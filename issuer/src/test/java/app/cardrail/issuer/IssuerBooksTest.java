package app.cardrail.issuer;

import app.cardrail.common.clearing.ClearingFile.Record;
import app.cardrail.common.clearing.ClearingFile.Type;
import app.cardrail.iso8583.IsoMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
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
class IssuerBooksTest {

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
    @Autowired IssuerStore store;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;

    @Test
    void clearingPostsTheSaleReleasesTheHoldAndKeepsTheBooksExact() {
        var card = issuing.open("Books test", "DEBIT", 10_000, null);
        String rrn = approve(card, 4_000);
        assertThat(available(card.accountId())).isEqualTo(6_000);

        // captured for less than was approved: the rest of the hold is released too
        post(record(Type.SALE, rrn, 3_000, 26));

        assertThat(balance(card.accountId())).isEqualTo(7_000);
        assertThat(available(card.accountId())).isEqualTo(7_000);
        assertThat(books.ledger().balance("cardholder:" + card.accountId())).isEqualTo(-7_000);
        assertThat(books.ledger().balance("income:interchange")).isNegative();
        assertThat(books.ledger().total()).isZero();

        post(record(Type.SALE, rrn, 3_000, 26));   // a repeat changes nothing
        assertThat(balance(card.accountId())).isEqualTo(7_000);
    }

    @Test
    void aRefundGivesMoneyBackAndSettlementLeavesNothingDue() {
        var card = issuing.open("Refund books", "DEBIT", 10_000, null);
        String sale = approve(card, 2_000);
        post(record(Type.SALE, sale, 2_000, 22));
        String refund = refundApproved(card, 500);
        post(record(Type.CREDIT, refund, 500, 0));

        assertThat(balance(card.accountId())).isEqualTo(8_500);
        long due = books.ledger().balance(IssuerBooks.DUE_RAILNET);
        // what this pays Railnet: its sale less refund less interchange, plus its fee of 3 cents
        tx.executeWithoutResult(s -> books.book(new IssuerBooks.Report("stl_test_" + N.incrementAndGet(), LocalDate.now(), "286001",
                -due + 3, List.of(new IssuerBooks.Item("SALE", "400101", "286001", sale, 2_000, 22, 3)))));
        assertThat(books.ledger().balance(IssuerBooks.DUE_RAILNET)).isZero();
        assertThat(books.ledger().total()).isZero();
    }

    @Test
    void aDisputeGivesTheMoneyBackAndARepresentmentTakesItAgain() {
        var card = issuing.open("Dispute books", "CREDIT", 0, 50_000L);
        String rrn = approve(card, 1_500);
        assertThatThrownBy(() -> books.dispute(rrn, "1310")).hasMessageContaining("only a purchase that has been cleared");
        post(record(Type.SALE, rrn, 1_500, 37));
        assertThat(balance(card.accountId())).isEqualTo(1_500);   // owed on the credit card

        var dispute = books.dispute(rrn, "1310");
        assertThat(dispute.status()).isEqualTo("OPEN");
        assertThat(balance(card.accountId())).isZero();
        assertThatThrownBy(() -> books.dispute(rrn, "1310")).hasMessageContaining("disputed already");

        post(record(Type.REPRESENTMENT, rrn, 1_500, 0));
        assertThat(balance(card.accountId())).isEqualTo(1_500);
        assertThat(jdbc.sql("select status from disputes where id = ?").param(dispute.id()).query(String.class).single())
                .isEqualTo("REPRESENTED");
        assertThat(books.ledger().total()).isZero();
    }

    @Test
    void anUnmatchedRecordIsHeldInSuspense() {
        post(record(Type.SALE, "NOSUCH" + String.format("%06d", N.incrementAndGet()), 999, 21));
        assertThat(books.ledger().balance("suspense:unmatched")).isPositive();
        assertThat(books.ledger().total()).isZero();
    }

    private String approve(CardIssuing.IssuedCard card, long cents) {
        String rrn = String.format("B%011d", N.incrementAndGet());
        IsoMessage response = authoriser.authorise(new IsoMessage("0100").set(2, card.pan()).set(3, "000000")
                .set(4, String.format("%012d", cents)).set(7, "1102093015").set(11, "000001").set(14, card.expiry())
                .set(18, "5999").set(32, "400101").set(37, rrn).set(49, "840"));
        assertThat(response.get(39)).isEqualTo("00");
        return rrn;
    }

    private String refundApproved(CardIssuing.IssuedCard card, long cents) {
        String rrn = String.format("R%011d", N.incrementAndGet());
        authoriser.authorise(new IsoMessage("0100").set(2, card.pan()).set(3, "200000").set(4, String.format("%012d", cents))
                .set(7, "1102093015").set(11, "000002").set(14, card.expiry()).set(32, "400101").set(37, rrn).set(49, "840"));
        return rrn;
    }

    private void post(Record r) {
        tx.executeWithoutResult(s -> books.post(r));
    }

    private static Record record(Type type, String rrn, long cents, long interchange) {
        return new Record(type, "400101", "286001", rrn, "ABC123", cents, "840", LocalDate.now(), "5999", "RPA000000000001",
                "W0000001", "286001", "0012", interchange, type == Type.REPRESENTMENT ? "1310" : null, null, null);
    }

    private long balance(UUID account) {
        return store.account(account).orElseThrow().balanceCents();
    }

    private long available(UUID account) {
        var a = store.account(account).orElseThrow();
        return a.availableCents(store.heldCents(account));
    }
}
