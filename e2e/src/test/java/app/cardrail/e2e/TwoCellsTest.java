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
    static JsonNode heldCard;
    static String heldReference;

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
        admin("b", "promote");
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
        JsonNode whileHeld = edge.pay("sticker", 1, fresh);
        assertThat(whileHeld.get("status").asText()).isEqualTo("captured");
        heldReference = whileHeld.get("reference").asText();
        heldCard = fresh;
    }

    @Test
    @Order(3)
    @DisplayName("CELL-03: the phone's cell comes back and is handed its banks again, with what happened while it was away")
    void cell03() throws Exception {
        compose("start", "pg-a");
        admin("a", "rejoin");                          // the phone copies everything from the laptop, which keeps serving
        compose("--profile", "takeover", "stop", "standby-b");
        admin("b", "release");
        admin("a", "resume");                          // the last rows, then the phone owns its half again
        admin("b", "replicate");
        admin("a", "replicate");
        compose("up", "-d", "--wait", "--no-deps", "banks", "rails");

        // the card issued and used while the laptop held the banks is the phone's now, with its history
        Edge.Response session = edge.post("/api/cardholder/sessions", Map.of("number", heldCard.get("number").asText(),
                "expiry", heldCard.get("expiry").asText(), "cvv2", heldCard.get("cvv2").asText()));
        assertThat(session.status()).isEqualTo(201);
        assertThat(edge.get("/api/cardholder/transactions", session.body().get("token").asText()).body().findValuesAsText("reference"))
                .contains(heldReference);
        assertThat(eventually("pg-a", "SELECT count(*) FROM issuer.authorisations WHERE rrn = '" + heldReference + "'")).isEqualTo("1");

        // and payments cross both cells again, copied both ways
        JsonNode after = edge.pay("tee", 1, heldCard);
        assertThat(after.get("status").asText()).isEqualTo("captured");
        assertThat(eventually("pg-b", "SELECT count(*) FROM issuer.authorisations WHERE rrn = '" + after.get("reference").asText() + "'")).isEqualTo("1");
    }

    /** One of a cell's database commands, run against cell {@code cell}'s database with the other as its peer. */
    private static void admin(String cell, String command) throws Exception {
        String peer = cell.equals("a") ? "b" : "a";
        compose("--profile", "takeover", "run", "--rm", "-e", "CARDRAIL_CELL=" + cell,
                "-e", "CARDRAIL_DB_URL=jdbc:postgresql://pg-" + cell + ":5432/cardrail",
                "-e", "CARDRAIL_PEER_DB_URL=jdbc:postgresql://pg-" + peer + ":5432/cardrail", "admin", command);
    }

    /** An issued card as the hosted form takes it: the bank gives the expiry as YYMM, the form takes MM/YY. */
    private static JsonNode cardFor(JsonNode issued) {
        String expiry = issued.get("expiry").asText();
        return Edge.JSON.valueToTree(Map.of("number", issued.get("pan").asText(),
                "expiry", expiry.substring(2) + "/" + expiry.substring(0, 2), "cvv2", issued.get("cvv2").asText(),
                "id", java.util.UUID.randomUUID().toString()));
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
