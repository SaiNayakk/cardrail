package app.cardrail.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.name=gateway",
        "cardrail.internal-key=test-internal-key-0123456789"})
class PaymentsApiTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    static final ObjectMapper JSON = new ObjectMapper();
    static final AtomicInteger authorisations = new AtomicInteger();
    static final AtomicInteger merchantsMade = new AtomicInteger();
    static final HttpServer fakes;

    static {
        try {
            // The vault and the acquirer, faked: tokens ending in "decline" are declined cards.
            fakes = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            fakes.createContext("/internal/v1/merchants", ex -> reply(ex, 201,
                    Map.of("mid", String.format("RPA%012d", merchantsMade.incrementAndGet()))));
            fakes.createContext("/internal/v1/tokens/", ex -> {
                String token = ex.getRequestURI().getPath().split("/")[4];
                if (token.equals("tok_missing")) {
                    reply(ex, 404, Map.of("code", "not_found"));
                } else {
                    reply(ex, 200, Map.of("pan", token.endsWith("decline") ? "2860010000000020" : "2860010000000012",
                            "expiry", "3012", "cvc", "123"));
                }
            });
            fakes.createContext("/internal/v1/authorisations", ex -> {
                authorisations.incrementAndGet();
                JsonNode body = JSON.readTree(ex.getRequestBody());
                boolean decline = body.get("pan").asText().endsWith("0020");
                reply(ex, 200, decline
                        ? Map.of("rrn", body.get("rrn").asText(), "stan", "000001", "status", "DECLINED", "responseCode", "51", "responseText", "Insufficient funds")
                        : Map.of("rrn", body.get("rrn").asText(), "stan", "000001", "status", "APPROVED", "responseCode", "00", "responseText", "Approved", "authCode", "QWE123"));
            });
            fakes.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void reply(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&currentSchema=gateway");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        String fake = "http://127.0.0.1:" + fakes.getAddress().getPort();
        registry.add("gateway.vault-url", () -> fake);
        registry.add("gateway.acquirer-url", () -> fake);
    }

    @AfterAll
    static void stop() {
        fakes.stop(0);
    }

    @Autowired TestRestTemplate http;
    @Autowired Merchants merchants;
    private String secretKey;

    @BeforeEach
    void merchant() {
        secretKey = merchants.create("Test shop", "5999", "Boston", null, null, null).secretKey();
    }

    @Test
    void authorisesAPayment() throws Exception {
        var response = pay("tok_good", 1999, null);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        JsonNode payment = JSON.readTree(response.getBody());
        assertThat(payment.get("id").asText()).startsWith("pay_");
        assertThat(payment.get("status").asText()).isEqualTo("authorised");
        assertThat(payment.get("authCode").asText()).isEqualTo("QWE123");
        assertThat(payment.get("card").get("last4").asText()).isEqualTo("0012");
        assertThat(payment.get("reference").asText()).hasSize(12);
        assertThat(response.getBody()).doesNotContain("2860010000000012");

        var fetched = checked("GET", "/payments/" + payment.get("id").asText(), http.exchange(
                "/v1/payments/" + payment.get("id").asText(), HttpMethod.GET, new HttpEntity<>(headers(null)), String.class));
        assertThat(JSON.readTree(fetched.getBody()).get("status").asText()).isEqualTo("authorised");
    }

    @Test
    void aDeclineIsAPaymentWithItsReason() throws Exception {
        JsonNode payment = JSON.readTree(pay("tok_decline", 1999, null).getBody());
        assertThat(payment.get("status").asText()).isEqualTo("declined");
        assertThat(payment.get("declineReason").asText()).isEqualTo("Insufficient funds");
    }

    @Test
    void aRepeatWithTheSameIdempotencyKeyIsAnsweredWithoutPayingTwice() throws Exception {
        int before = authorisations.get();
        var first = pay("tok_good", 500, "order-42");
        var again = pay("tok_good", 500, "order-42");

        assertThat(authorisations.get() - before).isEqualTo(1);
        assertThat(JSON.readTree(again.getBody()).get("id")).isEqualTo(JSON.readTree(first.getBody()).get("id"));
        assertThat(again.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");

        var different = pay("tok_good", 501, "order-42");
        assertThat(different.getStatusCode().value()).isEqualTo(422);
        assertThat(different.getBody()).contains("idempotency_key_reused");
    }

    @Test
    void refusesWithoutAValidSecretKey() {
        var response = http.exchange("/v1/payments", HttpMethod.POST,
                new HttpEntity<>(Map.of("amount", 100, "currency", "usd", "source", "tok_good"), new HttpHeaders()), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(401);

        HttpHeaders wrong = new HttpHeaders();
        wrong.set("Authorization", "Bearer sk_test_nope");
        response = http.exchange("/v1/payments", HttpMethod.POST,
                new HttpEntity<>(Map.of("amount", 100, "currency", "usd", "source", "tok_good"), wrong), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void refusesUnknownTokensAndCurrencies() {
        assertThat(pay("tok_missing", 100, null).getBody()).contains("invalid_source");
        var euros = http.exchange("/v1/payments", HttpMethod.POST,
                new HttpEntity<>(Map.of("amount", 100, "currency", "eur", "source", "tok_good"), headers(null)), String.class);
        assertThat(euros.getBody()).contains("unsupported_currency");
    }

    /** Every answer the merchant API gives must be what the published contract says. */
    static final com.atlassian.oai.validator.OpenApiInteractionValidator CONTRACT =
            com.atlassian.oai.validator.OpenApiInteractionValidator.createForSpecificationUrl(
                            java.nio.file.Path.of("../contracts/gateway-v1.yaml").toUri().toString())
                    .withBasePathOverride("/v1")
                    .build();

    private static ResponseEntity<String> checked(String method, String path, ResponseEntity<String> response) {
        var simple = com.atlassian.oai.validator.model.SimpleResponse.Builder.status(response.getStatusCode().value())
                .withContentType(String.valueOf(response.getHeaders().getContentType()))
                .withBody(response.getBody())
                .build();
        var report = CONTRACT.validateResponse("/v1" + path, com.atlassian.oai.validator.model.Request.Method.valueOf(method), simple);
        assertThat(report.getMessages()).as("contract violations for %s %s", method, path).isEmpty();
        return response;
    }

    private ResponseEntity<String> pay(String token, long cents, String idempotencyKey) {
        return checked("POST", "/payments", http.exchange("/v1/payments", HttpMethod.POST,
                new HttpEntity<>(Map.of("amount", cents, "currency", "usd", "source", token), headers(idempotencyKey)), String.class));
    }

    private HttpHeaders headers(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + secretKey);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return headers;
    }
}
