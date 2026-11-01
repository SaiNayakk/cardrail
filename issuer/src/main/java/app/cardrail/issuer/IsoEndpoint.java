package app.cardrail.issuer;

import app.cardrail.common.iso.IsoServer;
import app.cardrail.common.trace.TraceReporter;
import app.cardrail.iso8583.Iso8583Codec;
import app.cardrail.iso8583.IsoMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;

import static app.cardrail.iso8583.ResponseCode.*;

/** Where Railnet connects to Kestrel Bank: authorisations in, decisions out. */
@Component
class IsoEndpoint implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(IsoEndpoint.class);

    private final IssuerProperties props;
    private final Iso8583Codec codec;
    private final Authoriser authoriser;
    private final TraceReporter trace;
    private volatile IsoServer server;

    IsoEndpoint(IssuerProperties props, Iso8583Codec codec, Authoriser authoriser, TraceReporter trace) {
        this.props = props;
        this.codec = codec;
        this.authoriser = authoriser;
        this.trace = trace;
    }

    IsoMessage handle(IsoMessage request) {
        // Each party reports what it sends, so every hop appears once in the trace.
        IsoMessage response = switch (request.mti()) {
            case "0100" -> authoriser.authorise(request);
            case "0800" -> request.response(APPROVED);
            default -> {
                log.warn("Kestrel Bank doesn't handle {} yet", request.mti());
                yield request.mti().charAt(2) % 2 == 0 ? request.response(INVALID_TRANSACTION) : null;
            }
        };
        if (response != null) {
            trace.iso(request.get(37), Parties.KESTREL, Parties.RAILNET, response);
        }
        return response;
    }

    @Override
    public void start() {
        try {
            server = new IsoServer("kestrel-iso", props.isoBind(), props.isoPort(), codec, this::handle, 8);
        } catch (IOException e) {
            throw new UncheckedIOException("Kestrel Bank couldn't listen on port " + props.isoPort(), e);
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
}
