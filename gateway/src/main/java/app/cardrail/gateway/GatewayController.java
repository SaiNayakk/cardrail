package app.cardrail.gateway;

import app.cardrail.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** The merchant API (secret key) and the gateway's internal API. Every write takes an Idempotency-Key. */
@RestController
class GatewayController {

    private final Merchants merchants;
    private final Payments payments;
    private final Disputes disputes;
    private final Idempotency idempotency;

    GatewayController(Merchants merchants, Payments payments, Disputes disputes, Idempotency idempotency) {
        this.merchants = merchants;
        this.payments = payments;
        this.disputes = disputes;
        this.idempotency = idempotency;
    }

    @GetMapping({"/v2/disputes", "/v3/disputes"})
    PaymentList listDisputes(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return new PaymentList("list", disputes.list(merchants.authenticate(authorization).id()));
    }

    @GetMapping({"/v2/disputes/{id}", "/v3/disputes/{id}"})
    Disputes.DisputeView dispute(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String id) {
        return disputes.view(merchants.authenticate(authorization).id(), id);
    }

    record Evidence(@NotBlank @Size(max = 2000) String evidence) {}

    /** Answers a dispute with evidence (a delivery record, a signed receipt): the cardholder's bank decides. */
    @PostMapping({"/v2/disputes/{id}/evidence", "/v3/disputes/{id}/evidence"})
    Disputes.DisputeView evidence(@RequestHeader(value = "Authorization", required = false) String authorization,
                                  @PathVariable String id, @Valid @RequestBody Evidence body) {
        return disputes.answer(merchants.authenticate(authorization), id, body.evidence());
    }

    /** Accepts a dispute: the cardholder keeps the money. */
    @PostMapping({"/v2/disputes/{id}/accept", "/v3/disputes/{id}/accept"})
    Disputes.DisputeView acceptDispute(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @PathVariable String id) {
        return disputes.accept(merchants.authenticate(authorization), id);
    }

    @GetMapping({"/v2/payouts", "/v3/payouts"})
    PaymentList payouts(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return new PaymentList("list", disputes.payouts(merchants.authenticate(authorization).id()));
    }

    /** The acquirer tells Railpay about chargebacks and payouts. */
    @PostMapping("/internal/v1/notifications")
    ResponseEntity<Void> notified(@RequestBody com.fasterxml.jackson.databind.JsonNode event) {
        disputes.notified(event);
        return ResponseEntity.noContent().build();
    }

    record CreatePayment(@NotNull @Min(1) @Max(99_999_999L) Long amount, @NotBlank String currency,
                         @NotBlank String source, @Size(max = 200) String description, Boolean capture) {}

    /**
     * Authorises a card payment, and captures it at once unless {@code capture} is false. A declined card is still a
     * payment (status "declined", with the reason), so it answers 201 either way; "unknown" means the network didn't
     * answer in time: the payment is being reversed, and a webhook says when it has been.
     */
    @PostMapping("/v2/payments")
    ResponseEntity<?> create(@RequestHeader(value = "Authorization", required = false) String authorization,
                             @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                             @Valid @RequestBody CreatePayment body) {
        Merchants.Merchant merchant = merchants.authenticate(authorization);
        boolean capture = body.capture() == null || body.capture();
        return idempotency.run(merchant.id(), "v2:" + idempotencyKey, idempotencyKey, body, () -> {
            var payment = payments.authorise(merchant, body.amount(), body.currency(), body.source(), body.description(), capture);
            return ResponseEntity.status(HttpStatus.CREATED).body(payments.view(merchant.id(), payment.id()));
        });
    }

    // Version 3 (contracts/gateway-v3.yaml): version 2 with step-up. A payment the card's bank wants confirmed waits as
    // "requires_action" with a page for the shopper; the merchant confirms it once they have. Everything else is shared.

    @PostMapping("/v3/payments")
    ResponseEntity<?> createV3(@RequestHeader(value = "Authorization", required = false) String authorization,
                               @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                               @Valid @RequestBody CreatePayment body) {
        Merchants.Merchant merchant = merchants.authenticate(authorization);
        boolean capture = body.capture() == null || body.capture();
        return idempotency.run(merchant.id(), "v3:" + idempotencyKey, idempotencyKey, body, () -> {
            var payment = payments.authorise(merchant, body.amount(), body.currency(), body.source(), body.description(), capture, true);
            return ResponseEntity.status(HttpStatus.CREATED).body(payments.viewV3(merchant.id(), payment.id()));
        });
    }

    /** After the shopper confirmed with their bank: authorise again with the proof. Safe to repeat. */
    @PostMapping("/v3/payments/{id}/confirm")
    PaymentView confirm(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String id) {
        Merchants.Merchant merchant = merchants.authenticate(authorization);
        payments.confirm(merchant, id);
        return payments.viewV3(merchant.id(), id);
    }

    @GetMapping("/v3/payments/{id}")
    PaymentView getV3(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String id) {
        return payments.viewV3(merchants.authenticate(authorization).id(), id);
    }

    @GetMapping("/v3/payments")
    PaymentList listV3(@RequestHeader(value = "Authorization", required = false) String authorization,
                       @RequestParam(defaultValue = "20") int limit) {
        Merchants.Merchant merchant = merchants.authenticate(authorization);
        return new PaymentList("list", payments.recent(merchant.id(), Math.max(1, Math.min(limit, 100))).stream()
                .map(p -> PaymentView.v3(p, payments.refunds(p.id()), payments.challengePath(p.id()).orElse(null))).toList());
    }

    @GetMapping("/v2/payments/{id}")
    PaymentView get(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String id) {
        Merchants.Merchant merchant = merchants.authenticate(authorization);
        return payments.view(merchant.id(), id);
    }

    record PaymentList(String object, List<?> data) {}

    @GetMapping("/v2/payments")
    PaymentList list(@RequestHeader(value = "Authorization", required = false) String authorization,
                     @RequestParam(defaultValue = "20") int limit) {
        Merchants.Merchant merchant = merchants.authenticate(authorization);
        return new PaymentList("list", payments.recent(merchant.id(), Math.max(1, Math.min(limit, 100))).stream()
                .map(p -> PaymentView.of(p, payments.refunds(p.id()))).toList());
    }

    // Version 1, as first published (contracts/gateway-v1.yaml): authorise only, never captured, and only the
    // statuses it listed. A state it doesn't know is shown as the nearest one it does.

    @PostMapping("/v1/payments")
    ResponseEntity<?> createV1(@RequestHeader(value = "Authorization", required = false) String authorization,
                               @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                               @Valid @RequestBody CreatePayment body) {
        Merchants.Merchant merchant = merchants.authenticate(authorization);
        CreatePayment v1 = new CreatePayment(body.amount(), body.currency(), body.source(), body.description(), null);
        return idempotency.run(merchant.id(), idempotencyKey, idempotencyKey, v1, () -> {
            var payment = payments.authorise(merchant, v1.amount(), v1.currency(), v1.source(), v1.description(), false);
            return ResponseEntity.status(HttpStatus.CREATED).body(PaymentV1.of(payment));
        });
    }

    @GetMapping("/v1/payments/{id}")
    PaymentV1 getV1(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String id) {
        Merchants.Merchant merchant = merchants.authenticate(authorization);
        return payments.byId(merchant.id(), id).map(PaymentV1::of).orElseThrow(() -> ApiException.notFound("payment " + id));
    }

    @GetMapping("/v1/payments")
    PaymentList listV1(@RequestHeader(value = "Authorization", required = false) String authorization,
                       @RequestParam(defaultValue = "20") int limit) {
        Merchants.Merchant merchant = merchants.authenticate(authorization);
        return new PaymentList("list", payments.recent(merchant.id(), Math.max(1, Math.min(limit, 100))).stream()
                .map(PaymentV1::of).toList());
    }

    record PaymentV1(String id, String object, long amount, String currency, String status, PaymentView.CardView card,
                     String reference, String authCode, String responseCode, String declineReason, String description,
                     java.time.Instant createdAt) {

        static PaymentV1 of(Payments.Payment p) {
            String status = switch (p.status()) {
                case "CAPTURED" -> "authorised";             // approved, and will be paid
                case "VOIDED", "REVERSED" -> "failed";       // not charged
                case "REQUIRES_ACTION" -> "pending";         // waiting on step-up (made through v3)
                default -> p.status().toLowerCase();
            };
            return new PaymentV1(p.id(), "payment", p.amountCents(), p.currency().toLowerCase(), status,
                    new PaymentView.CardView(p.cardBrand(), p.cardLast4()), p.rrn(), p.authCode(), p.responseCode(),
                    p.declineReason(), p.description(), p.createdAt());
        }
    }

    record Capture(@Min(1) @Max(99_999_999L) Long amount) {}

    /** Captures an authorised payment: all of it, or {@code amount} (the rest of the hold is released). */
    @PostMapping({"/v2/payments/{id}/capture", "/v3/payments/{id}/capture"})
    ResponseEntity<?> capture(@RequestHeader(value = "Authorization", required = false) String authorization,
                              @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                              @PathVariable String id, @Valid @RequestBody(required = false) Capture body) {
        Merchants.Merchant merchant = merchants.authenticate(authorization);
        Long amount = body == null ? null : body.amount();
        return idempotency.run(merchant.id(), "v2:" + idempotencyKey, idempotencyKey, Map.of("capture", id, "amount", String.valueOf(amount)), () -> {
            payments.capture(merchant, id, amount);
            return ResponseEntity.ok(payments.view(merchant.id(), id));
        });
    }

    /** Cancels an authorised payment that hasn't been captured: the cardholder's hold is released. */
    @PostMapping({"/v2/payments/{id}/void", "/v3/payments/{id}/void"})
    ResponseEntity<?> voidPayment(@RequestHeader(value = "Authorization", required = false) String authorization,
                                  @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                  @PathVariable String id) {
        Merchants.Merchant merchant = merchants.authenticate(authorization);
        return idempotency.run(merchant.id(), "v2:" + idempotencyKey, idempotencyKey, Map.of("void", id), () -> {
            payments.voidPayment(merchant, id);
            return ResponseEntity.ok(payments.view(merchant.id(), id));
        });
    }

    record CreateRefund(@Min(1) @Max(99_999_999L) Long amount) {}

    /** Gives money back on a captured payment: all that is left, or {@code amount}. */
    @PostMapping({"/v2/payments/{id}/refunds", "/v3/payments/{id}/refunds"})
    ResponseEntity<?> refund(@RequestHeader(value = "Authorization", required = false) String authorization,
                             @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                             @PathVariable String id, @Valid @RequestBody(required = false) CreateRefund body) {
        Merchants.Merchant merchant = merchants.authenticate(authorization);
        Long amount = body == null ? null : body.amount();
        return idempotency.run(merchant.id(), "v2:" + idempotencyKey, idempotencyKey, Map.of("refund", id, "amount", String.valueOf(amount)), () ->
                ResponseEntity.status(HttpStatus.CREATED).body(PaymentView.RefundView.of(payments.refund(merchant, id, amount))));
    }

    record CreateMerchant(@NotBlank @Size(max = 60) String name, @Pattern(regexp = "[0-9]{4}") String mcc,
                          @NotBlank @Size(max = 40) String city, @Size(max = 300) String webhookUrl) {}

    record MerchantCreated(String id, String name, String mid, String secretKey, String publishableKey, String webhookSecret) {}

    /** Signs a merchant up. The secret key is in this answer only: it is stored hashed. */
    @PostMapping("/internal/v1/merchants")
    @ResponseStatus(HttpStatus.CREATED)
    MerchantCreated createMerchant(@Valid @RequestBody CreateMerchant body) {
        var created = merchants.create(body.name(), body.mcc(), body.city(), body.webhookUrl(), null, null, null);
        return new MerchantCreated(created.merchant().id(), created.merchant().name(), created.merchant().acquirerMid(),
                created.secretKey(), created.merchant().publishableKey(), created.webhookSecret());
    }

    record KeyOwner(String merchantId) {}

    /** The vault asks which merchant a publishable key belongs to. */
    @GetMapping("/internal/v1/publishable-keys/{key}")
    KeyOwner publishableKey(@PathVariable String key) {
        return merchants.byPublishableKey(key).map(m -> new KeyOwner(m.id()))
                .orElseThrow(() -> ApiException.notFound("publishable key"));
    }
}
