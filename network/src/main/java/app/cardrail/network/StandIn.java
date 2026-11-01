package app.cardrail.network;

import app.cardrail.iso8583.IsoMessage;
import app.cardrail.iso8583.Iso8583Codec;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.YearMonth;
import java.time.ZoneOffset;

import static app.cardrail.iso8583.ResponseCode.*;

/**
 * Stand-in processing (STIP): what Railnet decides for an issuer that can't answer, within limits the issuer set.
 * The network can't see the cardholder's balance or check the CVV2, so it only takes small, plausible risks:
 * a card number that passes Luhn and hasn't expired, under a per-payment limit and a per-card daily count and
 * total. What it approves, the issuer is bound by: it is told by an advice (0120) as soon as it answers again.
 */
@Component
public class StandIn {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final NetworkProperties props;
    private final NetworkStore store;
    private final NetworkKeys keys;
    private final Iso8583Codec codec;

    public StandIn(NetworkProperties props, NetworkStore store, NetworkKeys keys, Iso8583Codec codec) {
        this.props = props;
        this.store = store;
        this.keys = keys;
        this.codec = codec;
    }

    /** The response the network gives for the issuer; an approval also queues the advice the issuer is owed. */
    public IsoMessage decide(IsoMessage request, String issuerId) {
        NetworkProperties.Endpoint limits = props.issuers().get(issuerId);
        if (limits == null || !limits.standsIn() || !request.get(3).startsWith("00")) {
            return request.response(ISSUER_UNAVAILABLE);
        }
        if (!app.cardrail.iso8583.Luhn.valid(request.get(2))) {
            return request.response(INVALID_CARD);
        }
        if (request.has(14) && expired(request.get(14))) {
            return request.response(EXPIRED_CARD);
        }
        long amount = Long.parseLong(request.get(4));
        var used = store.standInToday(keys.panHash(request.get(2)));
        boolean withinLimits = amount <= limits.standInMaxCents()
                && (limits.standInDailyCount() == null || used.count() < limits.standInDailyCount())
                && (limits.standInDailyCents() == null || used.cents() + amount <= limits.standInDailyCents());
        if (!withinLimits) {
            // above what the issuer lets us risk: the shopper can try again when the issuer is back
            return request.response(ISSUER_UNAVAILABLE);
        }
        IsoMessage approval = request.response(APPROVED).set(38, code());
        IsoMessage advice = Switch.copy(request).set(39, APPROVED).set(38, approval.get(38))
                .set(33, props.institutionId()).set(100, issuerId);
        advice = Switch.copyAs("0120", advice);
        store.enqueueAdvice(issuerId, request.get(32), request.get(37), "0120", keys.seal(codec.encode(advice)));
        return approval;
    }

    private static boolean expired(String yymm) {
        if (!yymm.matches("[0-9]{4}")) {
            return true;
        }
        int month = Integer.parseInt(yymm.substring(2));
        if (month < 1 || month > 12) {
            return true;
        }
        return YearMonth.now(ZoneOffset.UTC).isAfter(YearMonth.of(2000 + Integer.parseInt(yymm.substring(0, 2)), month));
    }

    private static String code() {
        StringBuilder code = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            code.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        return code.toString();
    }
}
