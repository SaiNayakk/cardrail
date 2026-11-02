package app.cardrail.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phase 3: what happens after the authorisation. Pre-prod runs the clearing day fast (files every 5 s, settlement
 * every 15 s), so each test waits up to a minute or two for the next round.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ClearingJourneyTest {

    static Edge edge;

    @BeforeAll
    static void edge() {
        String base = Edge.baseUrl();
        assumeTrue(base != null, "no environment given (-De2e.baseUrl)");
        edge = new Edge(base);
    }

    @Test
    @Order(1)
    @DisplayName("CLEAR-01: a captured payment is presented, cleared by Railnet with interchange, and posted to the cardholder")
    void clear01() throws InterruptedException {
        JsonNode payment = edge.pay("tee", 1, edge.testCard("Debit card with $5,000"));
        String rrn = payment.get("reference").asText();

        List<String> notes = waitFor(() -> edge.notes(rrn), n -> n.stream().anyMatch(s -> s.startsWith("Posted: purchase")), 90);

        assertThat(notes).anyMatch(s -> s.startsWith("Presented for clearing: sale of $19.99, merchant fee $0.50"));
        assertThat(notes).anyMatch(s -> s.startsWith("Cleared: sale of $19.99, interchange $0.22"));
        assertThat(notes).anyMatch(s -> s.startsWith("Posted: purchase of $19.99"));
    }

    @Test
    @Order(2)
    @DisplayName("SETTLE-01: once settled, the shop is paid what it sold, less its fee")
    void settle01() throws InterruptedException {
        Instant before = Instant.now().minusSeconds(1);
        edge.pay("lamp", 1, edge.testCard("Debit card with $5,000"));

        // payouts carry everything settled since the last one, so this sale may arrive in one or spread over two
        List<JsonNode> since = waitFor(() -> {
            List<JsonNode> out = new java.util.ArrayList<>();
            for (JsonNode p : edge.get("/api/shop/v1/payouts").body().path("data")) {
                if (Instant.parse(p.get("createdAt").asText()).isAfter(before)) {
                    out.add(p);
                }
            }
            return out;
        }, ps -> ps.stream().mapToLong(p -> p.get("sales").asLong()).sum() >= 8_900, 120);

        assertThat(since.stream().mapToLong(p -> p.get("sales").asLong()).sum()).isGreaterThanOrEqualTo(8_900);
        for (JsonNode payout : since) {
            assertThat(payout.get("amount").asLong())
                    .isEqualTo(payout.get("sales").asLong() - payout.get("refunds").asLong() - payout.get("fees").asLong()
                            - payout.get("disputes").asLong());
        }
    }

    @Test
    @Order(3)
    @DisplayName("DISPUTE-01: a cardholder disputes a purchase; the shop answers with evidence; the money goes back to the shop")
    void dispute01() throws InterruptedException {
        JsonNode payment = edge.pay("mug", 1, edge.testCard("Debit card with $5,000"));
        String rrn = payment.get("reference").asText();
        waitFor(() -> edge.notes(rrn), n -> n.stream().anyMatch(s -> s.startsWith("Posted: purchase")), 90);

        Edge.Response disputed = edge.post("/api/issuer/disputes", Map.of("reference", rrn, "reason", "1310"));
        assertThat(disputed.status()).isEqualTo(201);

        JsonNode dispute = waitFor(() -> {
            for (JsonNode d : edge.get("/api/shop/v1/disputes").body().path("data")) {
                if (d.get("payment").asText().equals(payment.get("id").asText())) {
                    return d;
                }
            }
            return null;
        }, d -> true, 90);
        assertThat(dispute.get("status").asText()).isEqualTo("needs_response");
        assertThat(dispute.get("reason").asText()).isEqualTo("not received");

        Edge.Response answered = edge.post("/api/shop/v1/disputes/" + dispute.get("id").asText() + "/evidence",
                Map.of("evidence", "Delivered " + UUID.randomUUID() + ", signed for by the cardholder"));
        assertThat(answered.status()).isEqualTo(200);

        List<String> notes = waitFor(() -> edge.notes(rrn), n -> n.stream().anyMatch(s -> s.startsWith("Posted: the merchant's answer")), 90);
        assertThat(notes).anyMatch(s -> s.startsWith("Chargeback of $4.50 (reason 1310)"));
        String status = waitFor(() -> {
            for (JsonNode d : edge.get("/api/shop/v1/disputes").body().path("data")) {
                if (d.get("id").asText().equals(dispute.get("id").asText())) {
                    return d.get("status").asText();
                }
            }
            return "";
        }, "won"::equals, 30);
        assertThat(status).isEqualTo("won");
    }

    @Test
    @Order(4)
    @DisplayName("RECON-01: every party's books add up to zero, all the time")
    void recon01() {
        for (String party : List.of("kestrel", "railnet", "railpay-acquiring")) {
            JsonNode books = edge.get("/api/books/" + party).body();
            assertThat(books.get("total").asLong()).as(party + "'s books").isZero();
        }
    }

    @Test
    @Order(5)
    @DisplayName("RECON-02: once the day is quiet, everything cleared has been settled and booked by both sides: nothing is due anywhere")
    void recon02() throws InterruptedException {
        Map<String, Long> due = waitFor(this::dues, d -> d.values().stream().allMatch(v -> v == 0), 120);
        assertThat(due).allSatisfy((account, cents) -> assertThat(cents).as(account).isZero());
    }

    private Map<String, Long> dues() {
        JsonNode railnet = edge.get("/api/books/railnet").body().get("accounts");
        return Map.of(
                "railnet member:400101", railnet.path("member:400101").asLong(0),
                "railnet member:286001", railnet.path("member:286001").asLong(0),
                "acquirer due:railnet", edge.get("/api/books/railpay-acquiring").body().get("accounts").path("due:railnet").asLong(0),
                "kestrel due:railnet", edge.get("/api/books/kestrel").body().get("accounts").path("due:railnet").asLong(0));
    }

    /** Reads until done, for up to {@code seconds}. A read that fails (a slow moment) counts as "not yet". */
    private static <T> T waitFor(Supplier<T> read, java.util.function.Predicate<T> done, int seconds) throws InterruptedException {
        long until = System.nanoTime() + seconds * 1_000_000_000L;
        T value = null;
        while (true) {
            try {
                value = read.get();
                if (value != null && done.test(value)) {
                    return value;
                }
            } catch (IllegalStateException slow) {
                // try again
            }
            if (System.nanoTime() > until) {
                return value;
            }
            Thread.sleep(1000);
        }
    }
}
