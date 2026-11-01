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
        "network.issuer-timeout=500ms",
        "cardrail.internal-key=test-internal-key-0123456789"})
class SwitchTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    static final Iso8583Codec CODEC = new Iso8583Codec(MessageSpec.RAILNET);
    static final List<IsoMessage> seenByIssuer = new CopyOnWriteArrayList<>();
    static final IsoServer issuer;

    static {
        try {
            // A fake issuer: approves, except cards ending 0000, which it never answers.
            issuer = new IsoServer("fake-issuer", "127.0.0.1", 0, CODEC, request -> {
                seenByIssuer.add(request);
                return request.get(2).endsWith("0000") ? null : request.response("00").set(38, "ABC123");
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
    private IsoLink acquirer;

    @BeforeEach
    void connect() {
        seenByIssuer.clear();
        acquirer = new IsoLink("acquirer", "127.0.0.1", endpoint.port(), CODEC);
    }

    @AfterEach
    void disconnect() {
        acquirer.close();
    }

    @Test
    void routesByBinAndKeepsEachSidesTraceNumbers() throws Exception {
        IsoMessage request = request("2860010000000012", "400101");

        IsoMessage response = send(request);

        assertThat(response.get(39)).isEqualTo("00");
        assertThat(response.get(38)).isEqualTo("ABC123");
        assertThat(response.get(11)).isEqualTo(request.get(11));
        assertThat(response.has(100)).isFalse();

        IsoMessage forwarded = seenByIssuer.getFirst();
        assertThat(forwarded.get(37)).isEqualTo(request.get(37));
        assertThat(forwarded.get(33)).isEqualTo("900001");
        assertThat(forwarded.get(100)).isEqualTo("286001");
        assertThat(forwarded.get(11)).isNotEqualTo(request.get(11));
    }

    @Test
    void answersARepeatWithoutAskingTheIssuerAgain() throws Exception {
        IsoMessage request = request("2860010000000012", "400101");

        send(request);
        IsoMessage again = send(request);

        assertThat(again.get(38)).isEqualTo("ABC123");
        assertThat(seenByIssuer).hasSize(1);
    }

    @Test
    void refusesCardsWithNoIssuer() throws Exception {
        assertThat(send(request("5555550000000017", "400101")).get(39)).isEqualTo("92");
        assertThat(seenByIssuer).isEmpty();
    }

    @Test
    void refusesStrangers() throws Exception {
        assertThat(send(request("2860010000000012", "999999")).get(39)).isEqualTo("05");
    }

    @Test
    void answersForAnIssuerThatDoesNotAnswer() throws Exception {
        long started = System.nanoTime();
        IsoMessage response = send(request("2860010000000000", "400101"));
        assertThat(response.get(39)).isEqualTo("91");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void answersEchoes() throws Exception {
        IsoMessage echo = new IsoMessage("0800").set(7, "1102093015").set(11, stan()).set(32, "400101")
                .set(37, "ECHO00000001").set(70, "301");
        assertThat(send(echo).mti()).isEqualTo("0810");
    }

    private IsoMessage send(IsoMessage request) throws Exception {
        return acquirer.send(request, Duration.ofSeconds(5)).get();
    }

    private static IsoMessage request(String pan, String acquirerId) {
        String stan = stan();
        return new IsoMessage("0100").set(2, pan).set(3, "000000").set(4, "000000001000").set(7, "1102093015")
                .set(11, stan).set(14, "3012").set(32, acquirerId).set(37, "R" + String.format("%011d", Integer.parseInt(stan)))
                .set(49, "840");
    }

    private static String stan() {
        return String.format("%06d", STAN.incrementAndGet());
    }
}
