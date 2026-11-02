package app.cardrail.gateway;

import app.cardrail.common.ApiException;
import app.cardrail.common.trace.TraceReporter;
import app.cardrail.gateway.Merchants.Merchant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A payment's story at the gateway: authorise, capture (now or later), void, refund, and what happens when the
 * network never answers. Card numbers pass through in memory only; the gateway keeps the brand and last four,
 * and the vault keeps the card for refunds.
 *
 * <p>Every change of state and the webhook reporting it are written in one transaction ({@link Webhooks}).
 */
@Service
public class Payments {

    private static final Logger log = LoggerFactory.getLogger(Payments.class);

    public record Payment(String id, String merchantId, long amountCents, String currency, String description, String status,
                          String cardBrand, String cardBin, String cardLast4, String rrn, String authCode, String responseCode,
                          String declineReason, boolean capture, long capturedCents, long refundedCents, String cardRef,
                          Instant createdAt, Instant updatedAt) {}

    public record Refund(String id, String paymentId, long amountCents, String status, String rrn, String responseCode,
                         String failureReason, Instant createdAt) {}

    record Revealed(String pan, String expiry, String cvc, String cardRef) {}

    record AcquirerResult(String rrn, String stan, String status, String responseCode, String responseText, String authCode) {}

    record AcquirerOutcome(String rrn, String status) {}

    record AcquirerAuthorisation(String rrn, String kind, String status, long amountCents, long capturedCents) {}

    private static final String PAYMENT = """
            select id, merchant_id, amount_cents, currency, description, status, card_brand, card_bin, card_last4, rrn,
                   auth_code, response_code, decline_reason, capture, captured_cents, refunded_cents, card_ref, created_at, updated_at
            from payments""";

    private final JdbcClient jdbc;
    private final Clients clients;
    private final Merchants merchants;
    private final Webhooks webhooks;
    private final TraceReporter trace;
    private final TransactionTemplate tx;
    private final Clock clock;

    public Payments(JdbcClient jdbc, Clients clients, Merchants merchants, Webhooks webhooks, TraceReporter trace,
                    TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc;
        this.clients = clients;
        this.merchants = merchants;
        this.webhooks = webhooks;
        this.trace = trace;
        this.tx = tx;
        this.clock = clock;
    }

    public Payment authorise(Merchant merchant, long amountCents, String currency, String token, String description, boolean capture) {
        return authorise(merchant, amountCents, currency, token, description, capture, false);
    }

    /**
     * @param stepUp the caller can show the shopper a step-up challenge (API v3): if the card's bank asks for one, the
     *               payment waits as REQUIRES_ACTION instead of being declined
     */
    public Payment authorise(Merchant merchant, long amountCents, String currency, String token, String description, boolean capture,
                             boolean stepUp) {
        if (!"usd".equalsIgnoreCase(currency)) {
            throw ApiException.invalid("unsupported_currency", "Railpay takes payments in usd");
        }
        Revealed card = reveal(merchant, token);
        String rrn = nextRrn();
        String id = Keys.random("pay_", 20);
        jdbc.sql("""
                insert into payments (id, merchant_id, amount_cents, currency, description, status, card_brand, card_bin, card_last4,
                                      rrn, capture, card_ref)
                values (?, ?, ?, 'USD', ?, 'PENDING', ?, ?, ?, ?, ?, ?)""")
                .params(id, merchant.id(), amountCents, description, brand(card.pan()), card.pan().substring(0, 6),
                        card.pan().substring(card.pan().length() - 4), rrn, capture, card.cardRef())
                .update();
        trace.note(rrn, Parties.MERCHANT, Parties.GATEWAY, "Create a payment of " + dollars(amountCents),
                details("merchant", merchant.name(), "payment", id, "card", "**** " + card.pan().substring(card.pan().length() - 4),
                        "capture", capture ? "at once" : "later"));

        Outcome outcome = send(merchant, id, rrn, card, amountCents, null, null);
        if (stepUp && "DECLINED".equals(outcome.status()) && outcome.result() != null && "1A".equals(outcome.result().responseCode())) {
            return startStepUp(merchant, id, rrn, card, amountCents);
        }
        return settle(merchant, id, capture, outcome);
    }

    record Outcome(String status, String reason, AcquirerResult result) {}

    /** One authorisation through the acquirer, with the step-up proof when there is one. */
    private Outcome send(Merchant merchant, String id, String rrn, Revealed card, long amountCents, String authenticationId,
                         String authenticationValue) {
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
        if (authenticationValue != null) {
            request.put("authenticationId", authenticationId);
            request.put("authenticationValue", authenticationValue);
        }
        trace.note(rrn, Parties.GATEWAY, Parties.ACQUIRER, "Authorise " + dollars(amountCents) + (authenticationValue != null ? ", confirmed by the cardholder" : ""),
                details("mid", merchant.acquirerMid(), "reference", rrn));
        try {
            AcquirerResult result = clients.acquirer().post().uri("/internal/v1/authorisations").body(request).retrieve().body(AcquirerResult.class);
            String status = switch (result.status()) {
                case "APPROVED" -> "AUTHORISED";
                case "DECLINED" -> "DECLINED";
                default -> "UNKNOWN";
            };
            String reason = "AUTHORISED".equals(status) ? null
                    : "1A".equals(result.responseCode()) ? "The card's bank wants the cardholder to confirm this payment" : result.responseText();
            return new Outcome(status, reason, result);
        } catch (RestClientException e) {
            // We don't know if the acquirer sent it on: money may be held. Never call it declined.
            log.warn("Payment {} ({}): no answer from the acquirer: {}", id, rrn, e.getMessage());
            return new Outcome("UNKNOWN", "No answer from the acquirer", null);
        }
    }

    /** Records how an authorisation ended, tells the merchant, and captures at once if asked to. */
    private Payment settle(Merchant merchant, String id, boolean capture, Outcome outcome) {
        AcquirerResult answered = outcome.result();
        tx.executeWithoutResult(s -> {
            jdbc.sql("""
                    update payments set status = ?, auth_code = ?, response_code = ?, decline_reason = ?, updated_at = now()
                    where id = ?""")
                    .params(outcome.status(), answered == null ? null : answered.authCode(), answered == null ? null : answered.responseCode(),
                            outcome.reason(), id)
                    .update();
            if (!"UNKNOWN".equals(outcome.status()) && !(capture && "AUTHORISED".equals(outcome.status()))) {
                webhooks.emit(merchant, "payment." + outcome.status().toLowerCase(), view(merchant.id(), id));
            }
        });
        if (capture && "AUTHORISED".equals(outcome.status())) {
            return capture(merchant, id, null);
        }
        Payment payment = byId(merchant.id(), id).orElseThrow();
        reply(payment, "Payment " + outcome.status().toLowerCase() + (outcome.reason() == null ? "" : ": " + outcome.reason()));
        return payment;
    }

    record DirectoryStarted(String issuerId, String id, String status, String challengePath) {}

    record DirectoryResult(String id, String status, String value) {}

    /** The card's bank wants the cardholder to confirm: ask it, through Railnet's directory, and wait for the shopper. */
    private Payment startStepUp(Merchant merchant, String id, String rrn, Revealed card, long amountCents) {
        DirectoryStarted started;
        try {
            started = clients.directory().post().uri("/internal/v1/directory/authentications")
                    .body(Map.of("pan", card.pan(), "expiry", card.expiry(), "amountCents", amountCents, "merchant", merchant.name(),
                            "reference", rrn))
                    .retrieve().body(DirectoryStarted.class);
        } catch (RestClientException e) {
            log.warn("Payment {}: step-up couldn't be started: {}", id, e.getMessage());
            return settle(merchant, id, false, new Outcome("DECLINED", "The card's bank wants the cardholder to confirm, and couldn't be reached",
                    null));
        }
        jdbc.sql("""
                update payments set status = 'REQUIRES_ACTION', authentication_issuer = ?, authentication_id = ?, challenge_path = ?,
                       response_code = '1A', updated_at = now() where id = ?""")
                .params(started.issuerId(), started.id(), started.challengePath(), id).update();
        trace.note(rrn, Parties.GATEWAY, Parties.MERCHANT, "The cardholder must confirm it: show them their bank's page", Map.of("payment", id));
        return byId(merchant.id(), id).orElseThrow();
    }

    /**
     * After the shopper confirmed with their bank: the payment is authorised again, with the proof and a new
     * reference (the first attempt was answered, and its answer stands). Declined if the cardholder said no.
     */
    public Payment confirm(Merchant merchant, String id) {
        Payment payment = byId(merchant.id(), id).orElseThrow(() -> ApiException.notFound("payment " + id));
        if (!"REQUIRES_ACTION".equals(payment.status())) {
            return payment;   // confirmed already, or never needed it: the same answer again
        }
        record Step(String issuer, String authId) {}
        Step step = jdbc.sql("select authentication_issuer as issuer, authentication_id as auth_id from payments where id = ?").param(id).query(Step.class).single();
        DirectoryResult result;
        try {
            result = clients.directory().get().uri("/internal/v1/directory/authentications/{i}/{a}", step.issuer(), step.authId())
                    .retrieve().body(DirectoryResult.class);
        } catch (RestClientException e) {
            throw unavailable();
        }
        if ("PENDING".equals(result.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "not_confirmed_yet", "the cardholder hasn't confirmed this payment yet");
        }
        if (!"Y".equals(result.status())) {
            return settle(merchant, id, false, new Outcome("DECLINED", "The cardholder didn't confirm the payment", null));
        }
        Revealed card;
        try {
            card = clients.vault().post().uri("/internal/v1/cards/{id}/reveal", payment.cardRef())
                    .body(Map.of("merchantId", merchant.id())).retrieve().body(Revealed.class);
        } catch (RestClientException e) {
            throw unavailable();
        }
        String rrn = nextRrn();
        // claim the confirmation: two confirms at once must not authorise twice
        if (jdbc.sql("update payments set first_rrn = rrn, rrn = ?, status = 'PENDING', updated_at = now() where id = ? and status = 'REQUIRES_ACTION'")
                .params(rrn, id).update() == 0) {
            return byId(merchant.id(), id).orElseThrow();
        }
        trace.note(rrn, Parties.MERCHANT, Parties.GATEWAY, "The cardholder confirmed: authorise " + dollars(payment.amountCents()) + " again",
                details("payment", id, "firstAttempt", payment.rrn()));
        return settle(merchant, id, payment.capture(), send(merchant, id, rrn, card, payment.amountCents(), step.authId(), result.value()));
    }

    /** Where the shopper confirms: the card's bank's page, for a payment waiting on step-up. */
    public java.util.Optional<String> challengePath(String id) {
        return jdbc.sql("select challenge_path from payments where id = ? and status = 'REQUIRES_ACTION'").param(id).query(String.class).optional();
    }

    /** Takes the money for an authorised payment: all of it, or less (the rest of the hold is released at clearing). */
    public Payment capture(Merchant merchant, String id, Long amountCents) {
        Payment payment = byId(merchant.id(), id).orElseThrow(() -> ApiException.notFound("payment " + id));
        if ("CAPTURED".equals(payment.status())) {
            if (amountCents == null || amountCents == payment.capturedCents()) {
                return payment;
            }
            throw new ApiException(HttpStatus.CONFLICT, "already_captured", "this payment was captured already");
        }
        if (!"AUTHORISED".equals(payment.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "not_capturable",
                    "only an authorised payment can be captured (this one is " + payment.status().toLowerCase() + ")");
        }
        long cents = amountCents == null ? payment.amountCents() : amountCents;
        if (cents < 1 || cents > payment.amountCents()) {
            throw ApiException.invalid("capture_too_large", "a capture can't be more than was authorised");
        }
        trace.note(payment.rrn(), Parties.GATEWAY, Parties.ACQUIRER, "Capture " + dollars(cents), Map.of());
        try {
            clients.acquirer().post().uri("/internal/v1/authorisations/{rrn}/capture", payment.rrn())
                    .body(Map.of("amountCents", cents)).retrieve().body(AcquirerOutcome.class);
        } catch (RestClientResponseException e) {
            throw new ApiException(HttpStatus.CONFLICT, "not_capturable", "the acquirer refused the capture");
        } catch (RestClientException e) {
            throw unavailable();
        }
        tx.executeWithoutResult(s -> {
            jdbc.sql("update payments set status = 'CAPTURED', captured_cents = ?, updated_at = now() where id = ? and status = 'AUTHORISED'")
                    .params(cents, id).update();
            webhooks.emit(merchant, "payment.captured", view(merchant.id(), id));
        });
        Payment captured = byId(merchant.id(), id).orElseThrow();
        reply(captured, "Payment captured: " + dollars(cents));
        return captured;
    }

    /** Cancels an authorised payment before capture: the issuer releases the hold at once (0400). */
    public Payment voidPayment(Merchant merchant, String id) {
        Payment payment = byId(merchant.id(), id).orElseThrow(() -> ApiException.notFound("payment " + id));
        if ("VOIDED".equals(payment.status())) {
            return payment;
        }
        if (!"AUTHORISED".equals(payment.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "not_voidable", "only an authorised payment that isn't captured can be voided"
                    + ("CAPTURED".equals(payment.status()) ? "; refund it instead" : ""));
        }
        trace.note(payment.rrn(), Parties.MERCHANT, Parties.GATEWAY, "Void the payment", Map.of());
        trace.note(payment.rrn(), Parties.GATEWAY, Parties.ACQUIRER, "Void", Map.of());
        try {
            clients.acquirer().post().uri("/internal/v1/authorisations/{rrn}/void", payment.rrn()).retrieve().body(AcquirerOutcome.class);
        } catch (RestClientResponseException e) {
            throw new ApiException(HttpStatus.CONFLICT, "not_voidable", "the acquirer refused the void");
        } catch (RestClientException e) {
            throw unavailable();
        }
        // Reversed now, or owed and guaranteed: either way the hold will be released.
        tx.executeWithoutResult(s -> {
            jdbc.sql("update payments set status = 'VOIDED', updated_at = now() where id = ? and status = 'AUTHORISED'").param(id).update();
            webhooks.emit(merchant, "payment.voided", view(merchant.id(), id));
        });
        Payment voided = byId(merchant.id(), id).orElseThrow();
        reply(voided, "Payment voided");
        return voided;
    }

    /** Gives money back on a captured payment: all that is left, or part of it. */
    public Refund refund(Merchant merchant, String paymentId, Long amountCents) {
        String refundId = Keys.random("re_", 20);
        String rrn = nextRrn();
        Payment payment = tx.execute(s -> {
            Payment p = jdbc.sql(PAYMENT + " where merchant_id = ? and id = ? for update").params(merchant.id(), paymentId)
                    .query(Payment.class).optional().orElseThrow(() -> ApiException.notFound("payment " + paymentId));
            if (!"CAPTURED".equals(p.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "not_refundable",
                        "only a captured payment can be refunded" + ("AUTHORISED".equals(p.status()) ? "; void it instead" : ""));
            }
            long committed = jdbc.sql("select coalesce(sum(amount_cents), 0) from refunds where payment_id = ? and status <> 'FAILED'")
                    .param(paymentId).query(Long.class).single();
            long cents = amountCents == null ? p.capturedCents() - committed : amountCents;
            if (cents < 1) {
                throw ApiException.invalid("nothing_to_refund", "this payment has been refunded in full");
            }
            if (committed + cents > p.capturedCents()) {
                throw ApiException.invalid("refund_too_large", "that would refund more than was captured");
            }
            jdbc.sql("insert into refunds (id, payment_id, amount_cents, status, rrn) values (?, ?, ?, 'PENDING', ?)")
                    .params(refundId, paymentId, cents, rrn).update();
            return p;
        });
        long cents = refundById(refundId).orElseThrow().amountCents();
        trace.note(rrn, Parties.MERCHANT, Parties.GATEWAY, "Refund " + dollars(cents) + " of payment " + payment.rrn(),
                details("payment", paymentId, "refund", refundId));

        Revealed card;
        try {
            card = clients.vault().post().uri("/internal/v1/cards/{id}/reveal", payment.cardRef())
                    .body(Map.of("merchantId", merchant.id())).retrieve().body(Revealed.class);
        } catch (RestClientException e) {
            failRefund(merchant, refundId, null, "The card couldn't be found");
            throw unavailable();
        }
        trace.note(rrn, Parties.GATEWAY, Parties.ACQUIRER, "Refund " + dollars(cents), details("refundOf", payment.rrn()));
        AcquirerResult result;
        try {
            result = clients.acquirer().post().uri("/internal/v1/refunds")
                    .body(Map.of("originalRrn", payment.rrn(), "mid", merchant.acquirerMid(), "pan", card.pan(), "expiry", card.expiry(),
                            "amountCents", cents, "rrn", rrn))
                    .retrieve().body(AcquirerResult.class);
        } catch (HttpClientErrorException e) {
            failRefund(merchant, refundId, null, "The acquirer refused the refund");
            return refundById(refundId).orElseThrow();
        } catch (RestClientException e) {
            // The refund may still go through; it stays pending, and is never sent twice under another reference.
            log.warn("Refund {} ({}): no answer from the acquirer: {}", refundId, rrn, e.getMessage());
            return refundById(refundId).orElseThrow();
        }
        if ("APPROVED".equals(result.status())) {
            tx.executeWithoutResult(s -> {
                jdbc.sql("update refunds set status = 'SUCCEEDED', response_code = ?, updated_at = now() where id = ?")
                        .params(result.responseCode(), refundId).update();
                jdbc.sql("update payments set refunded_cents = refunded_cents + ?, updated_at = now() where id = ?")
                        .params(cents, paymentId).update();
                webhooks.emit(merchant, "refund.succeeded", PaymentView.RefundView.of(refundById(refundId).orElseThrow()));
            });
        } else if ("DECLINED".equals(result.status())) {
            failRefund(merchant, refundId, result.responseCode(), result.responseText());
        }
        Refund refund = refundById(refundId).orElseThrow();
        trace.note(rrn, Parties.GATEWAY, Parties.MERCHANT, "Refund " + refund.status().toLowerCase(), Map.of("refund", refundId));
        return refund;
    }

    private void failRefund(Merchant merchant, String refundId, String code, String reason) {
        tx.executeWithoutResult(s -> {
            jdbc.sql("update refunds set status = 'FAILED', response_code = ?, failure_reason = ?, updated_at = now() where id = ?")
                    .params(code, reason, refundId).update();
            webhooks.emit(merchant, "refund.failed", PaymentView.RefundView.of(refundById(refundId).orElseThrow()));
        });
    }

    /**
     * Payments whose outcome was unknown: the acquirer reverses each (0420) until the network hears it. Once it has,
     * the payment is REVERSED: the shopper was not charged, and the merchant is told.
     */
    @Scheduled(fixedDelayString = "${gateway.resolve-every:5s}", initialDelayString = "${gateway.resolve-every:5s}")
    public void resolveUnknown() {
        List<Payment> unknown = jdbc.sql(PAYMENT + " where status = 'UNKNOWN' order by updated_at limit 50").query(Payment.class).list();
        for (Payment p : unknown) {
            try {
                AcquirerAuthorisation a = clients.acquirer().get().uri("/internal/v1/authorisations/{rrn}", p.rrn())
                        .retrieve().body(AcquirerAuthorisation.class);
                if ("REVERSED".equals(a.status()) || "DECLINED".equals(a.status())) {
                    Merchant merchant = merchants.byId(p.merchantId()).orElseThrow();
                    tx.executeWithoutResult(s -> {
                        jdbc.sql("update payments set status = 'REVERSED', decline_reason = ?, updated_at = now() where id = ? and status = 'UNKNOWN'")
                                .params("The network didn't answer in time, so the payment was reversed", p.id()).update();
                        webhooks.emit(merchant, "payment.reversed", view(merchant.id(), p.id()));
                    });
                    reply(byId(p.merchantId(), p.id()).orElseThrow(), "Payment reversed: the network didn't answer in time");
                }
            } catch (HttpClientErrorException.NotFound e) {
                // the acquirer never recorded it, so nothing was sent anywhere
                jdbc.sql("update payments set status = 'FAILED', updated_at = now() where id = ? and status = 'UNKNOWN'").param(p.id()).update();
            } catch (RestClientException e) {
                return;   // the acquirer is unreachable: try again next round
            }
        }
        // Refunds whose answer was lost: the acquirer knows how each ended.
        List<Refund> pending = jdbc.sql("""
                select id, payment_id, amount_cents, status, rrn, response_code, failure_reason, created_at
                from refunds where status = 'PENDING' and updated_at < now() - interval '10 seconds' order by created_at limit 50""")
                .query(Refund.class).list();
        for (Refund r : pending) {
            try {
                AcquirerAuthorisation a = clients.acquirer().get().uri("/internal/v1/authorisations/{rrn}", r.rrn())
                        .retrieve().body(AcquirerAuthorisation.class);
                Payment p = jdbc.sql(PAYMENT + " where id = ?").param(r.paymentId()).query(Payment.class).single();
                Merchant merchant = merchants.byId(p.merchantId()).orElseThrow();
                switch (a.status()) {
                    case "APPROVED" -> tx.executeWithoutResult(s -> {
                        if (jdbc.sql("update refunds set status = 'SUCCEEDED', updated_at = now() where id = ? and status = 'PENDING'")
                                .param(r.id()).update() == 1) {
                            jdbc.sql("update payments set refunded_cents = refunded_cents + ?, updated_at = now() where id = ?")
                                    .params(r.amountCents(), r.paymentId()).update();
                            webhooks.emit(merchant, "refund.succeeded", PaymentView.RefundView.of(refundById(r.id()).orElseThrow()));
                        }
                    });
                    case "DECLINED", "REVERSED" -> failRefund(merchant, r.id(), null, "The refund didn't go through; try again");
                    default -> { }
                }
            } catch (HttpClientErrorException.NotFound e) {
                Payment p = jdbc.sql(PAYMENT + " where id = ?").param(r.paymentId()).query(Payment.class).single();
                failRefund(merchants.byId(p.merchantId()).orElseThrow(), r.id(), null, "The refund never reached the acquirer");
            } catch (RestClientException e) {
                return;
            }
        }
    }

    public Optional<Payment> byId(String merchantId, String id) {
        return jdbc.sql(PAYMENT + " where merchant_id = ? and id = ?").params(merchantId, id).query(Payment.class).optional();
    }

    public List<Payment> recent(String merchantId, int limit) {
        return jdbc.sql(PAYMENT + " where merchant_id = ? order by created_at desc limit ?")
                .params(merchantId, limit).query(Payment.class).list();
    }

    public List<Refund> refunds(String paymentId) {
        return jdbc.sql("""
                select id, payment_id, amount_cents, status, rrn, response_code, failure_reason, created_at
                from refunds where payment_id = ? order by created_at""")
                .param(paymentId).query(Refund.class).list();
    }

    public Optional<Refund> refundById(String id) {
        return jdbc.sql("""
                select id, payment_id, amount_cents, status, rrn, response_code, failure_reason, created_at
                from refunds where id = ?""")
                .param(id).query(Refund.class).optional();
    }

    public PaymentView view(String merchantId, String id) {
        Payment p = byId(merchantId, id).orElseThrow(() -> ApiException.notFound("payment " + id));
        return PaymentView.of(p, refunds(id));
    }

    public PaymentView viewV3(String merchantId, String id) {
        Payment p = byId(merchantId, id).orElseThrow(() -> ApiException.notFound("payment " + id));
        return PaymentView.v3(p, refunds(id), challengePath(id).orElse(null));
    }

    private void reply(Payment payment, String summary) {
        trace.note(payment.rrn(), Parties.GATEWAY, Parties.MERCHANT, summary,
                details("payment", payment.id(), "status", payment.status().toLowerCase()));
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
            throw unavailable();
        }
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "unavailable", "payments can't be taken right now; try again shortly");
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
