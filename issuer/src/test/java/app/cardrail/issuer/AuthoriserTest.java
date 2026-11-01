package app.cardrail.issuer;

import app.cardrail.common.iso.IsoLink;
import app.cardrail.iso8583.Iso8583Codec;
import app.cardrail.iso8583.IsoMessage;
import app.cardrail.iso8583.MessageSpec;
import app.cardrail.iso8583.Subelements;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.name=issuer",
        "issuer.iso-port=0",
        "issuer.demo=true",
        "issuer.pan-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "issuer.hash-key=ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=",
        "issuer.cvk=a2VzdHJlbC10ZXN0LWN2ay0zMi1ieXRlcy1sb25nISE=",
        "cardrail.internal-key=test-internal-key-0123456789"})
class AuthoriserTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&currentSchema=issuer");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static final AtomicInteger STAN = new AtomicInteger();
    private static final String APPROVE = CardIssuing.pan("286001000000001");
    private static final String LOW = CardIssuing.pan("286001000000002");

    @Autowired IsoEndpoint endpoint;
    @Autowired CardKeys keys;
    @Autowired CardIssuing issuing;

    private IsoLink link;

    @BeforeEach
    void connect() {
        link = new IsoLink("railnet", "127.0.0.1", endpoint.port(), new Iso8583Codec(MessageSpec.RAILNET));
    }

    @AfterEach
    void disconnect() {
        link.close();
    }

    @Test
    void approvesAGoodCardAndAnswersARepeatTheSameWay() throws Exception {
        IsoMessage request = request(APPROVE, "3012", cvv(APPROVE, "3012"), 1999);

        IsoMessage first = send(request);
        IsoMessage again = send(request);

        assertThat(first.mti()).isEqualTo("0110");
        assertThat(first.get(39)).isEqualTo("00");
        assertThat(first.get(38)).hasSize(6);
        assertThat(again.get(38)).isEqualTo(first.get(38));
    }

    @Test
    void holdsReduceWhatCanBeSpent() throws Exception {
        var card = issuing.open("Hold test", "DEBIT", 1_000, null);

        assertThat(send(request(card.pan(), card.expiry(), card.cvv2(), 600)).get(39)).isEqualTo("00");
        assertThat(send(request(card.pan(), card.expiry(), card.cvv2(), 600)).get(39)).isEqualTo("51");
        assertThat(send(request(card.pan(), card.expiry(), card.cvv2(), 400)).get(39)).isEqualTo("00");
    }

    @Test
    void declinesForEachPublishedReason() throws Exception {
        assertThat(code(LOW, "3012", 501)).isEqualTo("51");
        assertThat(code(CardIssuing.pan("286001000000003"), "3012", 100)).isEqualTo("62");
        assertThat(code(CardIssuing.pan("286001000000004"), "3012", 100)).isEqualTo("41");
        assertThat(code(CardIssuing.pan("286001000000005"), "2501", 100)).isEqualTo("54");
        assertThat(code(CardIssuing.pan("286009999999999"), "3012", 100)).isEqualTo("14");
        assertThat(code(APPROVE, "2912", 100)).isEqualTo("54");
    }

    @Test
    void declinesAWrongSecurityCode() throws Exception {
        String wrong = cvv(APPROVE, "3012").equals("000") ? "001" : "000";
        assertThat(send(request(APPROVE, "3012", wrong, 100)).get(39)).isEqualTo("N7");
    }

    @Test
    void creditCardsSpendTheirLimit() throws Exception {
        String credit = CardIssuing.pan("286002000000001");
        assertThat(code(credit, "3012", 150_000)).isEqualTo("00");
        assertThat(code(credit, "3012", 60_000)).isEqualTo("51");
    }

    @Test
    void aReversalReleasesTheHold() throws Exception {
        var card = issuing.open("Reversal test", "DEBIT", 1_000, null);
        IsoMessage purchase = request(card.pan(), card.expiry(), card.cvv2(), 600);
        assertThat(send(purchase).get(39)).isEqualTo("00");
        assertThat(send(request(card.pan(), card.expiry(), card.cvv2(), 600)).get(39)).isEqualTo("51");

        IsoMessage reversed = send(reversal("0400", purchase, null));

        assertThat(reversed.mti()).isEqualTo("0410");
        assertThat(reversed.get(39)).isEqualTo("00");
        assertThat(send(request(card.pan(), card.expiry(), card.cvv2(), 600)).get(39)).isEqualTo("00");
    }

    @Test
    void aPartialReversalKeepsHoldingTheNewAmount() throws Exception {
        var card = issuing.open("Partial test", "DEBIT", 1_000, null);
        IsoMessage purchase = request(card.pan(), card.expiry(), card.cvv2(), 1_000);
        send(purchase);

        send(reversal("0400", purchase, 400L));

        assertThat(send(request(card.pan(), card.expiry(), card.cvv2(), 601)).get(39)).isEqualTo("51");
        assertThat(send(request(card.pan(), card.expiry(), card.cvv2(), 600)).get(39)).isEqualTo("00");
    }

    @Test
    void anOriginalThatArrivesAfterItsReversalIsDeclined() throws Exception {
        IsoMessage late = request(APPROVE, "3012", cvv(APPROVE, "3012"), 100);

        IsoMessage ack = send(reversal("0420", late, null));
        IsoMessage original = send(late);

        assertThat(ack.mti()).isEqualTo("0430");
        assertThat(ack.get(39)).isEqualTo("00");
        assertThat(original.get(39)).isEqualTo("25");
    }

    @Test
    void anAdviceIsHeldWhateverTheBalance() throws Exception {
        var card = issuing.open("Advice test", "DEBIT", 500, null);
        IsoMessage advice = copyAs("0120", request(card.pan(), card.expiry(), null, 2_000)).set(39, "00").set(38, "SNDIN1");

        IsoMessage ack = send(advice);

        assertThat(ack.mti()).isEqualTo("0130");
        assertThat(send(request(card.pan(), card.expiry(), card.cvv2(), 1)).get(39)).isEqualTo("51");
        assertThat(send(advice).get(39)).isEqualTo("00");
    }

    @Test
    void refundsAreApprovedWithoutAHold() throws Exception {
        var card = issuing.open("Refund test", "DEBIT", 100, null);
        IsoMessage refund = request(card.pan(), card.expiry(), null, 5_000).set(3, "200000");

        IsoMessage response = send(refund);

        assertThat(response.get(39)).isEqualTo("00");
        assertThat(response.get(38)).hasSize(6);
        assertThat(send(request(card.pan(), card.expiry(), card.cvv2(), 100)).get(39)).isEqualTo("00");
    }

    @Test
    void answersNetworkManagement() throws Exception {
        IsoMessage echo = new IsoMessage("0800").set(7, "1102093015").set(11, stan()).set(37, "ECHO00000001").set(70, "301");
        IsoMessage response = send(echo);
        assertThat(response.mti()).isEqualTo("0810");
        assertThat(response.get(39)).isEqualTo("00");
    }

    private String code(String pan, String expiry, long cents) throws Exception {
        return send(request(pan, expiry, cvv(pan, expiry), cents)).get(39);
    }

    private String cvv(String pan, String expiry) {
        return keys.cvv2(pan, expiry);
    }

    private IsoMessage send(IsoMessage request) throws Exception {
        return link.send(request, Duration.ofSeconds(5)).get();
    }

    private static IsoMessage request(String pan, String expiry, String cvv2, long cents) {
        String stan = stan();
        IsoMessage m = new IsoMessage("0100")
                .set(2, pan).set(3, "000000").set(4, String.format("%012d", cents)).set(7, "1102093015")
                .set(11, stan).set(14, expiry).set(18, "5999").set(22, "810").set(32, "400101")
                .set(37, "T" + String.format("%011d", Integer.parseInt(stan)))
                .set(41, "WEB00001").set(42, "MERCHANT0000001").set(49, "840");
        return cvv2 == null ? m : m.set(48, Subelements.encode(Map.of(Subelements.CVV2, cvv2)));
    }

    /** A reversal of {@code original}: all of it, or down to {@code remaining} cents. Its own trace number, the same RRN. */
    private static IsoMessage reversal(String mti, IsoMessage original, Long remaining) {
        IsoMessage r = new IsoMessage(mti).set(2, original.get(2)).set(3, original.get(3)).set(4, original.get(4))
                .set(7, "1102093020").set(11, stan()).set(32, original.get(32)).set(37, original.get(37)).set(49, "840")
                .set(90, original.mti() + original.get(11) + original.get(7) + String.format("%011d", 400101) + "0".repeat(11));
        return remaining == null ? r : r.set(95, String.format("%012d", remaining) + " ".repeat(30));
    }

    private static IsoMessage copyAs(String mti, IsoMessage m) {
        IsoMessage copy = new IsoMessage(mti);
        m.fields().forEach(copy::set);
        return copy;
    }

    private static String stan() {
        return String.format("%06d", STAN.incrementAndGet());
    }
}
