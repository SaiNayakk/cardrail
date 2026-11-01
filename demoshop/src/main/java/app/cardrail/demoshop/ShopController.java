package app.cardrail.demoshop;

import app.cardrail.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * What a shop's own server does at checkout: the browser gives it a card token from Railpay's hosted form, and it
 * asks Railpay to take the payment with its secret key. The shop never sees the card number.
 */
@RestController
class ShopController {

    @ConfigurationProperties("demoshop")
    record ShopProperties(String gatewayUrl, String secretKey, String publishableKey) {}

    record Product(String id, String name, long priceCents) {}

    /** Prices chosen to show outcomes: the $5 test card can buy a sticker or a mug, and nothing dearer. */
    static final List<Product> PRODUCTS = List.of(
            new Product("sticker", "Rail sticker", 300),
            new Product("mug", "Enamel mug", 450),
            new Product("tee", "Railway tee", 1999),
            new Product("lamp", "Signal lamp", 8900),
            new Product("map", "Framed route map", 25000));

    private final ShopProperties props;
    private final RestClient railpay;

    ShopController(ShopProperties props) {
        this.props = props;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(12));
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

    /** Takes payment for an order. The order id is the idempotency key, so a double click never pays twice. */
    @PostMapping("/shop/v1/checkout")
    JsonNode checkout(@Valid @RequestBody Checkout c) {
        Product product = PRODUCTS.stream().filter(p -> p.id().equals(c.product())).findFirst()
                .orElseThrow(() -> ApiException.invalid("unknown_product", "the shop doesn't sell that"));
        try {
            return railpay.post().uri("/v1/payments").header("Idempotency-Key", "demoshop-" + c.orderId())
                    .body(Map.of("amount", product.priceCents() * c.quantity(), "currency", "usd", "source", c.token(),
                            "description", c.quantity() + " x " + product.name()))
                    .retrieve().body(JsonNode.class);
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
