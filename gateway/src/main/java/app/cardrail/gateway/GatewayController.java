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

import java.time.Instant;
import java.util.List;

/** The merchant API (secret key) and the gateway's internal API. */
@RestController
class GatewayController {

    private final Merchants merchants;
    private final Payments payments;
    private final Idempotency idempotency;

    GatewayController(Merchants merchants, Payments payments, Idempotency idempotency) {
        this.merchants = merchants;
        this.payments = payments;
        this.idempotency = idempotency;
    }

    record CreatePayment(@NotNull @Min(1) @Max(99_999_999L) Long amount, @NotBlank String currency,
                         @NotBlank String source, @Size(max = 200) String description) {}

    record CardView(String brand, String last4) {}

    record PaymentView(String id, String object, long amount, String currency, String status, CardView card,
                       String reference, String authCode, String responseCode, String declineReason, String description,
                       Instant createdAt) {

        static PaymentView of(Payments.Payment p) {
            return new PaymentView(p.id(), "payment", p.amountCents(), p.currency().toLowerCase(), p.status().toLowerCase(),
                    new CardView(p.cardBrand(), p.cardLast4()), p.rrn(), p.authCode(), p.responseCode(), p.declineReason(),
                    p.description(), p.createdAt());
        }
    }

    /**
     * Authorises a card payment. A declined card is still a payment (status "declined", with the reason), so it
     * answers 201 either way; "unknown" means the network didn't answer in time and the payment will be reversed.
     */
    @PostMapping("/v1/payments")
    ResponseEntity<?> create(@RequestHeader(value = "Authorization", required = false) String authorization,
                             @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                             @Valid @RequestBody CreatePayment body) {
        Merchants.Merchant merchant = merchants.authenticate(authorization);
        return idempotency.run(merchant.id(), idempotencyKey, body, () -> ResponseEntity.status(HttpStatus.CREATED)
                .body(PaymentView.of(payments.authorise(merchant, body.amount(), body.currency(), body.source(), body.description()))));
    }

    @GetMapping("/v1/payments/{id}")
    PaymentView get(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String id) {
        Merchants.Merchant merchant = merchants.authenticate(authorization);
        return payments.byId(merchant.id(), id).map(PaymentView::of).orElseThrow(() -> ApiException.notFound("payment " + id));
    }

    record PaymentList(String object, List<PaymentView> data) {}

    @GetMapping("/v1/payments")
    PaymentList list(@RequestHeader(value = "Authorization", required = false) String authorization,
                     @RequestParam(defaultValue = "20") int limit) {
        Merchants.Merchant merchant = merchants.authenticate(authorization);
        return new PaymentList("list", payments.recent(merchant.id(), Math.max(1, Math.min(limit, 100))).stream()
                .map(PaymentView::of).toList());
    }

    record CreateMerchant(@NotBlank @Size(max = 60) String name, @Pattern(regexp = "[0-9]{4}") String mcc,
                          @NotBlank @Size(max = 40) String city, @Size(max = 300) String webhookUrl) {}

    record MerchantCreated(String id, String name, String mid, String secretKey, String publishableKey) {}

    /** Signs a merchant up. The secret key is in this answer only: it is stored hashed. */
    @PostMapping("/internal/v1/merchants")
    @ResponseStatus(HttpStatus.CREATED)
    MerchantCreated createMerchant(@Valid @RequestBody CreateMerchant body) {
        var created = merchants.create(body.name(), body.mcc(), body.city(), body.webhookUrl(), null, null);
        return new MerchantCreated(created.merchant().id(), created.merchant().name(), created.merchant().acquirerMid(),
                created.secretKey(), created.merchant().publishableKey());
    }

    record KeyOwner(String merchantId) {}

    /** The vault asks which merchant a publishable key belongs to. */
    @GetMapping("/internal/v1/publishable-keys/{key}")
    KeyOwner publishableKey(@PathVariable String key) {
        return merchants.byPublishableKey(key).map(m -> new KeyOwner(m.id()))
                .orElseThrow(() -> ApiException.notFound("publishable key"));
    }
}
