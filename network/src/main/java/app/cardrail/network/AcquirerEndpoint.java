package app.cardrail.network;

import app.cardrail.common.iso.IsoServer;
import app.cardrail.common.trace.TraceReporter;
import app.cardrail.iso8583.Iso8583Codec;
import app.cardrail.iso8583.IsoMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;

import static app.cardrail.iso8583.ResponseCode.*;

/** Where acquirers connect to Railnet. */
@Component
class AcquirerEndpoint implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AcquirerEndpoint.class);

    private final NetworkProperties props;
    private final Iso8583Codec codec;
    private final Switch network;
    private final TraceReporter trace;
    private volatile IsoServer server;

    AcquirerEndpoint(NetworkProperties props, Iso8583Codec codec, Switch network, TraceReporter trace) {
        this.props = props;
        this.codec = codec;
        this.network = network;
        this.trace = trace;
    }

    IsoMessage handle(IsoMessage request) {
        String acquirer = Parties.acquirer(request.get(32));
        return switch (request.mti()) {
            case "0100" -> network.authorise(request, acquirer);
            case "0400", "0420" -> network.reverse(request, acquirer);
            case "0800" -> request.response(APPROVED);   // echoes stay out of the trace
            default -> {
                log.warn("Railnet doesn't route {} yet", request.mti());
                yield request.mti().charAt(2) % 2 == 0 ? request.response(INVALID_TRANSACTION) : null;
            }
        };
    }

    @Override
    public void start() {
        try {
            server = new IsoServer("railnet-iso", props.isoBind(), props.isoPort(), codec, this::handle, 16);
        } catch (IOException e) {
            throw new UncheckedIOException("Railnet couldn't listen on port " + props.isoPort(), e);
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

    @Configuration(proxyBeanMethods = false)
    static class Beans {

        @Bean
        Iso8583Codec codec() {
            return new Iso8583Codec(app.cardrail.iso8583.MessageSpec.RAILNET);
        }

        @Bean
        TraceReporter traceReporter(NetworkProperties props, ObjectMapper json) {
            return new TraceReporter(Parties.RAILNET, props.traceUrl(), props.internalKey(), json);
        }
    }
}
