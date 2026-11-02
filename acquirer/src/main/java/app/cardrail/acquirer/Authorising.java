package app.cardrail.acquirer;

import app.cardrail.acquirer.AcquirerStore.Merchant;
import app.cardrail.common.ApiException;
import app.cardrail.common.iso.IsoTimeoutException;
import app.cardrail.common.trace.TraceReporter;
import app.cardrail.iso8583.IsoMessage;
import app.cardrail.iso8583.ResponseCode;
import app.cardrail.iso8583.Subelements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static app.cardrail.iso8583.ResponseCode.APPROVED;

/**
 * Turns a payment (or a refund) into an ISO 8583 authorisation request (0100), sends it to Railnet and reads the
 * answer. An answer that never comes leaves the authorisation UNKNOWN: the money may be held at the issuer, so it
 * is never reported as declined, and a reversal (0420) is owed to the network until it acknowledges one.
 */
@Service
public class Authorising {

    private static final Logger log = LoggerFactory.getLogger(Authorising.class);
    private static final DateTimeFormatter FIELD_7 = DateTimeFormatter.ofPattern("MMddHHmmss");
    private static final DateTimeFormatter FIELD_12 = DateTimeFormatter.ofPattern("HHmmss");
    private static final DateTimeFormatter FIELD_13 = DateTimeFormatter.ofPattern("MMdd");

    /** A purchase, or (with {@code originalRrn}) a refund of one. */
    public record Request(String mid, String tid, String pan, String expiry, String cvv2, long amountCents,
                          String currency, String rrn, String originalRrn, String authenticationId, String authenticationValue) {

        public Request(String mid, String tid, String pan, String expiry, String cvv2, long amountCents, String currency, String rrn,
                       String originalRrn) {
            this(mid, tid, pan, expiry, cvv2, amountCents, currency, rrn, originalRrn, null, null);
        }

        boolean refund() {
            return originalRrn != null;
        }
    }

    public record Result(String rrn, String stan, String status, String responseCode, String responseText, String authCode) {}

    private final AcquirerProperties props;
    private final AcquirerStore store;
    private final NetworkLink network;
    private final TraceReporter trace;
    private final Clock clock;

    public Authorising(AcquirerProperties props, AcquirerStore store, NetworkLink network, TraceReporter trace, Clock clock) {
        this.props = props;
        this.store = store;
        this.network = network;
        this.trace = trace;
        this.clock = clock;
    }

    public Result authorise(Request r) {
        Merchant merchant = store.merchant(r.mid()).orElseThrow(() -> ApiException.notFound("merchant " + r.mid()));
        if (!"ACTIVE".equals(merchant.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "merchant_suspended", "this merchant can't take payments");
        }
        String tid = r.tid() != null ? r.tid() : store.terminals(merchant.id()).stream().findFirst()
                .orElseThrow(() -> ApiException.invalid("no_terminal", "this merchant has no terminal"));
        if (!store.terminalBelongs(tid, merchant.id())) {
            throw ApiException.invalid("unknown_terminal", "terminal " + tid + " isn't this merchant's");
        }
        if (!"USD".equals(r.currency())) {
            throw ApiException.invalid("unsupported_currency", "Railpay Acquiring takes payments in USD");
        }

        ZonedDateTime now = ZonedDateTime.now(clock.withZone(ZoneOffset.UTC));
        String stan = network.nextStan();
        String transmitted = now.format(FIELD_7);
        if (r.refund()) {
            var original = store.authorisation(r.originalRrn())
                    .filter(a -> a.merchantId().equals(merchant.id()) && "PURCHASE".equals(a.kind()))
                    .orElseThrow(() -> ApiException.notFound("payment " + r.originalRrn()));
            if (!"CAPTURED".equals(original.status())) {
                throw ApiException.invalid("not_captured", "only a captured payment can be refunded; void it instead");
            }
            if (store.refundedCents(r.originalRrn()) + r.amountCents() > original.capturedCents()) {
                throw ApiException.invalid("refund_too_large", "that would refund more than was captured");
            }
        }
        if (!store.pending(merchant.id(), tid, r.rrn(), stan, transmitted, r.pan(), r.amountCents(), "840",
                r.refund() ? "REFUND" : "PURCHASE", r.originalRrn())) {
            throw new ApiException(HttpStatus.CONFLICT, "duplicate_reference", "reference " + r.rrn() + " was used before");
        }

        Map<String, String> field48 = new LinkedHashMap<>();
        boolean authenticated = r.authenticationValue() != null && r.authenticationId() != null;
        // channel encrypted; the cardholder authenticated by their bank (212) or not (210)
        field48.put(Subelements.ECOMMERCE_SECURITY, authenticated ? "212" : "210");
        if (authenticated) {
            field48.put(Subelements.AUTHENTICATION_VALUE, r.authenticationValue());
            field48.put(Subelements.AUTHENTICATION_ID, r.authenticationId());
        }
        if (r.cvv2() != null && !r.refund()) {
            field48.put(Subelements.CVV2, r.cvv2());
        }
        IsoMessage request = new IsoMessage("0100")
                .set(2, r.pan())
                .set(3, r.refund() ? "200000" : "000000")          // 00 purchase, 20 refund; from/to the default account
                .set(4, String.format("%012d", r.amountCents()))
                .set(7, transmitted)
                .set(11, stan)
                .set(12, now.format(FIELD_12))
                .set(13, now.format(FIELD_13))
                .set(14, r.expiry())
                .set(18, merchant.mcc())
                .set(22, "810")                                    // e-commerce, no PIN
                .set(25, "59")                                     // e-commerce order
                .set(32, props.institutionId())
                .set(37, r.rrn())
                .set(41, tid)
                .set(42, merchant.mid())
                .set(43, nameAndLocation(merchant))
                .set(48, Subelements.encode(field48))
                .set(49, "840");

        trace.iso(r.rrn(), Parties.ACQUIRER, Parties.RAILNET, request);
        try {
            IsoMessage response = network.link().send(request, props.networkTimeout()).get();
            String code = response.get(39);
            String status = APPROVED.equals(code) ? "APPROVED" : "DECLINED";
            store.answered(r.rrn(), code, response.get(38), status);
            return new Result(r.rrn(), stan, status, code, ResponseCode.describe(code), response.get(38));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            boolean unknown = cause instanceof IsoTimeoutException;
            log.warn("Authorisation {} got no answer: {}", r.rrn(), cause.getMessage());
            if (unknown) {
                // It may have been approved and money held. Tell the network it is reversed, until it hears us.
                store.answered(r.rrn(), null, null, "UNKNOWN");
                store.oweReversal(r.rrn(), "NO_ANSWER", 0);
                return new Result(r.rrn(), stan, "UNKNOWN", null, "No answer from the network; it is being reversed", null);
            }
            // never sent: nothing can be held anywhere
            store.answered(r.rrn(), ResponseCode.ISSUER_UNAVAILABLE, null, "DECLINED");
            return new Result(r.rrn(), stan, "DECLINED", ResponseCode.ISSUER_UNAVAILABLE, "Network unreachable", null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Field 43: the name (25), city (13) and country (2) the cardholder sees on their statement. */
    static String nameAndLocation(Merchant m) {
        return fit(m.name(), 25) + fit(m.city(), 13) + fit(m.country(), 2);
    }

    private static String fit(String s, int width) {
        String ascii = s.replaceAll("[^\\x20-\\x7E]", "?");
        return ascii.length() >= width ? ascii.substring(0, width) : ascii + " ".repeat(width - ascii.length());
    }
}
