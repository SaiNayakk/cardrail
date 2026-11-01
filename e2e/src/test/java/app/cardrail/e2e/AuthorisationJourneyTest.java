package app.cardrail.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Phase 1: an authorisation flows gateway, acquirer, network, issuer and back, and every hop is in its trace. */
class AuthorisationJourneyTest {

    static Edge edge;

    @BeforeAll
    static void edge() throws InterruptedException {
        String base = Edge.baseUrl();
        assumeTrue(base != null, "no environment given (-De2e.baseUrl)");
        edge = new Edge(base);
        // the demo shop signs itself up in the background once the acquirer answers
        for (int i = 0; i < 60; i++) {
            Edge.Response config = edge.get("/api/shop/v1/config");
            if (config.status() == 200 && edge.get("/api/test-cards").status() == 200) {
                return;
            }
            Thread.sleep(1000);
        }
    }

    @Test
    @DisplayName("E2E-01: an approved payment's trace shows every party, in order, with the ISO 8583 messages between them")
    void e2e01() throws InterruptedException {
        JsonNode payment = edge.pay("tee", 1, edge.testCard("Debit card with $5,000"));

        assertThat(payment.get("status").asText()).isEqualTo("authorised");
        assertThat(payment.get("amount").asLong()).isEqualTo(1999);
        assertThat(payment.get("authCode").asText()).hasSize(6);

        List<String> hops = hops(payment.get("reference").asText(), 8);
        assertThat(hops).containsExactly(
                "merchant>railpay note",
                "railpay>railpay-acquiring note",
                "railpay-acquiring>railnet 0100",
                "railnet>kestrel 0100",
                "kestrel>railnet 0110",
                "railnet>railpay-acquiring 0110",
                "railpay-acquiring>railpay note",
                "railpay>merchant note");
    }

    @Test
    @DisplayName("E2E-02: each published test card gets its published outcome")
    void e2e02() {
        assertThat(code("Debit card with $5:", "tee")).isEqualTo("51");
        assertThat(code("Blocked", "sticker")).isEqualTo("62");
        assertThat(code("Reported lost", "sticker")).isEqualTo("41");
        assertThat(code("Credit card", "lamp")).isEqualTo("00");
    }

    @Test
    @DisplayName("E2E-03: the expired card is refused by the hosted form before it reaches anyone")
    void e2e03() {
        JsonNode expired = edge.testCard("Expired");
        String key = edge.get("/api/shop/v1/config").body().get("publishableKey").asText();
        Edge.Response token = edge.post("/hosted/tokens", Map.of("publishableKey", key, "number", expired.get("number").asText(),
                "expMonth", 1, "expYear", 25, "cvc", expired.get("cvv2").asText()));
        assertThat(token.status()).isEqualTo(422);
        assertThat(token.body().get("code").asText()).isEqualTo("expired_card");
    }

    @Test
    @DisplayName("E2E-04: sending the same checkout twice (a double click, a retry after a lost answer) charges once")
    void e2e04() {
        String token = edge.token(edge.testCard("Debit card with $5,000"));
        String order = UUID.randomUUID().toString();
        JsonNode first = edge.checkout("mug", 1, token, order);
        JsonNode second = edge.checkout("mug", 1, token, order);
        assertThat(second.get("id").asText()).isEqualTo(first.get("id").asText());
    }

    @Test
    @DisplayName("E2E-05: no full card number, expiry or security code ever appears in a trace")
    void e2e05() throws InterruptedException {
        JsonNode card = edge.testCard("Debit card with $5,000");
        JsonNode payment = edge.pay("sticker", 1, card);
        hops(payment.get("reference").asText(), 8);
        String trace = edge.raw("/api/trace/traces/" + payment.get("reference").asText());
        assertThat(trace).doesNotContain(card.get("number").asText());
        assertThat(trace).doesNotContain("\"14\":\"3012\"");
        assertThat(trace).doesNotContain("9203" + card.get("cvv2").asText());
    }

    @Test
    @DisplayName("E2E-06: the edge never forwards internal or actuator routes")
    void e2e06() {
        assertThat(edge.get("/hosted/../internal/v1/tokens").status()).isIn(400, 404);
        assertThat(edge.post("/api/railpay/../../internal/v1/merchants", Map.of()).status()).isIn(400, 404);
        assertThat(edge.get("/actuator/health").status()).isEqualTo(404);
        assertThat(edge.get("/api/network/bin-ranges").status()).isEqualTo(200);
    }

    @Test
    @DisplayName("E2E-07: the merchant API refuses a call without a secret key")
    void e2e07() {
        Edge.Response response = edge.post("/api/railpay/payments", Map.of("amount", 100, "currency", "usd", "source", "tok_x"));
        assertThat(response.status()).isEqualTo(401);
        assertThat(response.body().get("code").asText()).isEqualTo("unauthorised");
    }

    private static String code(String card, String product) {
        return edge.pay(product, 1, edge.testCard(card)).get("responseCode").asText();
    }

    /** The trace as "from>to kind-or-mti", waiting for reports to arrive (they are sent in the background). */
    private static List<String> hops(String reference, int expectedAtLeast) throws InterruptedException {
        List<String> hops = new ArrayList<>();
        for (int attempt = 0; attempt < 50; attempt++) {
            Edge.Response trace = edge.get("/api/trace/traces/" + reference);
            hops.clear();
            if (trace.status() == 200) {
                for (JsonNode e : trace.body().get("events")) {
                    hops.add(e.get("from").asText() + ">" + e.get("to").asText() + " "
                            + ("iso8583".equals(e.get("kind").asText()) ? e.get("mti").asText() : "note"));
                }
            }
            if (hops.size() >= expectedAtLeast) {
                Thread.sleep(300);
                return hops;
            }
            Thread.sleep(200);
        }
        return hops;
    }
}
