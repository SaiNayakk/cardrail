package app.cardrail.network;

import app.cardrail.common.iso.IsoLink;
import app.cardrail.common.iso.IsoTimeoutException;
import app.cardrail.common.trace.TraceReporter;
import app.cardrail.iso8583.IsoMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;

import static app.cardrail.iso8583.ResponseCode.*;

/**
 * Routes an authorisation from an acquirer to the card's issuer and the answer back. On the way out the network
 * gives the message its own trace number (field 11) and names itself (33) and the issuer (100); on the way back
 * it restores the acquirer's trace number, so each side sees only its own numbering.
 */
@Service
public class Switch {

    private static final Logger log = LoggerFactory.getLogger(Switch.class);
    static final String ISSUER = "ISSUER";
    static final String NETWORK = "NETWORK";

    private final NetworkProperties props;
    private final Routing routing;
    private final IssuerLinks links;
    private final NetworkStore store;
    private final TraceReporter trace;

    public Switch(NetworkProperties props, Routing routing, IssuerLinks links, NetworkStore store, TraceReporter trace) {
        this.props = props;
        this.routing = routing;
        this.links = links;
        this.store = store;
        this.trace = trace;
    }

    public IsoMessage authorise(IsoMessage request, String acquirerParty) {
        for (int field : new int[] {2, 4, 11, 32, 37, 49}) {
            if (!request.has(field)) {
                return reply(request, request.response(FORMAT_ERROR), acquirerParty, null);
            }
        }
        String acquirer = request.get(32);
        String rrn = request.get(37);
        String stan = request.get(11);
        if (!routing.isAcquirer(acquirer)) {
            return reply(request, request.response(DO_NOT_HONOUR), acquirerParty, null);
        }

        var answered = store.answer(acquirer, rrn, stan);
        if (answered.isPresent()) {
            // a repeat of a request already answered: the same answer, without asking the issuer again
            IsoMessage response = request.response(answered.get().responseCode());
            if (answered.get().authCode() != null) {
                response.set(38, answered.get().authCode());
            }
            return reply(request, response, acquirerParty, null);
        }

        var range = routing.route(request.get(2));
        String networkStan = links.nextStan();
        String issuerId = range.map(Routing.Range::issuerId).orElse(null);
        boolean first = store.requested(acquirer, rrn, stan, issuerId, networkStan, request.get(2), request.mti(),
                Long.parseLong(request.get(4)), request.get(49));
        if (!first) {
            // the same request is still with the issuer: it is answered once, on the first copy
            return reply(request, request.response(DUPLICATE), acquirerParty, null);
        }
        if (range.isEmpty()) {
            return decided(request, request.response(NO_ROUTE), acquirerParty, NETWORK);
        }
        var link = links.link(issuerId);
        if (link.isEmpty()) {
            return decided(request, request.response(NO_ROUTE), acquirerParty, NETWORK);
        }

        IsoMessage forwarded = copy(request).set(11, networkStan).set(33, props.institutionId()).set(100, issuerId);
        String issuerParty = Parties.issuer(issuerId);
        trace.iso(rrn, Parties.RAILNET, issuerParty, forwarded);
        IsoMessage issuerResponse;
        try {
            issuerResponse = link.get().send(forwarded, props.issuerTimeout()).get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            log.warn("Issuer {} didn't answer {}: {}", issuerId, rrn, cause.getMessage());
            trace.note(rrn, issuerParty, Parties.RAILNET, cause instanceof IsoTimeoutException
                    ? "No answer within " + props.issuerTimeout().toMillis() + " ms" : "Issuer unreachable", java.util.Map.of());
            return decided(request, request.response(ISSUER_UNAVAILABLE), acquirerParty, NETWORK);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return decided(request, request.response(SYSTEM_ERROR), acquirerParty, NETWORK);
        }

        IsoMessage response = copy(issuerResponse).set(11, stan).remove(33).remove(100);
        return decided(request, response, acquirerParty, ISSUER);
    }

    private IsoMessage decided(IsoMessage request, IsoMessage response, String acquirerParty, String decidedBy) {
        store.answered(request.get(32), request.get(37), request.get(11), response.get(39), response.get(38), decidedBy);
        return reply(request, response, acquirerParty, decidedBy);
    }

    private IsoMessage reply(IsoMessage request, IsoMessage response, String acquirerParty, String decidedBy) {
        trace.iso(request.get(37), Parties.RAILNET, acquirerParty, response);
        return response;
    }

    static IsoMessage copy(IsoMessage message) {
        IsoMessage copy = new IsoMessage(message.mti());
        message.fields().forEach(copy::set);
        return copy;
    }
}
