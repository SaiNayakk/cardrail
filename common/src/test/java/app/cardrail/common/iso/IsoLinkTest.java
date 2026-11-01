package app.cardrail.common.iso;

import app.cardrail.iso8583.Iso8583Codec;
import app.cardrail.iso8583.IsoMessage;
import app.cardrail.iso8583.MessageSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IsoLinkTest {

    private final Iso8583Codec codec = new Iso8583Codec(MessageSpec.RAILNET);
    private IsoServer server;
    private IsoLink link;

    @AfterEach
    void close() {
        if (link != null) link.close();
        if (server != null) server.close();
    }

    @Test
    void matchesResponsesThatArriveOutOfOrder() throws Exception {
        // the first request is answered last: a slow issuer mustn't hold up the others on the connection
        server = new IsoServer("test", "127.0.0.1", 0, codec, request -> {
            sleep(request.get(11).equals("000001") ? 300 : 10);
            return request.response("00").set(38, "A" + request.get(11).substring(1));
        }, 4);
        link = new IsoLink("link", "127.0.0.1", server.port(), codec);

        List<CompletableFuture<IsoMessage>> responses = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            responses.add(link.send(request(i), Duration.ofSeconds(2)));
        }

        for (int i = 1; i <= 5; i++) {
            IsoMessage response = responses.get(i - 1).get();
            assertThat(response.mti()).isEqualTo("0110");
            assertThat(response.get(11)).isEqualTo(String.format("%06d", i));
            assertThat(response.get(38)).isEqualTo("A" + String.format("%05d", i));
        }
    }

    @Test
    void timesOutWhenNoResponseComes() throws Exception {
        server = new IsoServer("silent", "127.0.0.1", 0, codec, request -> null, 1);
        link = new IsoLink("link", "127.0.0.1", server.port(), codec);

        assertThatThrownBy(() -> link.send(request(1), Duration.ofMillis(200)).get())
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IsoTimeoutException.class);
    }

    @Test
    void reconnectsAfterThePeerRestarts() throws Exception {
        server = new IsoServer("first", "127.0.0.1", 0, codec, request -> request.response("00"), 1);
        int port = server.port();
        link = new IsoLink("link", "127.0.0.1", port, codec);
        assertThat(link.send(request(1), Duration.ofSeconds(2)).get().get(39)).isEqualTo("00");

        server.close();
        sleep(100);
        server = new IsoServer("second", "127.0.0.1", port, codec, request -> request.response("05"), 1);

        // the first send after the restart may find the old connection dead; the one after must work
        try {
            link.send(request(2), Duration.ofSeconds(1)).get();
        } catch (ExecutionException ignored) {
            // expected sometimes
        }
        assertThat(link.send(request(3), Duration.ofSeconds(2)).get().get(39)).isEqualTo("05");
    }

    @Test
    void failsFastWhenNobodyListens() {
        link = new IsoLink("link", "127.0.0.1", 1, codec);
        assertThatThrownBy(() -> link.send(request(1), Duration.ofSeconds(2)).get())
                .hasCauseInstanceOf(java.io.IOException.class);
    }

    private static IsoMessage request(int stan) {
        return new IsoMessage("0100").set(2, "4111111111111111").set(4, "000000000100")
                .set(11, String.format("%06d", stan)).set(37, "000000000001");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
