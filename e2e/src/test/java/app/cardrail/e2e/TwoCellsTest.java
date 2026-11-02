package app.cardrail.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The live layout (ADR-015): the banks in cell a, the rails in cell b, each copying the other's schemas. Every
 * payment crosses both cells; when the phone's cell is lost, the laptop's takes its banks over with their data.
 * They stop containers, so they run only with {@code -De2e.twoCells=<two-cells compose file>}.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TwoCellsTest {

    static Edge edge;
    static String compose;
    static JsonNode ownCard;
    static String firstReference;

    @BeforeAll
    static void setUp() {
        String base = Edge.baseUrl();
        compose = System.getProperty("e2e.twoCells");
        assumeTrue(base != null && compose != null && !compose.isBlank() && !compose.startsWith("${"),
                "two cells need -De2e.baseUrl and -De2e.twoCells=<compose file>");
        edge = new Edge(base);
    }

    @Test
    @Order(1)
    @DisplayName("CELL-01: a payment crosses both cells, and each cell's database holds a copy of the other's records")
    void cell01() throws Exception {
        JsonNode own = edge.post("/api/cardholder/cards", Map.of("kind", "DEBIT")).body();
        ownCard = cardFor(own.get("card"));
        JsonNode payment = edge.pay("sticker", 1, ownCard);
        assertThat(payment.get("status").asText()).isEqualTo("captured");
        firstReference = payment.get("reference").asText();

        // Kestrel Bank (cell a) approved it; the laptop's copy has its record. The gateway (cell b) took it; the phone has that.
        assertThat(eventually("pg-b", "SELECT count(*) FROM issuer.authorisations WHERE rrn = '" + firstReference + "'")).isEqualTo("1");
        assertThat(eventually("pg-a", "SELECT count(*) FROM gateway.payments WHERE rrn = '" + firstReference + "'")).isEqualTo("1");
    }

    @Test
    @Order(2)
    @DisplayName("CELL-02: the phone's cell is lost; the laptop takes the banks over, with every card and payment they had")
    void cell02() throws Exception {
        compose("stop", "banks", "pg-a", "rails");
        compose("--profile", "takeover", "run", "--rm", "promote-b");
        compose("--profile", "takeover", "up", "-d", "--wait", "standby-b");

        // the visitor's card still signs in, and its first payment is in its history
        Edge.Response session = edge.post("/api/cardholder/sessions", Map.of("number", ownCard.get("number").asText(),
                "expiry", ownCard.get("expiry").asText(), "cvv2", ownCard.get("cvv2").asText()));
        assertThat(session.status()).isEqualTo(201);
        String token = session.body().get("token").asText();
        List<String> references = edge.get("/api/cardholder/transactions", token).body().findValuesAsText("reference");
        assertThat(references).contains(firstReference);

        // and it keeps paying, as does a card issued after the takeover
        assertThat(edge.pay("tee", 1, ownCard).get("status").asText()).isEqualTo("captured");
        JsonNode fresh = cardFor(edge.post("/api/cardholder/cards", Map.of("kind", "DEBIT")).body().get("card"));
        assertThat(edge.pay("sticker", 1, fresh).get("status").asText()).isEqualTo("captured");
    }

    private static JsonNode cardFor(JsonNode issued) {
        return Edge.JSON.valueToTree(Map.of("number", issued.get("pan").asText(), "expiry", issued.get("expiry").asText(),
                "cvv2", issued.get("cvv2").asText()));
    }

    /** A query's answer once replication has had a few seconds to carry it. */
    private static String eventually(String db, String sql) throws Exception {
        String answer = "";
        for (int i = 0; i < 30 && !answer.equals("1"); i++) {
            answer = compose("exec", "-T", db, "psql", "-U", "cardrail", "-d", "cardrail", "-tAc", sql).trim();
            if (!answer.equals("1")) {
                Thread.sleep(1000);
            }
        }
        return answer;
    }

    private static String compose(String... args) throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>(List.of("docker", "compose", "-f", compose));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(300, TimeUnit.SECONDS)) {
            p.destroyForcibly();
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException(String.join(" ", args) + " failed: " + out);
        }
        return out;
    }
}
