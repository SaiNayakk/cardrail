package app.cardrail.network;

import app.cardrail.common.clearing.ClearingFile;
import app.cardrail.common.clearing.ClearingFile.Record;
import app.cardrail.common.clearing.ClearingFile.Type;
import app.cardrail.common.ApiException;
import app.cardrail.common.ledger.Ledger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.config.name=network",
        "network.iso-port=0",
        "network.key=cHJlcHJvZC1vbmx5LW5ldHdvcmsta2V5LTAwMDAwMDU=",
        "network.echo-every=1h",
        "network.advice-every=1h",
        "network.settle-every=1h",
        "cardrail.internal-key=test-internal-key-0123456789"})
class ClearingTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&currentSchema=network");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    static final AtomicInteger N = new AtomicInteger();

    @Autowired Clearing clearing;
    @Autowired Settlement settlement;
    @Autowired NetworkStore store;
    @Autowired NetworkKeys keys;
    @Autowired JdbcClient jdbc;

    @Test
    void routesASaleToTheIssuerWithInterchangeAndSettlesNet() {
        settlement.settle();   // whatever other tests cleared, so this cut-off holds only these two
        String debit = authorised("2860010000000012", 10_000);
        String credit = authorised("2860020000000011", 10_000);

        var result = clearing.receive(file("400101", sale(debit, 10_000, "286001"), sale(credit, 10_000, "286002")));

        assertThat(result.accepted()).isEqualTo(2);
        var toIssuer = ClearingFile.parse(clearing.outgoing("286001", 0).getLast().content());
        assertThat(toIssuer.records()).extracting(Record::interchangeCents)
                .containsExactly(5L + 21, 180L + 10);   // debit 0.05% + 21c, credit 1.80% + 10c

        String settled = settlement.settle().orElseThrow();
        var acquirer = settlement.reports("400101", null).stream().filter(r -> r.settlementId().equals(settled)).findFirst().orElseThrow();
        var issuer = settlement.reports("286001", null).stream().filter(r -> r.settlementId().equals(settled)).findFirst().orElseThrow();

        // the acquirer receives $200 less interchange (26 + 190) and its fee (13 + 13); the issuer pays $200 less
        // interchange, plus its fee
        assertThat(acquirer.netCents()).isEqualTo(-(20_000 - 216 - 26));
        assertThat(issuer.netCents()).isEqualTo(20_000 - 216 + 26);
        assertThat(acquirer.items()).hasSize(2);
        Ledger ledger = new Ledger(jdbc);
        assertThat(ledger.total()).isZero();
        assertThat(ledger.balance("member:400101")).isZero();
        assertThat(ledger.balance("member:286001")).isZero();
    }

    @Test
    void refusesWhatWasntAuthorisedOrIsPresentedTwice() {
        String rrn = authorised("2860010000000012", 1_000);

        var first = clearing.receive(file("400101", sale(rrn, 1_000, "286001"), sale("NOSUCH000000", 500, "286001"),
                sale(authorised("2860010000000012", 100), 200, "286001")));
        assertThat(first.accepted()).isEqualTo(1);
        assertThat(first.rejected()).extracting(Clearing.Rejected::reason)
                .containsExactly("no approved authorisation with this reference", "more than was authorised");

        var again = clearing.receive(file("400101", sale(rrn, 1_000, "286001")));
        assertThat(again.accepted()).isZero();
        assertThat(again.rejected()).extracting(Clearing.Rejected::reason).containsExactly("presented before");
    }

    @Test
    void aChargebackGoesBackToTheAcquirerAndARepresentmentToTheIssuer() {
        String rrn = authorised("2860010000000012", 3_000);
        clearing.receive(file("400101", sale(rrn, 3_000, "286001")));

        assertThat(clearing.receive(file("400101", chargeback(rrn, 3_000))).rejected())
                .extracting(Clearing.Rejected::reason).containsExactly("only the card's issuer can charge it back");
        assertThat(clearing.receive(file("286001", chargeback(rrn, 3_000))).accepted()).isEqualTo(1);
        var toAcquirer = ClearingFile.parse(clearing.outgoing("400101", 0).getLast().content());
        assertThat(toAcquirer.records().getFirst().type()).isEqualTo(Type.CHARGEBACK);
        assertThat(toAcquirer.records().getFirst().reasonCode()).isEqualTo("1310");

        assertThat(clearing.receive(file("400101", representment(rrn, 3_000))).accepted()).isEqualTo(1);
        assertThat(ClearingFile.parse(clearing.outgoing("286001", 0).getLast().content()).records().getFirst().type())
                .isEqualTo(Type.REPRESENTMENT);
    }

    @Test
    void refusesFilesFromStrangersAndRepeats() {
        String content = file("400101", sale(authorised("2860010000000012", 100), 100, "286001"));
        clearing.receive(content);
        assertThatThrownBy(() -> clearing.receive(content)).isInstanceOf(ApiException.class).hasMessageContaining("received before");
        assertThatThrownBy(() -> clearing.receive(file("999999"))).hasMessageContaining("isn't from a member");
    }

    /** An approved authorisation, as the switch would have recorded it. */
    private String authorised(String pan, long cents) {
        String rrn = String.format("C%011d", N.incrementAndGet());
        store.requested("400101", rrn, "000001", "286001", "000001", pan, keys.panHash(pan), "0100", cents, "840");
        store.answered("400101", rrn, "000001", "00", "ABC123", "ISSUER");
        return rrn;
    }

    private static Record sale(String rrn, long cents, String bin) {
        return new Record(Type.SALE, "400101", "0", rrn, "ABC123", cents, "840", LocalDate.now(), "5999", "RPA000000000001",
                "W0000001", bin, "0012", 0, null, null, null);
    }

    private static Record chargeback(String rrn, long cents) {
        return new Record(Type.CHARGEBACK, "400101", "286001", rrn, "ABC123", cents, "840", LocalDate.now(), "5999",
                "RPA000000000001", "W0000001", "286001", "0012", 0, "1310", null, "dsp_1");
    }

    private static Record representment(String rrn, long cents) {
        return new Record(Type.REPRESENTMENT, "400101", "286001", rrn, "ABC123", cents, "840", LocalDate.now(), "5999",
                "RPA000000000001", "W0000001", "286001", "0012", 0, "1310", null, "dsp_1");
    }

    private static String file(String sender, Record... records) {
        return ClearingFile.write(new ClearingFile.Header("F" + N.incrementAndGet(), sender, "900001", LocalDate.now(),
                LocalDateTime.now()), List.of(records));
    }
}
