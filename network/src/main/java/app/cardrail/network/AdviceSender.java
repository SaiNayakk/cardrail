package app.cardrail.network;

import app.cardrail.common.trace.TraceReporter;
import app.cardrail.iso8583.Iso8583Codec;
import app.cardrail.iso8583.IsoMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Delivers what the network owes each issuer, oldest first: one at a time, and only to an issuer that is
 * answering. An advice is resent until the issuer acknowledges it (0130, 0430); the issuer recognises a repeat
 * by its reference, so resending is harmless. A failure stops that issuer's queue until the next round, so
 * advices never overtake each other (a reversal must not arrive before what it reverses).
 */
@Component
public class AdviceSender {

    private static final Logger log = LoggerFactory.getLogger(AdviceSender.class);

    private final NetworkProperties props;
    private final NetworkStore store;
    private final NetworkKeys keys;
    private final Iso8583Codec codec;
    private final IssuerLinks links;
    private final IssuerHealth health;
    private final TraceReporter trace;

    public AdviceSender(NetworkProperties props, NetworkStore store, NetworkKeys keys, Iso8583Codec codec, IssuerLinks links,
                        IssuerHealth health, TraceReporter trace) {
        this.props = props;
        this.store = store;
        this.keys = keys;
        this.codec = codec;
        this.links = links;
        this.health = health;
        this.trace = trace;
    }

    @Scheduled(fixedDelayString = "${network.advice-every:1s}", initialDelayString = "${network.advice-every:1s}")
    public void deliver() {
        if (props.issuers() == null) {
            return;
        }
        for (String issuerId : props.issuers().keySet()) {
            if (!health.isUp(issuerId)) {
                continue;
            }
            var link = links.link(issuerId);
            if (link.isEmpty()) {
                continue;
            }
            for (NetworkStore.Advice advice : store.pendingAdvices(issuerId, 20)) {
                IsoMessage message = codec.decode(keys.open(advice.message())).set(11, links.nextStan());
                Duration timeout = props.issuerTimeout();
                try {
                    trace.iso(advice.rrn(), Parties.RAILNET, Parties.issuer(issuerId), message);
                    IsoMessage ack = link.get().send(message, timeout).get(timeout.toMillis() + 500, TimeUnit.MILLISECONDS);
                    store.acknowledged(advice.id());
                    health.answered(issuerId);
                    log.info("Issuer {} acknowledged the {} advice for {}", issuerId, advice.mti(), advice.rrn());
                } catch (Exception e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    store.adviceFailed(advice.id(), e.getClass().getSimpleName());
                    health.missed(issuerId);
                    break;
                }
            }
        }
    }
}
