package app.cardrail.network;

import app.cardrail.common.iso.IsoLink;
import app.cardrail.common.iso.IsoServer;
import app.cardrail.iso8583.Iso8583Codec;
import app.cardrail.iso8583.IsoMessage;
import app.cardrail.iso8583.MessageSpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.name=network",
        "network.iso-port=0",
        "network.issuer-timeout=400ms",
        "network.key=cHJlcHJvZC1vbmx5LW5ldHdvcmsta2V5LTAwMDAwMDU=",
        // the tests drive echoes and advice delivery themselves
        "network.echo-every=1h",
        "network.advice-every=1h",
        "cardrail.internal-key=test-internal-key-0123456789"})
class SwitchTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    static final Iso8583Codec CODEC = new Iso8583Codec(MessageSpec.RAILNET);
    static final List<IsoMessage> seenByIssuer = new CopyOnWriteArrayList<>();
    static final IsoServer issuer;

    /** Approved debit card; the issuer never answers authorisations for the silent one. */
    static final String CARD = "2860010000000012";
    static final String SILENT = "2860010000009997";

    static {
        try {
            issuer = new IsoServer("fake-issuer", "127.0.0.1", 0, CODEC, request -> {
                seenByIssuer.add(request);
                if (request.mti().equals("0100") && SILENT.equals(request.get(2))) {
                    return null;
                }
                return request.mti().equals("0100") ? request.response("00").set(38, "ABC123") : request.response("00");
            }, 4);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&currentSchema=network");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("network.issuers.286001.host", () -> "127.0.0.1");
        registry.add("network.issuers.286001.port", issuer::port);
    }

    @AfterAll
    static void stopIssuer() {
        issuer.close();
    }

    private static final AtomicInteger STAN = new AtomicInteger();

    @Autowired AcquirerEndpoint endpoint;
    @Autowired IssuerHealth health;
    @Autowired AdviceSender advices;
    @Autowired JdbcClient jdbc;
    private IsoLink acquirer;

    @BeforeEach
    void connect() {
        seenByIssuer.clear();
        health.answered("286001");
        jdbc.sql("update advices set acknowledged_at = now()").update();
        acquirer = new IsoLink("acquirer", "127.0.0.1", endpoint.port(), CODEC);
    }

    @AfterEach
    void disconnect() {
        acquirer.close();
    }

    @Test
    void routesByBinAndKeepsEachSidesTraceNumbers() throws Exception {
        IsoMessage request = request(CARD, 1000);

        IsoMessage response = send(request);

        assertThat(response.get(39)).isEqualTo("00");
        assertThat(response.get(38)).isEqualTo("ABC123");
        assertThat(response.get(11)).isEqualTo(request.get(11));
        assertThat(response.has(100)).isFalse();
        IsoMessage forwarded = seen("0100").getFirst();
        assertThat(forwarded.get(37)).isEqualTo(request.get(37));
        assertThat(forwarded.get(33)).isEqualTo("900001");
        assertThat(forwarded.get(100)).isEqualTo("286001");
        assertThat(forwarded.get(11)).isNotEqualTo(request.get(11));
    }

    @Test
    void answersARepeatWithoutAskingTheIssuerAgain() throws Exception {
        IsoMessage request = request(CARD, 1000);
        send(request);
        IsoMessage again = send(request);
        assertThat(again.get(38)).isEqualTo("ABC123");
        assertThat(seen("0100")).hasSize(1);
    }

    @Test
    void refusesCardsWithNoIssuerAndStrangers() throws Exception {
        assertThat(send(request("5555550000000017", 1000)).get(39)).isEqualTo("92");
        assertThat(send(request(CARD, 1000).set(32, "999999")).get(39)).isEqualTo("05");
        assertThat(seen("0100")).isEmpty();
    }

    @Test
    void standsInForASilentIssuerWithinItsLimitsAndTellsItLater() throws Exception {
        IsoMessage small = send(request(SILENT, 5_000));
        IsoMessage large = send(request(SILENT, 50_000));

        assertThat(small.get(39)).isEqualTo("00");
        assertThat(small.get(38)).hasSize(6);
        assertThat(large.get(39)).isEqualTo("91");
        // the approval is owed as an 0120; the decline, which the issuer may approve late, as an 0420
        assertThat(pendingAdvices()).isEqualTo(2);
    }

    @Test
    void aForwardedPaymentThatStandInDeclinesIsReversedAtTheIssuer() throws Exception {
        IsoMessage large = send(request(SILENT, 50_000));
        assertThat(large.get(39)).isEqualTo("91");

        health.answered("286001");
        advices.deliver();

        // The issuer may have approved it late: it is told to reverse it.
        assertThat(seen("0420")).anyMatch(m -> m.get(37).equals(large.get(37)));
    }

    @Test
    void anIssuerKnownToBeDownIsNotWaitedFor() throws Exception {
        health.missed("286001");
        health.missed("286001");

        long started = System.nanoTime();
        IsoMessage response = send(request(CARD, 2_000));

        assertThat(response.get(39)).isEqualTo("00");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(300));
        assertThat(seen("0100")).isEmpty();

        health.answered("286001");
        advices.deliver();

        IsoMessage advice = seen("0120").getFirst();
        assertThat(advice.get(37)).isEqualTo(response.get(37));
        assertThat(advice.get(39)).isEqualTo("00");
        assertThat(advice.get(38)).isEqualTo(response.get(38));
        assertThat(pendingAdvices()).isZero();
    }

    @Test
    void standInLimitsAreDailyPerCard() throws Exception {
        String card = "2860010000000004";   // Luhn-valid, used only here
        health.missed("286001");
        health.missed("286001");
        assertThat(send(request(card, 9_000)).get(39)).isEqualTo("00");
        assertThat(send(request(card, 9_000)).get(39)).isEqualTo("00");
        assertThat(send(request(card, 9_000)).get(39)).isEqualTo("91");   // over $200 in a day
    }

    @Test
    void forwardsAReversalToWhereTheOriginalWent() throws Exception {
        IsoMessage original = request(CARD, 1000);
        send(original);

        IsoMessage response = send(reversal("0400", original));

        assertThat(response.mti()).isEqualTo("0410");
        assertThat(response.get(39)).isEqualTo("00");
        assertThat(response.get(11)).isNotEqualTo(original.get(11));
        assertThat(seen("0400")).hasSize(1);
    }

    @Test
    void acceptsAReversalForADownIssuerAndDeliversItLater() throws Exception {
        IsoMessage original = request(CARD, 1000);
        send(original);
        health.missed("286001");
        health.missed("286001");

        IsoMessage response = send(reversal("0420", original));

        assertThat(response.get(39)).isEqualTo("00");
        assertThat(seen("0420")).isEmpty();
        health.answered("286001");
        advices.deliver();
        assertThat(seen("0420")).hasSize(1);
    }

    @Test
    void anOriginalArrivingAfterItsReversalNeverReachesTheIssuer() throws Exception {
        IsoMessage late = request(CARD, 1000);

        IsoMessage ack = send(reversal("0420", late));
        IsoMessage original = send(late);

        assertThat(ack.get(39)).isEqualTo("00");
        assertThat(original.get(39)).isEqualTo("25");
        assertThat(seen("0100")).isEmpty();
    }

    @Test
    void answersEchoes() throws Exception {
        IsoMessage echo = new IsoMessage("0800").set(7, "1102093015").set(11, stan()).set(32, "400101")
                .set(37, "ECHO00000001").set(70, "301");
        assertThat(send(echo).mti()).isEqualTo("0810");
    }

    private List<IsoMessage> seen(String mti) {
        return seenByIssuer.stream().filter(m -> m.mti().equals(mti)).toList();
    }

    private long pendingAdvices() {
        return jdbc.sql("select count(*) from advices where acknowledged_at is null").query(Long.class).single();
    }

    private IsoMessage send(IsoMessage request) throws Exception {
        return acquirer.send(request, Duration.ofSeconds(5)).get();
    }

    private static IsoMessage request(String pan, long cents) {
        String stan = stan();
        return new IsoMessage("0100").set(2, pan).set(3, "000000").set(4, String.format("%012d", cents)).set(7, "1102093015")
                .set(11, stan).set(14, "3012").set(32, "400101").set(37, "R" + String.format("%011d", Integer.parseInt(stan)))
                .set(49, "840");
    }

    private static IsoMessage reversal(String mti, IsoMessage original) {
        return new IsoMessage(mti).set(2, original.get(2)).set(3, original.get(3)).set(4, original.get(4))
                .set(7, "1102093020").set(11, stan()).set(32, original.get(32)).set(37, original.get(37)).set(49, "840")
                .set(90, original.mti() + original.get(11) + original.get(7) + String.format("%011d", 400101) + "0".repeat(11));
    }

    private static String stan() {
        return String.format("%06d", STAN.incrementAndGet());
    }
}
