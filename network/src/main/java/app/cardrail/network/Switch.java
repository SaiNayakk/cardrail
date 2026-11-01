package app.cardrail.network;

import app.cardrail.common.iso.IsoTimeoutException;
import app.cardrail.common.trace.TraceReporter;
import app.cardrail.iso8583.Iso8583Codec;
import app.cardrail.iso8583.IsoMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ExecutionException;

import static app.cardrail.iso8583.ResponseCode.*;

/**
 * Routes messages between acquirers and issuers. On the way out the network gives a message its own trace number
 * (field 11) and names itself (33) and the issuer (100); on the way back it restores the acquirer's trace number,
 * so each side sees only its own numbering.
 *
 * <p>When an issuer can't answer (it is DOWN, or this request timed out), the network stands in for it within the
 * issuer's limits ({@link StandIn}). Reversals are routed to whoever the original went to; one the issuer can't
 * take now is accepted by the network and delivered later as an advice ({@link AdviceSender}).
 */
@Service
public class Switch {

    private static final Logger log = LoggerFactory.getLogger(Switch.class);
    static final String ISSUER = "ISSUER";
    static final String NETWORK = "NETWORK";
    static final String STAND_IN = "STAND_IN";

    private final NetworkProperties props;
    private final Routing routing;
    private final IssuerLinks links;
    private final IssuerHealth health;
    private final StandIn standIn;
    private final NetworkStore store;
    private final NetworkKeys keys;
    private final Iso8583Codec codec;
    private final TraceReporter trace;

    public Switch(NetworkProperties props, Routing routing, IssuerLinks links, IssuerHealth health, StandIn standIn,
                  NetworkStore store, NetworkKeys keys, Iso8583Codec codec, TraceReporter trace) {
        this.props = props;
        this.routing = routing;
        this.links = links;
        this.health = health;
        this.standIn = standIn;
        this.store = store;
        this.keys = keys;
        this.codec = codec;
        this.trace = trace;
    }

    public IsoMessage authorise(IsoMessage request, String acquirerParty) {
        for (int field : new int[] {2, 3, 4, 11, 32, 37, 49}) {
            if (!request.has(field)) {
                return reply(request, request.response(FORMAT_ERROR), acquirerParty);
            }
        }
        String acquirer = request.get(32);
        String rrn = request.get(37);
        String stan = request.get(11);
        if (!routing.isAcquirer(acquirer)) {
            return reply(request, request.response(DO_NOT_HONOUR), acquirerParty);
        }

        var answered = store.answer(acquirer, rrn, stan);
        if (answered.isPresent()) {
            // a repeat of a request already answered (or reversed before it arrived): the same answer, nobody asked again
            IsoMessage response = request.response(answered.get().responseCode());
            if (answered.get().authCode() != null) {
                response.set(38, answered.get().authCode());
            }
            return reply(request, response, acquirerParty);
        }

        var range = routing.route(request.get(2));
        String networkStan = links.nextStan();
        String issuerId = range.map(Routing.Range::issuerId).orElse(null);
        boolean first = store.requested(acquirer, rrn, stan, issuerId, networkStan, request.get(2), keys.panHash(request.get(2)),
                request.mti(), Long.parseLong(request.get(4)), request.get(49));
        if (!first) {
            // the same request is still with the issuer: it is answered once, on the first copy
            return reply(request, request.response(DUPLICATE), acquirerParty);
        }
        if (range.isEmpty() || links.link(issuerId).isEmpty()) {
            return decided(request, request.response(NO_ROUTE), acquirerParty, NETWORK);
        }
        String issuerParty = Parties.issuer(issuerId);
        if (!health.isUp(issuerId)) {
            return standIn(request, issuerId, acquirerParty, issuerParty + " isn't answering", false);
        }

        IsoMessage forwarded = copy(request).set(11, networkStan).set(33, props.institutionId()).set(100, issuerId);
        trace.iso(rrn, Parties.RAILNET, issuerParty, forwarded);
        try {
            IsoMessage issuerResponse = links.link(issuerId).get().send(forwarded, props.issuerTimeout()).get();
            health.answered(issuerId);
            IsoMessage response = copy(issuerResponse).set(11, stan).remove(33).remove(100);
            return decided(request, response, acquirerParty, ISSUER);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            health.missed(issuerId);
            log.warn("Issuer {} didn't answer {}: {}", issuerId, rrn, cause.getMessage());
            return standIn(request, issuerId, acquirerParty, cause instanceof IsoTimeoutException
                    ? "No answer within " + props.issuerTimeout().toMillis() + " ms" : issuerParty + " unreachable", true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return decided(request, request.response(SYSTEM_ERROR), acquirerParty, NETWORK);
        }
    }

    /**
     * @param mayHaveArrived the issuer may have received the request and approved it late; if stand-in declines,
     *                       the issuer is told to reverse it, or it would hold money for a payment that failed
     */
    private IsoMessage standIn(IsoMessage request, String issuerId, String acquirerParty, String why, boolean mayHaveArrived) {
        IsoMessage response = standIn.decide(request, issuerId);
        boolean approved = APPROVED.equals(response.get(39));
        if (!approved && mayHaveArrived) {
            IsoMessage owed = copyAs("0420", copy(request).set(33, props.institutionId()).set(100, issuerId)
                    .set(90, request.mti() + request.get(11) + (request.has(7) ? request.get(7) : "0".repeat(10))
                            + String.format("%011d", Long.parseLong(request.get(32))) + "0".repeat(11)));
            store.enqueueAdvice(issuerId, request.get(32), request.get(37), "0420", keys.seal(codec.encode(owed)));
        }
        trace.note(request.get(37), Parties.RAILNET, Parties.issuer(issuerId), why + ": Railnet stands in and "
                + (approved ? "approves, telling the issuer later (0120)" : "declines"), Map.of("decidedBy", "stand-in"));
        return decided(request, response, acquirerParty, STAND_IN);
    }

    /**
     * An 0400 (please reverse) or 0420 (this was reversed). The original is found by the acquirer's reference and
     * the reversal goes where it went. If the issuer can't take it now, the network accepts it on the issuer's
     * behalf and delivers it as an 0420 advice once the issuer answers: a hold must never be left behind.
     */
    public IsoMessage reverse(IsoMessage reversal, String acquirerParty) {
        if (!reversal.has(11) || !reversal.has(32) || !reversal.has(37)) {
            return reply(reversal, reversal.response(FORMAT_ERROR), acquirerParty);
        }
        String acquirer = reversal.get(32);
        String rrn = reversal.get(37);
        boolean advice = "0420".equals(reversal.mti());
        var original = store.original(acquirer, rrn);
        if (original.isEmpty()) {
            // Never seen. If the original turns up late, it will be answered "25" here and never reach an issuer.
            String originalStan = reversal.has(90) ? reversal.get(90).substring(4, 10) : reversal.get(11);
            store.tombstone(acquirer, rrn, originalStan, reversal.has(90) ? reversal.get(90).substring(0, 4) : "0100");
            return reply(reversal, reversal.response(advice ? APPROVED : ORIGINAL_NOT_FOUND), acquirerParty);
        }
        String issuerId = original.get().issuerId();
        String code = original.get().responseCode();
        boolean nothingHeld = issuerId == null
                || NETWORK.equals(original.get().decidedBy())
                || (code != null && !APPROVED.equals(code));   // declined by the issuer, or by stand-in (which then reversed it)
        if (nothingHeld) {
            store.reversed(acquirer, rrn);
            return reply(reversal, reversal.response(APPROVED), acquirerParty);
        }
        String issuerParty = Parties.issuer(issuerId);
        // An earlier advice for this payment may still be queued: the reversal must not overtake it.
        boolean queuedBehind = store.hasPendingAdvice(issuerId, acquirer, rrn);
        if (health.isUp(issuerId) && !queuedBehind && links.link(issuerId).isPresent()) {
            IsoMessage forwarded = copy(reversal).set(11, links.nextStan()).set(33, props.institutionId()).set(100, issuerId);
            trace.iso(rrn, Parties.RAILNET, issuerParty, forwarded);
            try {
                IsoMessage issuerResponse = links.link(issuerId).get().send(forwarded, props.issuerTimeout()).get();
                health.answered(issuerId);
                store.reversed(acquirer, rrn);
                return reply(reversal, copy(issuerResponse).set(11, reversal.get(11)).remove(33).remove(100), acquirerParty);
            } catch (ExecutionException e) {
                health.missed(issuerId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return reply(reversal, reversal.response(SYSTEM_ERROR), acquirerParty);
            }
        }
        IsoMessage owed = copyAs("0420", copy(reversal).set(33, props.institutionId()).set(100, issuerId));
        store.enqueueAdvice(issuerId, acquirer, rrn, "0420", keys.seal(codec.encode(owed)));
        store.reversed(acquirer, rrn);
        trace.note(rrn, Parties.RAILNET, issuerParty, "Reversal accepted for " + issuerParty + "; delivered when it answers (0420)",
                Map.of());
        return reply(reversal, reversal.response(APPROVED), acquirerParty);
    }

    private IsoMessage decided(IsoMessage request, IsoMessage response, String acquirerParty, String decidedBy) {
        store.answered(request.get(32), request.get(37), request.get(11), response.get(39), response.get(38), decidedBy);
        return reply(request, response, acquirerParty);
    }

    private IsoMessage reply(IsoMessage request, IsoMessage response, String acquirerParty) {
        trace.iso(request.get(37), Parties.RAILNET, acquirerParty, response);
        return response;
    }

    static IsoMessage copy(IsoMessage message) {
        return copyAs(message.mti(), message);
    }

    static IsoMessage copyAs(String mti, IsoMessage message) {
        IsoMessage copy = new IsoMessage(mti);
        message.fields().forEach(copy::set);
        return copy;
    }
}
