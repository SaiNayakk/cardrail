package app.cardrail.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Phase 5: Meridian, the closed loop. No network in the middle; one company decides, collects and pays. */
class ClosedLoopJourneyTest {

    static Edge edge;

    @BeforeAll
    static void edge() {
        String base = Edge.baseUrl();
        assumeTrue(base != null, "no environment given (-De2e.baseUrl)");
        edge = new Edge(base);
    }

    @Test
    @DisplayName("CLOSED-01: a Meridian card goes straight from the acquirer to Meridian and back, with no network hop, and Meridian pays the shop less its 3% discount")
    void closed01() throws InterruptedException {
        JsonNode card = edge.get("/api/meridian/test-cards").body().get(0);
        assertThat(card.get("number").asText()).hasSize(15);
        assertThat(card.get("cvv2").asText()).hasSize(4);

        JsonNode payment = edge.pay("lamp", 1, card);
        assertThat(payment.get("status").asText()).isEqualTo("captured");
        assertThat(payment.get("card").get("brand").asText()).isEqualTo("meridian");

        String rrn = payment.get("reference").asText();
        List<String> hops = edge.hopsIncluding(rrn, "railpay-acquiring>meridian 0100", "meridian>railpay-acquiring 0110");
        assertThat(hops).contains("railpay-acquiring>meridian 0100", "meridian>railpay-acquiring 0110");
        assertThat(hops).noneMatch(h -> h.contains("railnet"));

        // submitted to Meridian, which keeps 3% and pays the shop itself
        List<String> notes = null;
        for (int i = 0; i < 90; i++) {
            notes = edge.notes(rrn);
            if (notes.stream().anyMatch(n -> n.startsWith("Submitted: sale"))) {
                break;
            }
            Thread.sleep(1000);
        }
        assertThat(notes).anyMatch(n -> n.startsWith("Submitted: sale of $89.00; Meridian keeps its $2.67 discount"));
        assertThat(edge.get("/api/books/meridian").body().get("total").asLong()).isZero();
    }

    @Test
    @DisplayName("CLOSED-02: Meridian declines over a cardmember's limit, as the issuer it is")
    void closed02() {
        JsonNode low = null;
        for (JsonNode c : edge.get("/api/meridian/test-cards").body()) {
            if (c.get("outcome").asText().contains("$10 left")) {
                low = c;
            }
        }
        assertThat(edge.pay("map", 1, low).get("responseCode").asText()).isEqualTo("51");
    }
}
