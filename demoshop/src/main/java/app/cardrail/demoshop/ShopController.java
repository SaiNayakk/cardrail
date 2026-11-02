package app.cardrail.demoshop;

import app.cardrail.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * What a shop's own server does: at checkout, the browser gives it a card token from Railpay's hosted form, and it
 * asks Railpay to take the payment with its secret key. It never sees the card number. Railpay tells it what
 * happens afterwards by signed webhooks, which it checks before believing.
 */
@RestController
class ShopController {

    @ConfigurationProperties("demoshop")
    record ShopProperties(String gatewayUrl, String secretKey, String publishableKey, String webhookSecret) {}

    record Product(String id, String name, long priceCents) {}

    /** Prices chosen to show outcomes: the $5 test card can buy a sticker or a mug, and nothing dearer. */
    static final List<Product> PRODUCTS = List.of(
            new Product("sticker", "Rail sticker", 300),
            new Product("mug", "Enamel mug", 450),
            new Product("tee", "Railway tee", 1999),
            new Product("lamp", "Signal lamp", 8900),
            new Product("map", "Framed route map", 25000));

    /** Webhooks older than this are refused: a delivery someone captured can't be replayed later. */
    static final Duration WEBHOOK_TOLERANCE = Duration.ofMinutes(5);

    private final ShopProperties props;
    private final ObjectMapper json;
    private final RestClient railpay;
    private final Deque<JsonNode> received = new ArrayDeque<>();

    ShopController(ShopProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.railpay = RestClient.builder().baseUrl(props.gatewayUrl()).requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + props.secretKey()).build();
    }

    record Config(String publishableKey, List<Product> products) {}

    @GetMapping("/shop/v1/config")
    Config config() {
        return new Config(props.publishableKey(), PRODUCTS);
    }

    record Checkout(@NotBlank String product, @Min(1) @Max(5) int quantity, @NotBlank String token,
                    @Pattern(regexp = "[A-Za-z0-9-]{8,64}") String orderId) {}

    /** Takes payment for an order, captured at once. The order id is the idempotency key, so a double click never pays twice. */
    @PostMapping("/shop/v1/checkout")
    JsonNode checkout(@Valid @RequestBody Checkout c) {
        Product product = PRODUCTS.stream().filter(p -> p.id().equals(c.product())).findFirst()
                .orElseThrow(() -> ApiException.invalid("unknown_product", "the shop doesn't sell that"));
        return call(() -> railpay.post().uri("/v2/payments").header("Idempotency-Key", "demoshop-" + c.orderId())
                .body(Map.of("amount", product.priceCents() * c.quantity(), "currency", "usd", "source", c.token(),
                        "description", c.quantity() + " x " + product.name(), "capture", true))
                .retrieve().body(JsonNode.class));
    }

    /** The shop's recent payments, as Railpay has them: the shop's own view of its takings. */
    @GetMapping("/shop/v1/payments")
    JsonNode payments() {
        return call(() -> railpay.get().uri("/v2/payments?limit=20").retrieve().body(JsonNode.class));
    }

    record RefundRequest(@Min(1) Long amount, @Pattern(regexp = "[A-Za-z0-9-]{8,64}") String requestId) {}

    /** Gives money back for a payment. The request id makes a double click harmless. */
    @PostMapping("/shop/v1/payments/{id}/refund")
    JsonNode refund(@PathVariable @Pattern(regexp = "pay_[A-Za-z0-9]+") String id, @Valid @RequestBody RefundRequest r) {
        return call(() -> railpay.post().uri("/v2/payments/{id}/refunds", id).header("Idempotency-Key", "demoshop-" + r.requestId())
                .body(r.amount() == null ? Map.of() : Map.of("amount", r.amount())).retrieve().body(JsonNode.class));
    }

    /** Chargebacks against the shop's payments. */
    @GetMapping("/shop/v1/disputes")
    JsonNode disputes() {
        return call(() -> railpay.get().uri("/v2/disputes").retrieve().body(JsonNode.class));
    }

    record DisputeAnswer(@NotBlank @jakarta.validation.constraints.Size(max = 2000) String evidence) {}

    /** The shop answers a chargeback with its evidence. */
    @PostMapping("/shop/v1/disputes/{id}/evidence")
    JsonNode answer(@PathVariable @Pattern(regexp = "dsp_[A-Za-z0-9]+") String id, @Valid @RequestBody DisputeAnswer a) {
        return call(() -> railpay.post().uri("/v2/disputes/{id}/evidence", id).body(Map.of("evidence", a.evidence()))
                .retrieve().body(JsonNode.class));
    }

    /** The shop accepts a chargeback. */
    @PostMapping("/shop/v1/disputes/{id}/accept")
    JsonNode accept(@PathVariable @Pattern(regexp = "dsp_[A-Za-z0-9]+") String id) {
        return call(() -> railpay.post().uri("/v2/disputes/{id}/accept", id).retrieve().body(JsonNode.class));
    }

    /** What Railpay paid the shop. */
    @GetMapping("/shop/v1/payouts")
    JsonNode payouts() {
        return call(() -> railpay.get().uri("/v2/payouts").retrieve().body(JsonNode.class));
    }

    /** Railpay's webhooks. Believed only with a valid, recent signature. */
    @PostMapping("/shop/v1/webhooks")
    ResponseEntity<Void> webhook(@RequestHeader(value = "Railpay-Signature", required = false) String signature,
                                 @RequestBody String body) throws IOException {
        if (!verified(signature, body, Instant.now())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        JsonNode event = json.readTree(body);
        synchronized (received) {
            if (received.stream().noneMatch(e -> e.path("id").equals(event.path("id")))) {
                received.addFirst(event);
                while (received.size() > 50) {
                    received.removeLast();
                }
            }
        }
        return ResponseEntity.ok().build();
    }

    /** What Railpay has told the shop, newest first. */
    @GetMapping("/shop/v1/webhooks")
    List<JsonNode> webhooks() {
        synchronized (received) {
            return List.copyOf(received);
        }
    }

    boolean verified(String header, String body, Instant now) {
        if (header == null || props.webhookSecret() == null || props.webhookSecret().isBlank()) {
            return false;
        }
        long t = -1;
        String v1 = null;
        for (String part : header.split(",")) {
            if (part.startsWith("t=")) {
                try {
                    t = Long.parseLong(part.substring(2));
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (part.startsWith("v1=")) {
                v1 = part.substring(3);
            }
        }
        if (t < 0 || v1 == null || Math.abs(now.getEpochSecond() - t) > WEBHOOK_TOLERANCE.toSeconds()) {
            return false;
        }
        String expected = hmac(props.webhookSecret(), t + "." + body);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), v1.getBytes(StandardCharsets.US_ASCII));
    }

    static String hmac(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode call(Supplier<JsonNode> request) {
        try {
            return request.get();
        } catch (HttpStatusCodeException e) {
            HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
            JsonNode problem = e.getResponseBodyAs(JsonNode.class);
            String detail = problem != null && problem.has("detail") ? problem.get("detail").asText() : "the payment couldn't be made";
            String code = problem != null && problem.has("code") ? problem.get("code").asText() : "payment_failed";
            throw new ApiException(status != null && status.is4xxClientError() ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.BAD_GATEWAY,
                    code, detail);
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "railpay_unreachable", "the shop couldn't reach Railpay; try again");
        }
    }
}
