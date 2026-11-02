package app.cardrail.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Phase 4: the cardholder's side. Step-up through Railnet's directory and Kestrel Bank's ACS, and a card's life. */
class CardholderJourneyTest {

    static Edge edge;

    @BeforeAll
    static void edge() {
        String base = Edge.baseUrl();
        assumeTrue(base != null, "no environment given (-De2e.baseUrl)");
        edge = new Edge(base);
    }

    @Test
    @DisplayName("STEPUP-01: the card's bank asks the cardholder to confirm; with the code from their app the payment goes through")
    void stepUp01() throws InterruptedException {
        JsonNode card = edge.testCard("Debit card that asks you to confirm");
        JsonNode waiting = edge.pay("tee", 1, card);
        assertThat(waiting.get("status").asText()).isEqualTo("requires_action");
        String challenge = waiting.get("nextAction").get("url").asText();
        assertThat(challenge).startsWith("/acs/challenge/");
        assertThat(edge.raw(challenge)).contains("Confirm this payment");

        // the cardholder opens their bank's app, where the code is shown
        String token = signIn(card);
        JsonNode pending = edge.get("/api/cardholder/authentications", token).body();
        String id = challenge.substring(challenge.lastIndexOf('/') + 1);
        String code = null;
        for (JsonNode p : pending) {
            if (p.get("id").asText().equals(id)) {
                code = p.get("otp").asText();
            }
        }
        assertThat(code).as("the code in the app").isNotNull();

        Edge.Response wrong = edge.post(challenge, Map.of("code", code.equals("000000") ? "111111" : "000000"));
        assertThat(wrong.status()).isEqualTo(422);
        assertThat(edge.post(challenge, Map.of("code", code)).body().get("status").asText()).isEqualTo("Y");

        JsonNode done = edge.post("/api/shop/v1/payments/" + waiting.get("id").asText() + "/confirm", Map.of()).body();
        assertThat(done.get("status").asText()).isEqualTo("captured");
        List<String> hops = edge.hopsIncluding(done.get("reference").asText(), "railpay-acquiring>railnet 0100", "kestrel>railnet 0110");
        assertThat(hops).contains("railpay-acquiring>railnet 0100", "kestrel>railnet 0110");
    }

    @Test
    @DisplayName("CARD-01: a visitor's own card: frozen it's declined, a blocked merchant category is declined, reported lost a new card must be activated")
    void card01() {
        JsonNode own = edge.post("/api/cardholder/cards", Map.of("kind", "DEBIT")).body();
        String token = own.get("session").get("token").asText();
        JsonNode card = cardFor(own.get("card"));

        assertThat(edge.post("/api/cardholder/card/freeze", Map.of(), token).status()).isEqualTo(200);
        assertThat(edge.pay("sticker", 1, card).get("responseCode").asText()).isEqualTo("62");
        edge.post("/api/cardholder/card/unfreeze", Map.of(), token);

        assertThat(edge.put("/api/cardholder/controls", Map.of("onlineEnabled", true, "singleLimitCents", 1000), token).status()).isEqualTo(200);
        assertThat(edge.pay("tee", 1, card).get("responseCode").asText()).isEqualTo("61");
        assertThat(edge.pay("sticker", 1, card).get("status").asText()).isEqualTo("captured");

        JsonNode replaced = edge.post("/api/cardholder/card/report-lost", Map.of("stolen", false), token).body();
        assertThat(edge.pay("sticker", 1, card).get("responseCode").asText()).isEqualTo("41");
        JsonNode fresh = cardFor(replaced.get("card"));
        String freshToken = replaced.get("session").get("token").asText();
        assertThat(edge.get("/api/cardholder/card", freshToken).body().get("status").asText()).isEqualTo("INACTIVE");
        edge.post("/api/cardholder/card/activate", Map.of("cvv2", fresh.get("cvv2").asText()), freshToken);
        assertThat(edge.pay("sticker", 1, fresh).get("status").asText()).isEqualTo("captured");

        // the same account: both cards' payments are in its history
        assertThat(edge.get("/api/cardholder/transactions", freshToken).body().size()).isGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("CARD-02: the shared test cards can be looked at, never changed")
    void card02() {
        String token = signIn(edge.testCard("Debit card with $5,000"));
        assertThat(edge.get("/api/cardholder/card", token).body().get("shared").asBoolean()).isTrue();
        Edge.Response freeze = edge.post("/api/cardholder/card/freeze", Map.of(), token);
        assertThat(freeze.status()).isEqualTo(403);
        assertThat(freeze.body().get("code").asText()).isEqualTo("shared_card");
        assertThat(edge.post("/api/cardholder/sessions", Map.of("number", "2860010000000012", "expiry", "12/30", "cvv2", "000"))
                .status()).isIn(201, 401);
    }

    private static String signIn(JsonNode card) {
        Edge.Response session = edge.post("/api/cardholder/sessions",
                Map.of("number", card.get("number").asText(), "expiry", card.get("expiry").asText(), "cvv2", card.get("cvv2").asText()));
        assertThat(session.status()).isEqualTo(201);
        return session.body().get("token").asText();
    }

    /** An issued card as the hosted form wants it. */
    private static JsonNode cardFor(JsonNode issued) {
        String expiry = issued.get("expiry").asText();
        return Edge.JSON.valueToTree(Map.of("number", issued.get("pan").asText(),
                "expiry", expiry.substring(2) + "/" + expiry.substring(0, 2), "cvv2", issued.get("cvv2").asText(),
                "id", UUID.randomUUID().toString()));
    }
}
