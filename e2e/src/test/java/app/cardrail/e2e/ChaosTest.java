package app.cardrail.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Chaos experiments: a party stops answering, and the system must still never leave a cardholder charged for a
 * payment the merchant was told failed, nor lose one it was told succeeded. They pause containers, so they run only
 * with {@code -De2e.chaos=<compose file>} against a pre-prod nobody else is using.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChaosTest {

    static Edge edge;
    static String compose;

    @BeforeAll
    static void setUp() {
        String base = Edge.baseUrl();
        compose = System.getProperty("e2e.chaos");
        assumeTrue(base != null && compose != null && !compose.isBlank() && !compose.startsWith("${"),
                "chaos needs -De2e.baseUrl and -De2e.chaos=<compose file>");
        edge = new Edge(base);
    }

    @AfterEach
    void heal() throws Exception {
        docker("unpause", "issuing");
        docker("unpause", "network");
    }

    @Test
    @Order(1)
    @DisplayName("CHAOS-01: Kestrel Bank stops answering; Railnet stands in within its limits, and tells it once it answers again")
    void chaos01() throws Exception {
        JsonNode card = edge.testCard("Debit card with $5,000");
        docker("pause", "issuing");

        JsonNode small = edge.pay("tee", 1, card);            // $19.99: within stand-in's $100 limit
        JsonNode large = edge.pay("map", 1, card);            // $250.00: over it

        assertThat(small.get("status").asText()).isEqualTo("captured");
        assertThat(large.get("status").asText()).isEqualTo("declined");
        assertThat(large.get("responseCode").asText()).isEqualTo("91");
        assertThat(edge.notes(small.get("reference").asText())).anyMatch(n -> n.contains("stands in and approves"));

        docker("unpause", "issuing");

        // the advice reaches Kestrel Bank, which acknowledges it
        assertThat(edge.hopsIncluding(small.get("reference").asText(), "railnet>kestrel 0120", "kestrel>railnet 0130"))
                .contains("railnet>kestrel 0120", "kestrel>railnet 0130");
        // The declined one: if Railnet had sent it to Kestrel before giving up (Kestrel may approve it late), Kestrel is
        // told to reverse it. If Railnet already knew Kestrel was down, it never sent it, and there is nothing to undo.
        List<String> largeHops = edge.hops(large.get("reference").asText(), 6);
        if (largeHops.contains("railnet>kestrel 0100")) {
            assertThat(edge.hopsIncluding(large.get("reference").asText(), "railnet>kestrel 0420", "kestrel>railnet 0430"))
                    .contains("railnet>kestrel 0420", "kestrel>railnet 0430");
        } else {
            assertThat(largeHops).doesNotContain("railnet>kestrel 0420");
        }
    }

    @Test
    @Order(2)
    @DisplayName("CHAOS-02: Railnet stops answering mid-payment; the payment is unknown, then reversed once Railnet hears the reversal")
    void chaos02() throws Exception {
        JsonNode card = edge.testCard("Debit card with $5,000");
        docker("pause", "network");

        JsonNode payment = edge.pay("lamp", 1, card);
        assertThat(payment.get("status").asText()).isEqualTo("unknown");

        docker("unpause", "network");

        String status = null;
        for (int i = 0; i < 90 && !"reversed".equals(status); i++) {
            for (JsonNode p : edge.get("/api/shop/v1/payments").body().get("data")) {
                if (p.get("id").asText().equals(payment.get("id").asText())) {
                    status = p.get("status").asText();
                }
            }
            Thread.sleep(1000);
        }
        assertThat(status).isEqualTo("reversed");
        // The original arrived after all (it was waiting in the connection) and was approved; the reversal then
        // reached the issuer, so the hold is gone.
        List<String> hops = edge.hopsIncluding(payment.get("reference").asText(), "railpay-acquiring>railnet 0420", "railnet>kestrel 0420");
        assertThat(hops).contains("railpay-acquiring>railnet 0420", "railnet>kestrel 0420");
    }

    @Test
    @Order(3)
    @DisplayName("CHAOS-03: after every experiment, payments work normally again")
    void chaos03() throws Exception {
        Thread.sleep(6_000);   // one echo round, so Railnet sees Kestrel Bank answering
        JsonNode payment = edge.pay("sticker", 1, edge.testCard("Debit card with $5,000"));
        assertThat(payment.get("status").asText()).isEqualTo("captured");
        assertThat(edge.notes(payment.get("reference").asText())).noneMatch(n -> n.contains("stands in"));
    }

    private static void docker(String action, String service) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("docker", "compose", "-f", compose, action, service).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        if (!p.waitFor(Duration.ofSeconds(60).toSeconds(), TimeUnit.SECONDS)) {
            p.destroyForcibly();
        }
    }
}
