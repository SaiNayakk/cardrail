package app.cardrail.issuer;

import app.cardrail.iso8583.Luhn;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;

/** Opens accounts and issues their cards. Kestrel Bank's BINs: 286001 for debit, 286002 for credit. */
@Service
public class CardIssuing {

    public static final String DEBIT_BIN = "286001";
    public static final String CREDIT_BIN = "286002";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final IssuerStore store;
    private final CardKeys keys;
    private final IssuerBooks books;
    private final Clock clock;

    public CardIssuing(IssuerStore store, CardKeys keys, IssuerBooks books, Clock clock) {
        this.store = store;
        this.keys = keys;
        this.books = books;
        this.clock = clock;
    }

    public record IssuedCard(UUID cardholderId, UUID accountId, UUID cardId, String pan, String expiry, String cvv2) {}

    /** A new cardholder with one account and an active card on it, valid for four years. */
    @Transactional
    public IssuedCard open(String name, String kind, long balanceCents, Long creditLimitCents) {
        String pan;
        do {
            pan = pan(("DEBIT".equals(kind) ? DEBIT_BIN : CREDIT_BIN) + String.format("%09d", RANDOM.nextInt(1_000_000_000)));
        } while (store.cardByPanHash(keys.panHash(pan)).isPresent());
        YearMonth expires = YearMonth.now(clock.withZone(ZoneOffset.UTC)).plusYears(4);
        String expiry = String.format("%02d%02d", expires.getYear() % 100, expires.getMonthValue());
        return open(name, kind, balanceCents, creditLimitCents, pan, expiry, "ACTIVE", null);
    }

    /** As above, with every detail given: used for the published test cards. */
    @Transactional
    public IssuedCard open(String name, String kind, long balanceCents, Long creditLimitCents,
                           String pan, String expiry, String status, String label) {
        UUID cardholder = store.createCardholder(name);
        UUID account = store.createAccount(cardholder, kind, balanceCents, creditLimitCents);
        books.open(account, kind, balanceCents);
        UUID card = store.createCard(account, keys.panHash(pan), keys.encrypt(pan), pan, expiry, status, label);
        return new IssuedCard(cardholder, account, card, pan, expiry, keys.cvv2(pan, expiry));
    }

    /**
     * A new card on the same account for one lost or stolen: a new number and expiry, issued inactive until its
     * cardholder activates it. The account, its money and its history stay.
     */
    @Transactional
    public IssuedCard replace(UUID accountId, UUID lostCardId) {
        String bin = store.account(accountId).map(a -> "DEBIT".equals(a.kind()) ? DEBIT_BIN : CREDIT_BIN).orElseThrow();
        String pan;
        do {
            pan = pan(bin + String.format("%09d", RANDOM.nextInt(1_000_000_000)));
        } while (store.cardByPanHash(keys.panHash(pan)).isPresent());
        YearMonth expires = YearMonth.now(clock.withZone(ZoneOffset.UTC)).plusYears(4);
        String expiry = String.format("%02d%02d", expires.getYear() % 100, expires.getMonthValue());
        UUID card = store.createCard(accountId, keys.panHash(pan), keys.encrypt(pan), pan, expiry, "INACTIVE", null);
        store.markReplacement(card, lostCardId);
        UUID cardholder = store.account(accountId).orElseThrow().cardholderId();
        return new IssuedCard(cardholder, accountId, card, pan, expiry, keys.cvv2(pan, expiry));
    }

    /** A valid card number from its first 15 digits. */
    public static String pan(String fifteen) {
        return fifteen + Luhn.checkDigit(fifteen);
    }
}
