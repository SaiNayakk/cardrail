package app.cardrail.acquirer;

import app.cardrail.common.ApiException;
import app.cardrail.common.iso.IsoServer;
import app.cardrail.iso8583.Iso8583Codec;
import app.cardrail.iso8583.IsoMessage;
import app.cardrail.iso8583.MessageSpec;
import app.cardrail.iso8583.Subelements;
import org.junit.jupiter.api.AfterAll;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.name=acquirer",
        "acquirer.network-timeout=500ms",
        "cardrail.internal-key=test-internal-key-0123456789"})
class AuthorisingTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    static final List<IsoMessage> seenByNetwork = new CopyOnWriteArrayList<>();
    static final IsoServer network;

    static {
        try {
            // A fake network: approves amounts under $100, declines the rest, never answers $666.
            network = new IsoServer("fake-network", "127.0.0.1", 0, new Iso8583Codec(MessageSpec.RAILNET), request -> {
                seenByNetwork.add(request);
                long cents = Long.parseLong(request.get(4));
                if (cents == 66_600) return null;
                return cents < 10_000 ? request.response("00").set(38, "XYZ789") : request.response("51");
            }, 4);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&currentSchema=acquirer");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("acquirer.network-port", network::port);
    }

    @AfterAll
    static void stop() {
        network.close();
    }

    private static final AtomicInteger RRN = new AtomicInteger();

    @Autowired AcquirerController controller;
    @Autowired Authorising authorising;
    private String mid;

    @BeforeEach
    void merchant() {
        seenByNetwork.clear();
        mid = controller.onboard(new AcquirerController.OnboardRequest("Corner Books", "5942", "Portland", "US", null)).mid();
    }

    @Test
    void buildsAnEcommerceAuthorisationRequest() {
        var result = authorising.authorise(request(1999, "123"));

        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.authCode()).isEqualTo("XYZ789");
        IsoMessage sent = seenByNetwork.getFirst();
        assertThat(sent.mti()).isEqualTo("0100");
        assertThat(sent.get(4)).isEqualTo("000000001999");
        assertThat(sent.get(18)).isEqualTo("5942");
        assertThat(sent.get(22)).isEqualTo("810");
        assertThat(sent.get(32)).isEqualTo("400101");
        assertThat(sent.get(42)).isEqualTo(mid);
        assertThat(sent.get(43)).hasSize(40).startsWith("Corner Books").endsWith("US");
        assertThat(Subelements.decode(sent.get(48))).containsEntry(Subelements.CVV2, "123");
    }

    @Test
    void reportsDeclinesWithTheirReason() {
        var result = authorising.authorise(request(20_000, null));
        assertThat(result.status()).isEqualTo("DECLINED");
        assertThat(result.responseCode()).isEqualTo("51");
        assertThat(result.responseText()).isEqualTo("Insufficient funds");
    }

    @Test
    void neverCallsASilentNetworkADecline() {
        var result = authorising.authorise(request(66_600, null));
        assertThat(result.status()).isEqualTo("UNKNOWN");
    }

    @Test
    void refusesAReusedReference() {
        var request = request(100, null);
        authorising.authorise(request);
        assertThatThrownBy(() -> authorising.authorise(request)).isInstanceOf(ApiException.class)
                .hasMessageContaining("used before");
    }

    private Authorising.Request request(long cents, String cvv2) {
        String rrn = "A" + String.format("%011d", RRN.incrementAndGet());
        return new Authorising.Request(mid, null, "2860010000000012", "3012", cvv2, cents, "USD", rrn);
    }
}
