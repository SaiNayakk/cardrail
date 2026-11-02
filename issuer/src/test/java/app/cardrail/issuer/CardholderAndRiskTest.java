package app.cardrail.issuer;

import app.cardrail.common.ApiException;
import app.cardrail.iso8583.IsoMessage;
import app.cardrail.iso8583.Subelements;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.config.name=issuer",
        "issuer.iso-port=0",
        "issuer.demo=true",
        "issuer.clearing-every=1h",
        "issuer.pan-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "issuer.hash-key=ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=",
        "issuer.cvk=a2VzdHJlbC10ZXN0LWN2ay0zMi1ieXRlcy1sb25nISE=",
        "cardrail.internal-key=test-internal-key-0123456789"})
class CardholderAndRiskTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&currentSchema=issuer");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    static final AtomicInteger N = new AtomicInteger();

    @Autowired Authoriser authoriser;
    @Autowired CardIssuing issuing;
    @Autowired Cardholders cardholders;
    @Autowired Acs acs;
    @Autowired Risk risk;
    @Autowired JdbcClient jdbc;

    @Test
    void theCardholdersOwnRulesComeFirst() {
        var card = issuing.open("Rules", "DEBIT", 100_000, null);
        cardholders.setControls(card.cardId(), true, 5_000L, 8_000L, List.of("7995"));

        assertThat(code(card, 6_000, "5999", null)).isEqualTo("61");          // over the limit per payment
        assertThat(code(card, 100, "7995", null)).isEqualTo("57");            // a blocked kind of merchant
        assertThat(code(card, 5_000, "5999", null)).isEqualTo("00");
        assertThat(code(card, 4_000, "5999", null)).isEqualTo("61");          // over the daily limit

        cardholders.setControls(card.cardId(), false, null, null, List.of());
        assertThat(code(card, 100, "5999", null)).isEqualTo("57");            // online payments off
    }

    @Test
    void tooManyPaymentsTooFastAreRefused() {
        var card = issuing.open("Velocity", "DEBIT", 100_000, null);
        for (int i = 0; i < Risk.VELOCITY_MAX; i++) {
            code(card, 100, "5999", null);
        }
        assertThat(code(card, 100, "5999", null)).isEqualTo("65");
    }

    @Test
    void aRiskyOnlinePaymentNeedsStepUpAndProofOfItWorksOnce() {
        var card = issuing.open("Step-up", "DEBIT", 100_000, null);
        // a risky kind of merchant (25), $500 or more (20), a large first payment (20): 65, so confirm it
        IsoMessage first = request(card, 50_000, "4829", null);
        IsoMessage answer = authoriser.authorise(first);
        assertThat(answer.get(39)).isEqualTo("1A");
        assertThat(jdbc.sql("select risk_reasons from authorisations where rrn = ?").param(first.get(37)).query(String.class).single())
                .contains("risky kind of merchant", "$500 or more", "large first payment");
        // from here the card asks every time, so a spent or forged proof is the only thing being tested
        risk.setControls(card.cardId(), new Risk.Controls(true, null, null, List.of(), true, false));

        var started = acs.start(card.pan(), card.expiry(), 50_000, "Wire shop", null);
        String otp = jdbc.sql("select otp from authentications where id = ?").param(started.id()).query(String.class).single();
        assertThatThrownBy(() -> acs.answer(started.id(), otp.equals("000000") ? "111111" : "000000")).hasMessageContaining("2 tries left");
        assertThat(acs.answer(started.id(), otp).status()).isEqualTo("Y");
        String value = acs.result(started.id()).value();

        IsoMessage confirmed = request(card, 50_000, "4829", Map.of(Subelements.ECOMMERCE_SECURITY, "212",
                Subelements.AUTHENTICATION_VALUE, value, Subelements.AUTHENTICATION_ID, started.id()));
        assertThat(authoriser.authorise(confirmed).get(39)).isEqualTo("00");

        IsoMessage reused = request(card, 50_000, "4829", Map.of(Subelements.ECOMMERCE_SECURITY, "212",
                Subelements.AUTHENTICATION_VALUE, value, Subelements.AUTHENTICATION_ID, started.id()));
        assertThat(authoriser.authorise(reused).get(39)).isEqualTo("1A");

        IsoMessage forged = request(card, 50_000, "4829", Map.of(Subelements.ECOMMERCE_SECURITY, "212",
                Subelements.AUTHENTICATION_VALUE, "A".repeat(28), Subelements.AUTHENTICATION_ID, started.id()));
        assertThat(authoriser.authorise(forged).get(39)).isEqualTo("1A");
    }

    @Test
    void aCardholderSignsInFreezesReportsLostAndActivatesTheNewCard() {
        var card = issuing.open("Lifecycle", "DEBIT", 100_000, null);
        String mmyy = card.expiry().substring(2) + "/" + card.expiry().substring(0, 2);
        assertThatThrownBy(() -> cardholders.signIn(card.pan(), mmyy, card.cvv2().equals("000") ? "001" : "000"))
                .hasMessageContaining("don't match");
        var session = cardholders.signIn(card.pan(), mmyy, card.cvv2());
        assertThat(cardholders.cardFor("Bearer " + session.token())).isEqualTo(card.cardId());
        assertThatThrownBy(() -> cardholders.cardFor("Bearer " + session.token() + "x")).isInstanceOf(ApiException.class);

        cardholders.freeze(card.cardId(), true);
        assertThat(code(card, 100, "5999", null)).isEqualTo("62");
        cardholders.freeze(card.cardId(), false);

        var replacement = cardholders.reportLost(card.cardId(), false);
        assertThat(code(card, 100, "5999", null)).isEqualTo("41");
        var fresh = replacement.card();
        assertThat(fresh.accountId()).isEqualTo(card.accountId());
        assertThat(code(fresh, 100, "5999", null)).isEqualTo("62");           // not activated yet
        cardholders.activate(fresh.cardId(), fresh.cvv2());
        assertThat(code(fresh, 100, "5999", null)).isEqualTo("00");
    }

    @Test
    void sharedTestCardsCantBeChanged() {
        var shared = jdbc.sql("select card_id from card_controls where shared limit 1").query(java.util.UUID.class).single();
        assertThatThrownBy(() -> cardholders.freeze(shared, true)).hasMessageContaining("shared test card");
        assertThatThrownBy(() -> cardholders.reportLost(shared, false)).hasMessageContaining("shared test card");
    }

    private String code(CardIssuing.IssuedCard card, long cents, String mcc, Map<String, String> field48) {
        return authoriser.authorise(request(card, cents, mcc, field48)).get(39);
    }

    private static IsoMessage request(CardIssuing.IssuedCard card, long cents, String mcc, Map<String, String> field48) {
        Map<String, String> sub = new LinkedHashMap<>();
        sub.put(Subelements.CVV2, card.cvv2());
        if (field48 != null) {
            sub.putAll(field48);
        }
        return new IsoMessage("0100").set(2, card.pan()).set(3, "000000").set(4, String.format("%012d", cents)).set(7, "1102093015")
                .set(11, "000001").set(14, card.expiry()).set(18, mcc).set(22, "810").set(32, "400101")
                .set(37, String.format("P%011d", N.incrementAndGet())).set(48, Subelements.encode(sub)).set(49, "840");
    }
}
