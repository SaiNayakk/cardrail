package app.cardrail.issuer;

import app.cardrail.iso8583.IsoMessage;
import app.cardrail.iso8583.Subelements;
import app.cardrail.issuer.IssuerStore.Account;
import app.cardrail.issuer.IssuerStore.Card;
import app.cardrail.issuer.IssuerStore.NewAuthorisation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Optional;

import static app.cardrail.iso8583.ResponseCode.*;

/**
 * Decides what Railnet brings Kestrel Bank, the way an issuer's host does.
 *
 * <ul>
 *   <li><b>0100, a purchase</b> (processing code 00): is this our card, is it usable, is it the real card (expiry,
 *       CVV2), is there money for it. An approval places a hold; the money leaves when the payment is cleared.</li>
 *   <li><b>0100, a refund</b> (processing code 20): is this our card. Nothing is held; the money arrives when the
 *       refund is cleared.</li>
 *   <li><b>0120, an advice</b>: Railnet approved a purchase for us while we were down (stand-in). We are bound by
 *       it, so it is recorded and held whatever the balance.</li>
 *   <li><b>0400 and 0420, reversals</b>: release the hold, all of it or down to field 95's new amount. A reversal
 *       of something we never saw is remembered, so if the original arrives late it is declined, not approved.</li>
 * </ul>
 *
 * The acquirer (field 32) and the retrieval reference (field 37) identify a payment: a repeat gets its first answer.
 */
@Service
public class Authoriser {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String AUTH_CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final String USD = "840";
    static final String PURCHASE = "PURCHASE";
    static final String REFUND = "REFUND";

    private final IssuerStore store;
    private final CardKeys keys;
    private final TransactionTemplate tx;
    private final Clock clock;

    public Authoriser(IssuerStore store, CardKeys keys, TransactionTemplate tx, Clock clock) {
        this.store = store;
        this.keys = keys;
        this.tx = tx;
        this.clock = clock;
    }

    public IsoMessage authorise(IsoMessage request) {
        for (int field : new int[] {2, 3, 4, 7, 11, 14, 32, 37, 49}) {
            if (!request.has(field)) {
                return request.response(FORMAT_ERROR);
            }
        }
        Optional<IssuerStore.Authorisation> seen = store.authorisation(request.get(32), request.get(37));
        if (seen.isPresent()) {
            return answer(request, seen.get().responseCode(), seen.get().authCode());
        }
        Amount amount = Amount.of(request);
        String processing = request.get(3).substring(0, 2);
        String kind = "20".equals(processing) ? REFUND : PURCHASE;
        if (!"00".equals(processing) && !"20".equals(processing)) {
            return decline(request, null, kind, amount, INVALID_TRANSACTION);
        }
        if (!USD.equals(amount.currency())) {
            return decline(request, null, kind, amount, INVALID_TRANSACTION);
        }
        if (amount.cents() <= 0) {
            return decline(request, null, kind, amount, INVALID_AMOUNT);
        }

        String pan = request.get(2);
        Optional<Card> found = store.cardByPanHash(keys.panHash(pan));
        if (found.isEmpty()) {
            return decline(request, null, kind, amount, INVALID_CARD);
        }
        Card card = found.get();

        if (REFUND.equals(kind)) {
            // Money back to the cardholder: a closed card's account still takes it.
            String authCode = authCode();
            store.record(new NewAuthorisation(card.id(), card.accountId(), request.get(32), request.get(37), request.get(11),
                    request.mti(), REFUND, amount.cents(), amount.currency(), request.get(18), merchant(request), APPROVED,
                    authCode, "ISSUER", "APPROVED", 0));
            return first(request);
        }

        String refusal = cardRefusal(card, request, pan);
        if (refusal != null) {
            return decline(request, card, kind, amount, refusal);
        }
        return tx.execute(status -> {
            Account account = store.lockAccount(card.accountId());
            long available = account.availableCents(store.heldCents(account.id()));
            boolean approved = amount.cents() <= available;
            String code = approved ? APPROVED : INSUFFICIENT_FUNDS;
            store.record(new NewAuthorisation(card.id(), account.id(), request.get(32), request.get(37), request.get(11),
                    request.mti(), PURCHASE, amount.cents(), amount.currency(), request.get(18), merchant(request), code,
                    approved ? authCode() : null, "ISSUER", approved ? "APPROVED" : "DECLINED", approved ? amount.cents() : 0));
            return first(request);
        });
    }

    /** An 0120: the network approved this for us while we couldn't answer. Recorded and held, whatever the balance. */
    public IsoMessage advise(IsoMessage advice) {
        if (!advice.has(2) || !advice.has(32) || !advice.has(37) || !advice.has(4)) {
            return advice.response(FORMAT_ERROR);
        }
        Amount amount = Amount.of(advice);
        boolean approved = APPROVED.equals(advice.get(39));
        store.cardByPanHash(keys.panHash(advice.get(2))).ifPresent(card -> tx.executeWithoutResult(status -> {
            boolean recorded = store.record(new NewAuthorisation(card.id(), card.accountId(), advice.get(32), advice.get(37),
                    advice.get(11), advice.mti(), PURCHASE, amount.cents(), amount.currency(), advice.get(18), merchant(advice),
                    approved ? APPROVED : advice.get(39), advice.get(38), "STAND_IN", approved ? "APPROVED" : "DECLINED",
                    approved ? amount.cents() : 0));
            if (!recorded && approved) {
                // We declined it, but our answer never reached the network, which approved it standing in.
                // The network's answer is the one the merchant got: we are bound by it.
                store.lockAuthorisation(advice.get(32), advice.get(37))
                        .filter(a -> "DECLINED".equals(a.status()) && PURCHASE.equals(a.kind()))
                        .ifPresent(a -> store.honourStandIn(a.id(), amount.cents(), advice.get(38)));
            }
        }));
        // an advice is always acknowledged: the network must stop sending it
        return advice.response(APPROVED);
    }

    /** An 0400 (asked) or 0420 (told): release what the original holds, all of it or down to field 95's amount. */
    public IsoMessage reverse(IsoMessage reversal) {
        if (!reversal.has(32) || !reversal.has(37)) {
            return reversal.response(FORMAT_ERROR);
        }
        long remaining = reversal.has(95) ? Long.parseLong(reversal.get(95).substring(0, 12)) : 0;
        boolean advice = "0420".equals(reversal.mti());
        return tx.execute(status -> {
            var original = store.lockAuthorisation(reversal.get(32), reversal.get(37));
            if (original.isEmpty()) {
                // Never seen. Remember it, so a late original is declined instead of approved and held for nothing.
                store.record(new NewAuthorisation(null, null, reversal.get(32), reversal.get(37), reversal.get(11),
                        reversal.mti(), PURCHASE, 0, USD, null, null, ORIGINAL_NOT_FOUND, null, "ISSUER", "REVERSED", 0));
                return reversal.response(advice ? APPROVED : ORIGINAL_NOT_FOUND);
            }
            var auth = original.get();
            if ("APPROVED".equals(auth.status()) && PURCHASE.equals(auth.kind()) && remaining < auth.holdCents()) {
                store.reverse(auth.id(), remaining);
            }
            return reversal.response(APPROVED);
        });
    }

    /** Why this card can't be used for this request, or null if it can. */
    private String cardRefusal(Card card, IsoMessage request, String pan) {
        switch (card.status()) {
            case "ACTIVE" -> { }
            case "LOST" -> { return LOST_CARD; }
            case "STOLEN" -> { return STOLEN_CARD; }
            default -> { return RESTRICTED_CARD; }
        }
        YearMonth expires = YearMonth.of(2000 + Integer.parseInt(card.expiry().substring(0, 2)),
                Integer.parseInt(card.expiry().substring(2)));
        if (YearMonth.now(clock.withZone(ZoneOffset.UTC)).isAfter(expires)) {
            return EXPIRED_CARD;
        }
        if (!card.expiry().equals(request.get(14))) {
            // the wrong expiry means whoever is paying doesn't have the card in front of them
            return EXPIRED_CARD;
        }
        if (request.has(48)) {
            String cvv2 = Subelements.decode(request.get(48)).get(Subelements.CVV2);
            if (cvv2 != null && !MessageDigest.isEqual(cvv2.getBytes(), keys.cvv2(pan, card.expiry()).getBytes())) {
                return CVV2_MISMATCH;
            }
        }
        return null;
    }

    private IsoMessage decline(IsoMessage request, Card card, String kind, Amount amount, String code) {
        store.record(new NewAuthorisation(card == null ? null : card.id(), card == null ? null : card.accountId(),
                request.get(32), request.get(37), request.get(11), request.mti(), kind, Math.max(amount.cents(), 0),
                amount.currency(), request.get(18), merchant(request), code, null, "ISSUER", "DECLINED", 0));
        return first(request);
    }

    /** The answer recorded for this request: ours, or the one a copy racing it got first. */
    private IsoMessage first(IsoMessage request) {
        var first = store.authorisation(request.get(32), request.get(37)).orElseThrow();
        return answer(request, first.responseCode(), first.authCode());
    }

    private static IsoMessage answer(IsoMessage request, String code, String authCode) {
        IsoMessage response = request.response(code);
        if (authCode != null) {
            response.set(38, authCode);
        }
        return response;
    }

    private static String merchant(IsoMessage m) {
        return m.has(43) ? m.get(43).trim() : null;
    }

    private static String authCode() {
        StringBuilder code = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            code.append(AUTH_CODE_CHARS.charAt(RANDOM.nextInt(AUTH_CODE_CHARS.length())));
        }
        return code.toString();
    }

    /** What the cardholder is asked for: field 6 in field 51's currency when the network converted it, else field 4. */
    record Amount(long cents, String currency) {
        static Amount of(IsoMessage m) {
            boolean converted = m.has(6) && m.has(51);
            return new Amount(Long.parseLong(converted ? m.get(6) : m.get(4)), converted ? m.get(51) : m.has(49) ? m.get(49) : USD);
        }
    }
}
