package app.cardrail.gateway;

import app.cardrail.common.ApiException;
import app.cardrail.common.trace.TraceReporter;
import app.cardrail.gateway.Merchants.Merchant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A payment's story at the gateway: the card behind the token, a reference every party will know it by (the
 * RRN), the acquirer's answer. Card numbers pass through in memory only; the gateway keeps the BIN and last four.
 */
@Service
public class Payments {

    private static final Logger log = LoggerFactory.getLogger(Payments.class);

    public record Payment(String id, String merchantId, long amountCents, String currency, String description, String status,
                          String cardBrand, String cardBin, String cardLast4, String rrn, String authCode, String responseCode,
                          String declineReason, Instant createdAt, Instant updatedAt) {}

    record Revealed(String pan, String expiry, String cvc) {}

    record AcquirerResult(String rrn, String stan, String status, String responseCode, String responseText, String authCode) {}

    private final JdbcClient jdbc;
    private final Clients clients;
    private final TraceReporter trace;
    private final Clock clock;

    public Payments(JdbcClient jdbc, Clients clients, TraceReporter trace, Clock clock) {
        this.jdbc = jdbc;
        this.clients = clients;
        this.trace = trace;
        this.clock = clock;
    }

    public Payment authorise(Merchant merchant, long amountCents, String currency, String token, String description) {
        if (!"usd".equalsIgnoreCase(currency)) {
            throw ApiException.invalid("unsupported_currency", "Railpay takes payments in usd");
        }
        Revealed card = reveal(merchant, token);
        String rrn = nextRrn();
        String id = Keys.random("pay_", 20);
        jdbc.sql("""
                insert into payments (id, merchant_id, amount_cents, currency, description, status, card_brand, card_bin, card_last4, rrn)
                values (?, ?, ?, 'USD', ?, 'PENDING', ?, ?, ?, ?)""")
                .params(id, merchant.id(), amountCents, description, brand(card.pan()), card.pan().substring(0, 6),
                        card.pan().substring(card.pan().length() - 4), rrn)
                .update();
        trace.note(rrn, Parties.MERCHANT, Parties.GATEWAY, "Create a payment of " + dollars(amountCents),
                details("merchant", merchant.name(), "payment", id, "card", "**** " + card.pan().substring(card.pan().length() - 4)));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("mid", merchant.acquirerMid());
        request.put("pan", card.pan());
        request.put("expiry", card.expiry());
        if (card.cvc() != null) {
            request.put("cvv2", card.cvc());
        }
        request.put("amountCents", amountCents);
        request.put("currency", "USD");
        request.put("rrn", rrn);
        trace.note(rrn, Parties.GATEWAY, Parties.ACQUIRER, "Authorise " + dollars(amountCents),
                details("mid", merchant.acquirerMid(), "reference", rrn));

        String status;
        String reason = null;
        AcquirerResult result = null;
        try {
            result = clients.acquirer().post().uri("/internal/v1/authorisations").body(request).retrieve().body(AcquirerResult.class);
            status = switch (result.status()) {
                case "APPROVED" -> "AUTHORISED";
                case "DECLINED" -> "DECLINED";
                default -> "UNKNOWN";
            };
            if (!"AUTHORISED".equals(status)) {
                reason = result.responseText();
            }
        } catch (RestClientException e) {
            // We don't know if the acquirer sent it on: money may be held. Never call it declined.
            log.warn("Payment {} ({}): no answer from the acquirer: {}", id, rrn, e.getMessage());
            status = "UNKNOWN";
            reason = "No answer from the acquirer";
        }
        jdbc.sql("""
                update payments set status = ?, auth_code = ?, response_code = ?, decline_reason = ?, updated_at = now()
                where id = ?""")
                .params(status, result == null ? null : result.authCode(), result == null ? null : result.responseCode(), reason, id)
                .update();
        Payment payment = byId(merchant.id(), id).orElseThrow();
        trace.note(rrn, Parties.GATEWAY, Parties.MERCHANT, "Payment " + status.toLowerCase()
                        + (reason == null ? "" : ": " + reason),
                details("payment", id, "status", status.toLowerCase()));
        return payment;
    }

    public Optional<Payment> byId(String merchantId, String id) {
        return jdbc.sql("""
                select id, merchant_id, amount_cents, currency, description, status, card_brand, card_bin, card_last4, rrn,
                       auth_code, response_code, decline_reason, created_at, updated_at
                from payments where merchant_id = ? and id = ?""")
                .params(merchantId, id).query(Payment.class).optional();
    }

    public List<Payment> recent(String merchantId, int limit) {
        return jdbc.sql("""
                select id, merchant_id, amount_cents, currency, description, status, card_brand, card_bin, card_last4, rrn,
                       auth_code, response_code, decline_reason, created_at, updated_at
                from payments where merchant_id = ? order by created_at desc limit ?""")
                .params(merchantId, limit).query(Payment.class).list();
    }

    private Revealed reveal(Merchant merchant, String token) {
        if (token == null || !token.startsWith("tok_")) {
            throw ApiException.invalid("invalid_source", "source must be a card token (tok_...) from the hosted card form");
        }
        try {
            return clients.vault().post().uri("/internal/v1/tokens/{id}/reveal", token)
                    .body(Map.of("merchantId", merchant.id())).retrieve().body(Revealed.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw ApiException.invalid("invalid_source", "no such card token for this merchant");
        } catch (HttpClientErrorException.Conflict | HttpClientErrorException.Gone e) {
            throw ApiException.invalid("token_used", "this card token was used already or has expired; ask for the card again");
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "unavailable", "payments can't be taken right now; try again shortly");
        }
    }

    /**
     * The retrieval reference: the last digit of the year, the day of the year, the hour, then a sequence.
     * Twelve digits, like the references on a card statement, and unique for years.
     */
    String nextRrn() {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(ZoneOffset.UTC));
        long n = jdbc.sql("select nextval('rrn_seq')").query(Long.class).single();
        return String.format("%d%03d%02d%06d", now.getYear() % 10, now.getDayOfYear(), now.getHour(), n % 1_000_000);
    }

    static String brand(String pan) {
        if (pan.startsWith("28600")) return "kestrel";
        if (pan.startsWith("39")) return "meridian";
        return "unknown";
    }

    static String dollars(long cents) {
        return String.format("$%d.%02d", cents / 100, cents % 100);
    }

    private static Map<String, Object> details(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
