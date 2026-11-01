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
 * Decides an authorisation request (0100) the way an issuer's host does: is this our card, is it usable, is it
 * the real card (expiry, CVV2), and is there money for it. An approval places a hold on the account; the money
 * leaves when the payment is cleared. A request seen before gets its first answer again.
 */
@Service
public class Authoriser {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String AUTH_CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final String USD = "840";

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
        String acquirer = request.get(32);
        String rrn = request.get(37);
        String stan = request.get(11);

        Optional<IssuerStore.Authorisation> seen = store.authorisation(acquirer, rrn, stan);
        if (seen.isPresent()) {
            return answer(request, seen.get().responseCode(), seen.get().authCode());
        }

        // The network converts foreign currencies: field 6 is then what the cardholder is billed, in field 51's currency.
        boolean converted = request.has(6) && request.has(51);
        String currency = converted ? request.get(51) : request.get(49);
        long amount = Long.parseLong(converted ? request.get(6) : request.get(4));
        String mti = request.mti();
        String mcc = request.get(18);
        String merchant = request.has(43) ? request.get(43).trim() : null;

        if (!request.get(3).startsWith("00")) {
            return decline(request, null, amount, currency, mcc, merchant, INVALID_TRANSACTION);
        }
        if (!USD.equals(currency)) {
            return decline(request, null, amount, currency, mcc, merchant, INVALID_TRANSACTION);
        }
        if (amount <= 0) {
            return decline(request, null, amount, currency, mcc, merchant, INVALID_AMOUNT);
        }

        String pan = request.get(2);
        Optional<Card> found = store.cardByPanHash(keys.panHash(pan));
        if (found.isEmpty()) {
            return decline(request, null, amount, currency, mcc, merchant, INVALID_CARD);
        }
        Card card = found.get();
        String refusal = cardRefusal(card, request, pan);
        if (refusal != null) {
            return decline(request, card, amount, currency, mcc, merchant, refusal);
        }

        return tx.execute(status -> {
            Account account = store.lockAccount(card.accountId());
            long available = account.availableCents(store.heldCents(account.id()));
            String code = amount > available ? INSUFFICIENT_FUNDS : APPROVED;
            String authCode = APPROVED.equals(code) ? authCode() : null;
            boolean recorded = store.record(new NewAuthorisation(card.id(), account.id(), acquirer, rrn, stan, mti, amount,
                    currency, mcc, merchant, code, authCode, APPROVED.equals(code) ? "APPROVED" : "DECLINED",
                    APPROVED.equals(code) ? amount : 0));
            if (!recorded) {
                status.setRollbackOnly();
                var first = store.authorisation(acquirer, rrn, stan).orElseThrow();
                return answer(request, first.responseCode(), first.authCode());
            }
            return answer(request, code, authCode);
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

    private IsoMessage decline(IsoMessage request, Card card, long amount, String currency, String mcc,
                               String merchant, String code) {
        store.record(new NewAuthorisation(card == null ? null : card.id(), card == null ? null : card.accountId(),
                request.get(32), request.get(37), request.get(11), request.mti(), amount, currency, mcc, merchant,
                code, null, "DECLINED", 0));
        var first = store.authorisation(request.get(32), request.get(37), request.get(11)).orElseThrow();
        return answer(request, first.responseCode(), first.authCode());
    }

    private static IsoMessage answer(IsoMessage request, String code, String authCode) {
        IsoMessage response = request.response(code);
        if (authCode != null) {
            response.set(38, authCode);
        }
        return response;
    }

    private static String authCode() {
        StringBuilder code = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            code.append(AUTH_CODE_CHARS.charAt(RANDOM.nextInt(AUTH_CODE_CHARS.length())));
        }
        return code.toString();
    }
}
