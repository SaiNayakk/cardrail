package app.cardrail.issuer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The published test cards, each showing one outcome. They exist only in the demo ({@code CARDRAIL_DEMO=true}),
 * and are made once: a restart finds them already there.
 */
@Component
class TestCards implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TestCards.class);

    record TestCard(String first15, String kind, long balanceCents, Long limitCents, String expiry, String status, String label) {}

    static final List<TestCard> CARDS = List.of(
            new TestCard("286001000000001", "DEBIT", 500_000, null, "3012", "ACTIVE", "Debit card with $5,000: approved"),
            new TestCard("286001000000002", "DEBIT", 500, null, "3012", "ACTIVE", "Debit card with $5: declined over $5 (insufficient funds)"),
            new TestCard("286001000000003", "DEBIT", 100_000, null, "3012", "BLOCKED", "Blocked by the cardholder: declined (restricted card)"),
            new TestCard("286001000000004", "DEBIT", 100_000, null, "3012", "LOST", "Reported lost: declined (lost card)"),
            new TestCard("286001000000005", "DEBIT", 100_000, null, "2501", "ACTIVE", "Expired in January 2025: declined (expired card)"),
            new TestCard("286002000000001", "CREDIT", 0, 200_000L, "3012", "ACTIVE", "Credit card with a $2,000 limit: approved"));

    private final IssuerProperties props;
    private final IssuerStore store;
    private final CardIssuing issuing;
    private final CardKeys keys;

    TestCards(IssuerProperties props, IssuerStore store, CardIssuing issuing, CardKeys keys) {
        this.props = props;
        this.store = store;
        this.issuing = issuing;
        this.keys = keys;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.demo()) {
            return;
        }
        int made = 0;
        for (TestCard card : CARDS) {
            String pan = CardIssuing.pan(card.first15());
            if (store.cardByPanHash(keys.panHash(pan)).isEmpty()) {
                issuing.open("Test cardholder", card.kind(), card.balanceCents(), card.limitCents(), pan, card.expiry(),
                        card.status(), card.label());
                made++;
            }
        }
        if (made > 0) {
            log.info("Made {} test cards", made);
        }
    }
}
