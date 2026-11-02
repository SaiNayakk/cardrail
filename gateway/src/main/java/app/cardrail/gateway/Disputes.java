package app.cardrail.gateway;

import app.cardrail.common.ApiException;
import app.cardrail.common.trace.TraceReporter;
import app.cardrail.gateway.Merchants.Merchant;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Chargebacks and payouts, as the acquirer tells Railpay about them, and as merchants answer them. Each change is a
 * webhook ({@code dispute.created}, {@code dispute.won}, {@code dispute.lost}, {@code payout.paid}).
 */
@Service
public class Disputes {

    private static final Logger log = LoggerFactory.getLogger(Disputes.class);

    public record Dispute(String id, String paymentId, String merchantId, long amountCents, String reasonCode, String status,
                          String evidence, Instant createdAt) {}

    public record Payout(String id, String merchantId, long amountCents, long salesCents, long refundsCents, long feesCents,
                         long disputesCents, Instant createdAt) {}

    public record DisputeView(String id, String object, String payment, long amount, String reason, String reasonCode,
                              String status, Instant createdAt) {

        static DisputeView of(Dispute d) {
            return new DisputeView(d.id(), "dispute", d.paymentId(), d.amountCents(), Disputes.reason(d.reasonCode()), d.reasonCode(),
                    d.status().toLowerCase(), d.createdAt());
        }
    }

    public record PayoutView(String id, String object, long amount, long sales, long refunds, long fees, long disputes,
                             Instant createdAt) {

        static PayoutView of(Payout p) {
            return new PayoutView(p.id(), "payout", p.amountCents(), p.salesCents(), p.refundsCents(), p.feesCents(),
                    p.disputesCents(), p.createdAt());
        }
    }

    private static final String DISPUTE = "select id, payment_id, merchant_id, amount_cents, reason_code, status, evidence, created_at from disputes";
    private static final String PAYOUT = """
            select id, merchant_id, amount_cents, sales_cents, refunds_cents, fees_cents, disputes_cents, created_at from payouts""";

    private final JdbcClient jdbc;
    private final Clients clients;
    private final Merchants merchants;
    private final Webhooks webhooks;
    private final TraceReporter trace;
    private final TransactionTemplate tx;

    public Disputes(JdbcClient jdbc, Clients clients, Merchants merchants, Webhooks webhooks, TraceReporter trace,
                    TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.clients = clients;
        this.merchants = merchants;
        this.webhooks = webhooks;
        this.trace = trace;
        this.tx = tx;
    }

    /** What the acquirer tells us. Each kind is applied once, so a repeat delivery changes nothing. */
    public void notified(JsonNode event) {
        String type = event.path("type").asText();
        Optional<Merchant> merchant = merchants.byMid(event.path("mid").asText());
        if (merchant.isEmpty()) {
            log.warn("A {} notification for a merchant Railpay doesn't know ({})", type, event.path("mid").asText());
            return;
        }
        Merchant m = merchant.get();
        tx.executeWithoutResult(s -> {
            switch (type) {
                case "dispute.created" -> {
                    String paymentId = jdbc.sql("select id from payments where merchant_id = ? and rrn = ?")
                            .params(m.id(), event.path("rrn").asText()).query(String.class).optional().orElse(null);
                    if (paymentId == null) {
                        log.warn("A chargeback for {} matches no payment", event.path("rrn").asText());
                        return;
                    }
                    if (jdbc.sql("""
                            insert into disputes (id, payment_id, merchant_id, amount_cents, reason_code, status)
                            values (?, ?, ?, ?, ?, 'NEEDS_RESPONSE') on conflict (id) do nothing""")
                            .params(event.path("disputeId").asText(), paymentId, m.id(), event.path("amountCents").asLong(),
                                    event.path("reasonCode").asText()).update() == 1) {
                        webhooks.emit(m, "dispute.created", view(m.id(), event.path("disputeId").asText()));
                    }
                }
                case "dispute.won", "dispute.lost" -> {
                    String status = type.endsWith("won") ? "WON" : "LOST";
                    if (jdbc.sql("update disputes set status = ?, updated_at = now() where id = ? and status <> ?")
                            .params(status, event.path("disputeId").asText(), status).update() == 1) {
                        webhooks.emit(m, type, view(m.id(), event.path("disputeId").asText()));
                    }
                }
                case "payout.paid" -> {
                    if (jdbc.sql("""
                            insert into payouts (id, merchant_id, amount_cents, sales_cents, refunds_cents, fees_cents, disputes_cents)
                            values (?, ?, ?, ?, ?, ?, ?) on conflict (id) do nothing""")
                            .params(event.path("payoutId").asText(), m.id(), event.path("amountCents").asLong(),
                                    event.path("salesCents").asLong(), event.path("refundsCents").asLong(),
                                    event.path("feesCents").asLong(), event.path("disputesCents").asLong()).update() == 1) {
                        webhooks.emit(m, "payout.paid", PayoutView.of(payout(event.path("payoutId").asText())));
                    }
                }
                default -> log.warn("Unknown notification {}", type);
            }
        });
    }

    /** The merchant answers with evidence: the acquirer sends it to the issuer as a representment. */
    public DisputeView answer(Merchant merchant, String id, String evidence) {
        Dispute d = dispute(merchant.id(), id);
        if (!"NEEDS_RESPONSE".equals(d.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "already_answered", "this dispute was answered already");
        }
        call(() -> clients.acquirer().post().uri("/internal/v1/disputes/{id}/answer", id).body(Map.of("evidence", evidence))
                .retrieve().toBodilessEntity());
        jdbc.sql("update disputes set status = 'UNDER_REVIEW', evidence = ?, updated_at = now() where id = ?").params(evidence, id).update();
        trace.note(rrn(d), Parties.MERCHANT, Parties.GATEWAY, "The merchant answers the dispute with evidence", Map.of("dispute", id));
        return view(merchant.id(), id);
    }

    /** The merchant accepts: the cardholder keeps the money. */
    public DisputeView accept(Merchant merchant, String id) {
        Dispute d = dispute(merchant.id(), id);
        if (!"NEEDS_RESPONSE".equals(d.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "already_answered", "this dispute was answered already");
        }
        call(() -> clients.acquirer().post().uri("/internal/v1/disputes/{id}/accept", id).retrieve().toBodilessEntity());
        tx.executeWithoutResult(s -> {
            if (jdbc.sql("update disputes set status = 'LOST', updated_at = now() where id = ? and status <> 'LOST'").param(id).update() == 1) {
                webhooks.emit(merchant, "dispute.lost", view(merchant.id(), id));
            }
        });
        trace.note(rrn(d), Parties.MERCHANT, Parties.GATEWAY, "The merchant accepts the dispute", Map.of("dispute", id));
        return view(merchant.id(), id);
    }

    public List<DisputeView> list(String merchantId) {
        return jdbc.sql(DISPUTE + " where merchant_id = ? order by created_at desc limit 50").param(merchantId)
                .query(Dispute.class).list().stream().map(DisputeView::of).toList();
    }

    public List<PayoutView> payouts(String merchantId) {
        return jdbc.sql(PAYOUT + " where merchant_id = ? order by created_at desc limit 50").param(merchantId)
                .query(Payout.class).list().stream().map(PayoutView::of).toList();
    }

    public DisputeView view(String merchantId, String id) {
        return DisputeView.of(dispute(merchantId, id));
    }

    private Dispute dispute(String merchantId, String id) {
        return jdbc.sql(DISPUTE + " where merchant_id = ? and id = ?").params(merchantId, id).query(Dispute.class).optional()
                .orElseThrow(() -> ApiException.notFound("dispute " + id));
    }

    private Payout payout(String id) {
        return jdbc.sql(PAYOUT + " where id = ?").param(id).query(Payout.class).single();
    }

    private String rrn(Dispute d) {
        return jdbc.sql("select rrn from payments where id = ?").param(d.paymentId()).query(String.class).single();
    }

    private static void call(Runnable request) {
        try {
            request.run();
        } catch (RestClientResponseException e) {
            throw new ApiException(HttpStatus.CONFLICT, "already_answered", "the acquirer refused: this dispute was answered already");
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "unavailable", "disputes can't be answered right now; try again shortly");
        }
    }

    /** Card network reason codes in plain words. */
    static String reason(String code) {
        return switch (code) {
            case "1040" -> "fraud: the cardholder didn't make this payment";
            case "1310" -> "not received";
            case "1330" -> "not as described";
            case "1240" -> "charged twice";
            default -> "code " + code;
        };
    }
}
