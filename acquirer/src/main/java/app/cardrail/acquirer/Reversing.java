package app.cardrail.acquirer;

import app.cardrail.acquirer.AcquirerStore.Authorisation;
import app.cardrail.common.ApiException;
import app.cardrail.common.trace.TraceReporter;
import app.cardrail.iso8583.IsoMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

import static app.cardrail.iso8583.ResponseCode.APPROVED;

/**
 * Voids, reversals and captures.
 *
 * <ul>
 *   <li><b>Void</b>: an approved, uncaptured payment is reversed with an 0400 so the issuer releases the hold now.
 *       If the network can't be asked, the reversal is owed instead.</li>
 *   <li><b>Owed reversals</b> (a void that couldn't be sent, a payment whose answer never came) go as 0420 advices,
 *       repeated with growing gaps until the network acknowledges one. A reversal is harmless to repeat.</li>
 *   <li><b>Capture</b>: confirms how much of an approved payment the merchant takes. It is a record here; the money
 *       moves when the day is cleared (phase 3).</li>
 * </ul>
 */
@Service
public class Reversing {

    private static final Logger log = LoggerFactory.getLogger(Reversing.class);
    private static final DateTimeFormatter FIELD_7 = DateTimeFormatter.ofPattern("MMddHHmmss");

    private final AcquirerProperties props;
    private final AcquirerStore store;
    private final NetworkLink network;
    private final TraceReporter trace;
    private final TransactionTemplate tx;
    private final Clock clock;

    public Reversing(AcquirerProperties props, AcquirerStore store, NetworkLink network, TraceReporter trace,
                     TransactionTemplate tx, Clock clock) {
        this.props = props;
        this.store = store;
        this.network = network;
        this.trace = trace;
        this.tx = tx;
        this.clock = clock;
    }

    public record Outcome(String rrn, String status) {}

    public Outcome voidPayment(String rrn) {
        Authorisation auth = store.authorisation(rrn).orElseThrow(() -> ApiException.notFound("authorisation " + rrn));
        switch (auth.status()) {
            case "REVERSED" -> { return new Outcome(rrn, "REVERSED"); }
            case "APPROVED" -> { }
            case "UNKNOWN" -> { return new Outcome(rrn, "REVERSING"); }
            default -> throw new ApiException(HttpStatus.CONFLICT, "not_voidable",
                    "only an approved payment that isn't captured can be voided (this one is " + auth.status().toLowerCase() + ")");
        }
        IsoMessage reversal = reversal("0400", auth);
        trace.iso(rrn, Parties.ACQUIRER, party(auth), reversal);
        try {
            IsoMessage response = network.link(auth.scheme()).send(reversal, props.networkTimeout())
                    .get(props.networkTimeout().toMillis() + 500, TimeUnit.MILLISECONDS);
            if (APPROVED.equals(response.get(39)) || "25".equals(response.get(39))) {
                store.setStatus(rrn, "REVERSED");
                return new Outcome(rrn, "REVERSED");
            }
            log.warn("The network answered the void of {} with {}", rrn, response.get(39));
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Void of {} got no answer: {}", rrn, e.getMessage());
        }
        // couldn't be done now: it is owed, and will be told to the network until it acknowledges
        store.oweReversal(rrn, "VOID", 0);
        store.setStatus(rrn, "UNKNOWN");
        return new Outcome(rrn, "REVERSING");
    }

    public Outcome capture(String rrn, long cents) {
        return tx.execute(status -> {
            Authorisation auth = store.lockAuthorisation(rrn).orElseThrow(() -> ApiException.notFound("authorisation " + rrn));
            if ("CAPTURED".equals(auth.status()) && auth.capturedCents() == cents) {
                return new Outcome(rrn, "CAPTURED");
            }
            if (!"APPROVED".equals(auth.status()) || !"PURCHASE".equals(auth.kind())) {
                throw new ApiException(HttpStatus.CONFLICT, "not_capturable",
                        "only an approved payment can be captured (this one is " + auth.status().toLowerCase() + ")");
            }
            if (cents < 1 || cents > auth.amountCents()) {
                throw ApiException.invalid("capture_too_large", "a capture can't be more than was approved");
            }
            store.captured(rrn, cents);
            return new Outcome(rrn, "CAPTURED");
        });
    }

    /** Sends what is owed, oldest first. */
    @Scheduled(fixedDelayString = "${acquirer.reversal-every:2s}", initialDelayString = "${acquirer.reversal-every:2s}")
    public void sendOwed() {
        for (AcquirerStore.Reversal owed : store.dueReversals(20)) {
            Authorisation auth = store.authorisation(owed.rrn()).orElseThrow();
            IsoMessage advice = reversal("0420", auth);
            trace.iso(owed.rrn(), Parties.ACQUIRER, party(auth), advice);
            try {
                IsoMessage ack = network.link(auth.scheme()).send(advice, props.networkTimeout())
                        .get(props.networkTimeout().toMillis() + 500, TimeUnit.MILLISECONDS);
                if (APPROVED.equals(ack.get(39))) {
                    tx.executeWithoutResult(s -> {
                        store.reversalDone(owed.id());
                        store.setStatus(owed.rrn(), "REVERSED");
                    });
                    log.info("Reversal of {} acknowledged after {} attempts", owed.rrn(), owed.attempts() + 1);
                } else {
                    store.reversalFailed(owed.id(), "answered " + ack.get(39));
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
                store.reversalFailed(owed.id(), e.getClass().getSimpleName());
                return;   // the network is unreachable: the rest wait for the next round
            }
        }
    }

    private static String party(Authorisation auth) {
        return "MERIDIAN".equals(auth.scheme()) ? Parties.MERIDIAN : Parties.RAILNET;
    }

    /**
     * A reversal: its own trace number, the original's reference (37), and field 90 naming the original
     * (MTI, trace number, when it was sent, who sent it). No card number: the network finds the original by its reference.
     */
    IsoMessage reversal(String mti, Authorisation auth) {
        var merchant = store.merchant(auth.merchantId()).orElseThrow();
        String now = ZonedDateTime.now(clock.withZone(ZoneOffset.UTC)).format(FIELD_7);
        return new IsoMessage(mti)
                .set(3, "REFUND".equals(auth.kind()) ? "200000" : "000000")
                .set(4, String.format("%012d", auth.amountCents()))
                .set(7, now)
                .set(11, network.nextStan())
                .set(32, props.institutionId())
                .set(37, auth.rrn())
                .set(41, auth.tid())
                .set(42, merchant.mid())
                .set(49, "840")
                .set(90, "0100" + auth.stan() + auth.transmitted() + String.format("%011d", Long.parseLong(props.institutionId()))
                        + "0".repeat(11));
    }
}
