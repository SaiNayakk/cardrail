package app.cardrail.closedloop;

import app.cardrail.common.ApiException;
import app.cardrail.common.iso.IsoServer;
import app.cardrail.common.trace.TraceReporter;
import app.cardrail.iso8583.Iso8583Codec;
import app.cardrail.iso8583.IsoMessage;
import app.cardrail.iso8583.MessageSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import static app.cardrail.iso8583.ResponseCode.*;

/** Where acquirers connect to Meridian (ISO 8583, like a network), and its HTTP side: submissions, payouts, books, test cards. */
@RestController
class MeridianEndpoint implements SmartLifecycle, ApplicationRunner {

    private final MeridianApplication.Properties props;
    private final Iso8583Codec codec;
    private final Meridian meridian;
    private final MeridianKeys keys;
    private final TraceReporter trace;
    private volatile IsoServer server;

    MeridianEndpoint(MeridianApplication.Properties props, Iso8583Codec codec, Meridian meridian, MeridianKeys keys, TraceReporter trace) {
        this.props = props;
        this.codec = codec;
        this.meridian = meridian;
        this.keys = keys;
        this.trace = trace;
    }

    IsoMessage handle(IsoMessage request) {
        String acquirer = "400101".equals(request.get(32)) ? "railpay-acquiring" : "acquirer-" + request.get(32);
        IsoMessage response = switch (request.mti()) {
            case "0100" -> meridian.authorise(request);
            case "0400", "0420" -> meridian.reverse(request);
            case "0800" -> request.response(APPROVED);
            default -> request.mti().charAt(2) % 2 == 0 ? request.response(INVALID_TRANSACTION) : null;
        };
        if (response != null && !"0800".equals(request.mti())) {
            trace.iso(request.get(37), Meridian.PARTY, acquirer, response);
        }
        return response;
    }

    @Override
    public void start() {
        try {
            server = new IsoServer("meridian-iso", props.isoBind(), props.isoPort(), codec, this::handle, 8);
        } catch (IOException e) {
            throw new UncheckedIOException("Meridian couldn't listen on port " + props.isoPort(), e);
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    @Override
    public boolean isRunning() {
        return server != null;
    }

    int port() {
        return server.port();
    }

    // ---- the published test cards

    record TestCard(String fourteen, long limitCents, String status, String label) {}

    static final List<TestCard> CARDS = List.of(
            new TestCard("39000000000001", 300_000, "ACTIVE", "Meridian charge card ($3,000 to spend): approved"),
            new TestCard("39000000000002", 1_000, "ACTIVE", "Meridian charge card with $10 left: declined over $10"));

    @Override
    public void run(ApplicationArguments args) {
        if (!props.demo()) {
            return;
        }
        for (TestCard c : CARDS) {
            String pan = c.fourteen() + app.cardrail.iso8583.Luhn.checkDigit(c.fourteen());
            if (meridian.card(pan).isEmpty()) {
                meridian.open("Test cardmember", c.limitCents(), c.fourteen(), "3012", c.label());
            }
        }
    }

    record TestCardView(String number, String expiry, String cvv2, String kind, String outcome) {}

    /** Meridian's published test cards: fifteen digits, a four-digit security code. Demo only. */
    @GetMapping("/v1/test-cards")
    List<TestCardView> testCards() {
        if (!props.demo()) {
            throw ApiException.notFound("test cards");
        }
        return CARDS.stream().map(c -> {
            String pan = c.fourteen() + app.cardrail.iso8583.Luhn.checkDigit(c.fourteen());
            return new TestCardView(pan, "12/30", keys.cid(pan, "3012"), "CHARGE", c.label());
        }).toList();
    }

    /** An acquirer submits its merchants' Meridian sales and refunds (the same file format as Railnet's clearing). */
    @PostMapping(value = "/internal/v1/submissions", consumes = "text/plain")
    Meridian.Submitted submit(@RequestBody String file) {
        return meridian.submit(file);
    }

    @PostMapping("/internal/v1/payouts/run")
    List<Meridian.Payout> payOut() {
        return meridian.payOut();
    }

    @GetMapping("/internal/v1/merchants/{mid}/payouts")
    List<Meridian.Payout> payouts(@PathVariable String mid) {
        return meridian.payouts(mid);
    }

    record Books(String party, long total, Map<String, Long> accounts) {}

    @GetMapping("/v1/books")
    Books books() {
        return new Books("meridian", meridian.ledger().total(), meridian.ledger().balances(""));
    }

    @Configuration(proxyBeanMethods = false)
    static class Beans {

        @Bean
        Iso8583Codec codec() {
            return new Iso8583Codec(MessageSpec.RAILNET);
        }

        @Bean
        TraceReporter traceReporter(MeridianApplication.Properties props, ObjectMapper json) {
            return new TraceReporter(Meridian.PARTY, props.traceUrl(), props.internalKey(), json);
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
