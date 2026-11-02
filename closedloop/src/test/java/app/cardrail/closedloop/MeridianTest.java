package app.cardrail.closedloop;

import app.cardrail.common.clearing.ClearingFile;
import app.cardrail.common.clearing.ClearingFile.Record;
import app.cardrail.common.clearing.ClearingFile.Type;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.config.name=closedloop",
        "meridian.iso-port=0",
        "meridian.demo=true",
        "meridian.key=cHJlcHJvZC1vbmx5LW1lcmlkaWFuLWtleS0wMDAwMDY=",
        "meridian.payout-every=1h",
        "meridian.gateway-url=http://127.0.0.1:1",
        "cardrail.internal-key=test-internal-key-0123456789"})
class MeridianTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&currentSchema=meridian");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    static final AtomicInteger N = new AtomicInteger();

    @Autowired MeridianEndpoint endpoint;
    @Autowired Meridian meridian;
    @Autowired MeridianKeys keys;
    private IsoLink acquirer;

    @BeforeEach
    void connect() {
        acquirer = new IsoLink("acquirer", "127.0.0.1", endpoint.port(), new Iso8583Codec(MessageSpec.RAILNET));
    }

    @AfterEach
    void close() {
        acquirer.close();
    }

    @Test
    void decidesAsTheIssuerSubmitsAsTheAcquirerAndPaysTheMerchantLessItsDiscount() throws Exception {
        var card = meridian.open("Member", 100_000, "390000000000" + String.format("%02d", 10 + N.incrementAndGet()), "3012", null);
        String rrn = rrn();

        IsoMessage answer = send(request(card, rrn, 20_000, card.cid()));
        assertThat(answer.get(39)).isEqualTo("00");
        assertThat(card.pan()).hasSize(15);
        assertThat(card.cid()).hasSize(4);
        assertThat(send(request(card, rrn(), 90_000, card.cid())).get(39)).isEqualTo("51");   // $1,000 limit, $200 held
        assertThat(send(request(card, rrn(), 100, "0000".equals(card.cid()) ? "0001" : "0000")).get(39)).isEqualTo("N7");

        var submitted = meridian.submit(file(new Record(Type.SALE, "400101", "390001", rrn, answer.get(38), 20_000, "840", LocalDate.now(),
                "5999", "RPA000000000009", "W0000001", "390000", card.pan().substring(11), 0, null, null, null)));
        assertThat(submitted.accepted()).isEqualTo(1);

        // the cardmember owes $200; the merchant is owed $194 (3% discount, all Meridian's)
        assertThat(meridian.ledger().balance("cardmember:" + card.accountId())).isEqualTo(20_000);
        assertThat(meridian.ledger().balance("merchant:RPA000000000009")).isEqualTo(-19_400);
        var payout = meridian.payOut().stream().filter(p -> p.mid().equals("RPA000000000009")).findFirst().orElseThrow();
        assertThat(payout.amountCents()).isEqualTo(19_400);
        assertThat(payout.discountCents()).isEqualTo(600);
        assertThat(meridian.ledger().total()).isZero();
    }

    @Test
    void publishesTestCardsWithFourDigitCodesThatWork() throws Exception {
        var published = endpoint.testCards();
        assertThat(published).isNotEmpty().allSatisfy(c -> assertThat(c.cvv2()).hasSize(4));
        var first = published.getFirst();
        var card = meridian.card(first.number()).orElseThrow();
        IsoMessage m = new IsoMessage("0100").set(2, first.number()).set(3, "000000").set(4, "000000000500").set(7, "1102093015")
                .set(11, "000001").set(14, card.expiry()).set(32, "400101").set(37, rrn()).set(42, "RPA000000000009")
                .set(48, Subelements.encode(Map.of(Subelements.CVV2, first.cvv2()))).set(49, "840");
        assertThat(send(m).get(39)).isEqualTo("00");
    }

    private IsoMessage send(IsoMessage m) throws Exception {
        return acquirer.send(m, Duration.ofSeconds(5)).get();
    }

    private static IsoMessage request(Meridian.Issued card, String rrn, long cents, String cid) {
        return new IsoMessage("0100").set(2, card.pan()).set(3, "000000").set(4, String.format("%012d", cents)).set(7, "1102093015")
                .set(11, "000001").set(14, card.expiry()).set(32, "400101").set(37, rrn).set(42, "RPA000000000009")
                .set(43, "Test merchant            Austin       US").set(48, Subelements.encode(Map.of(Subelements.CVV2, cid))).set(49, "840");
    }

    private static String rrn() {
        return String.format("M%011d", N.incrementAndGet());
    }

    private static String file(Record... records) {
        return ClearingFile.write(new ClearingFile.Header("F" + N.incrementAndGet(), "400101", "390001", LocalDate.now(), LocalDateTime.now()),
                List.of(records));
    }
}
