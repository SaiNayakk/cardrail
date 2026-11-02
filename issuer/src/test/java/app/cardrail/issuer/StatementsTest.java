package app.cardrail.issuer;

import app.cardrail.common.clearing.ClearingFile.Record;
import app.cardrail.common.clearing.ClearingFile.Type;
import app.cardrail.iso8583.IsoMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.config.name=issuer",
        "issuer.iso-port=0",
        "issuer.clearing-every=1h",
        "issuer.statement-check-every=1h",
        "issuer.payment-due-after=2s",
        "issuer.pan-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "issuer.hash-key=ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=",
        "issuer.cvk=a2VzdHJlbC10ZXN0LWN2ay0zMi1ieXRlcy1sb25nISE=",
        "cardrail.internal-key=test-internal-key-0123456789"})
class StatementsTest {

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
    @Autowired IssuerBooks books;
    @Autowired Statements statements;
    @Autowired IssuerStore store;
    @Autowired TransactionTemplate tx;

    @Test
    void theFirstStatementIsInterestFreeThenAShortPaymentCostsInterestAndALateFee() throws InterruptedException {
        var card = issuing.open("Statements", "CREDIT", 0, 500_000L);
        spend(card, 100_000);

        var first = statements.closeNow(card.accountId());
        assertThat(first.purchasesCents()).isEqualTo(100_000);
        assertThat(first.interestCents()).isZero();                       // nothing was owed before: grace
        assertThat(first.closingCents()).isEqualTo(100_000);
        assertThat(first.minimumDueCents()).isEqualTo(2_500);              // 1% is $10, so the $25 floor

        statements.pay(card.accountId(), 1_000);                           // $10: less than the minimum
        Thread.sleep(2_100);                                               // past the (2 s) due date
        var second = statements.closeNow(card.accountId());

        // not paid in full: interest on about $990 for 30 days at 24.99% is about $20.33; and late
        assertThat((double) second.interestCents()).isCloseTo(2_033, within(30.0));
        assertThat(second.feesCents()).isEqualTo(Statements.LATE_FEE_CENTS);
        assertThat(second.paymentsCents()).isEqualTo(1_000);
        assertThat(second.closingCents()).isEqualTo(99_000 + second.interestCents() + Statements.LATE_FEE_CENTS);
        assertThat(store.account(card.accountId()).orElseThrow().balanceCents()).isEqualTo(second.closingCents());
        assertThat(books.ledger().total()).isZero();
    }

    @Test
    void payingInFullKeepsItInterestFree() throws InterruptedException {
        var card = issuing.open("Pays in full", "CREDIT", 0, 500_000L);
        spend(card, 50_000);
        var first = statements.closeNow(card.accountId());
        statements.pay(card.accountId(), first.closingCents());
        spend(card, 20_000);

        var second = statements.closeNow(card.accountId());

        assertThat(second.interestCents()).isZero();
        assertThat(second.feesCents()).isZero();
        assertThat(second.closingCents()).isEqualTo(20_000);
    }

    private void spend(CardIssuing.IssuedCard card, long cents) {
        String rrn = String.format("S%011d", N.incrementAndGet());
        IsoMessage response = authoriser.authorise(new IsoMessage("0100").set(2, card.pan()).set(3, "000000")
                .set(4, String.format("%012d", cents)).set(7, "1102093015").set(11, "000001").set(14, card.expiry())
                .set(18, "5999").set(32, "400101").set(37, rrn).set(49, "840"));
        assertThat(response.get(39)).isEqualTo("00");
        tx.executeWithoutResult(s -> books.post(new Record(Type.SALE, "400101", "286001", rrn, response.get(38), cents, "840",
                LocalDate.now(), "5999", "RPA000000000001", "W0000001", "286002", card.pan().substring(12), 0, null, null, null)));
    }
}
