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
        "acquirer.reversal-every=1h",
        "acquirer.echo-every=1h",
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
                if (!request.mti().equals("0100")) {
                    return request.response("00");
                }
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
    @Autowired Reversing reversing;
    @Autowired AcquirerStore store;
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
        IsoMessage sent = seen("0100").getFirst();
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
    void aPaymentWithNoAnswerIsReversedUntilTheNetworkHearsIt() {
        long owedBefore = store.reversalsOwed();
        var result = authorising.authorise(request(66_600, null));
        assertThat(store.reversalsOwed()).isEqualTo(owedBefore + 1);

        reversing.sendOwed();

        IsoMessage advice = seen("0420").stream().filter(m -> m.get(37).equals(result.rrn())).findFirst().orElseThrow();
        assertThat(advice.get(37)).isEqualTo(result.rrn());
        assertThat(advice.get(90)).startsWith("0100" + result.stan());
        assertThat(advice.has(2)).isFalse();
        assertThat(store.authorisation(result.rrn()).orElseThrow().status()).isEqualTo("REVERSED");
        assertThat(store.reversalsOwed()).isZero();
    }

    @Test
    void aVoidReversesTheHoldWithAn0400() {
        var result = authorising.authorise(request(1_000, null));

        var outcome = reversing.voidPayment(result.rrn());

        assertThat(outcome.status()).isEqualTo("REVERSED");
        assertThat(seen("0400")).hasSize(1);
        assertThatThrownBy(() -> reversing.capture(result.rrn(), 1_000)).hasMessageContaining("only an approved payment");
    }

    @Test
    void capturesAndRefundsUpToWhatWasCaptured() {
        var purchase = authorising.authorise(request(1_000, null));
        reversing.capture(purchase.rrn(), 800);
        assertThatThrownBy(() -> reversing.voidPayment(purchase.rrn())).hasMessageContaining("can be voided");

        var refund = authorising.authorise(refund(purchase.rrn(), 500));
        assertThat(refund.status()).isEqualTo("APPROVED");
        assertThat(seen("0100").getLast().get(3)).isEqualTo("200000");
        assertThat(seen("0100").getLast().has(48) && seen("0100").getLast().get(48).contains("92")).isFalse();
        assertThatThrownBy(() -> authorising.authorise(refund(purchase.rrn(), 301))).hasMessageContaining("more than was captured");
        assertThat(authorising.authorise(refund(purchase.rrn(), 300)).status()).isEqualTo("APPROVED");
    }

    @Test
    void refusesAReusedReference() {
        var request = request(100, null);
        authorising.authorise(request);
        assertThatThrownBy(() -> authorising.authorise(request)).isInstanceOf(ApiException.class)
                .hasMessageContaining("used before");
    }

    private List<IsoMessage> seen(String mti) {
        return seenByNetwork.stream().filter(m -> m.mti().equals(mti)).toList();
    }

    private Authorising.Request refund(String originalRrn, long cents) {
        String rrn = "F" + String.format("%011d", RRN.incrementAndGet());
        return new Authorising.Request(mid, null, "2860010000000012", "3012", null, cents, "USD", rrn, originalRrn);
    }

    private Authorising.Request request(long cents, String cvv2) {
        String rrn = "A" + String.format("%011d", RRN.incrementAndGet());
        return new Authorising.Request(mid, null, "2860010000000012", "3012", cvv2, cents, "USD", rrn, null);
    }
}
