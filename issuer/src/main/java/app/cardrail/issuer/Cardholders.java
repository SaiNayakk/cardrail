package app.cardrail.issuer;

import app.cardrail.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * The cardholder's side of Kestrel Bank: signing in, seeing the card and what it was used for, and running it
 * (freeze, report lost and get a new number, activate, set controls).
 *
 * <p>Signing in is a demo shortcut: the card number, expiry and security code, as a bank's app might do when first
 * linking a card. A session is a signed token (the card, an expiry, an HMAC under a key derived from Kestrel Bank's
 * card hash key) valid for an hour; nothing is stored.
 *
 * <p>The published test cards are shared by every visitor, so they can be looked at but not changed: a visitor who
 * wants to freeze a card or report it lost opens one of their own.
 */
@Service
public class Cardholders {

    static final Duration SESSION = Duration.ofHours(1);

    public record Session(String token, UUID cardId, Instant expiresAt) {}

    public record CardView(UUID id, String last4, String kind, String status, String expiry, boolean shared, long balanceCents,
                           long availableCents, long heldCents, Long creditLimitCents) {}

    public record Transaction(String reference, String kind, String merchant, long amountCents, String status, String responseCode,
                              String responseText, Integer fraudScore, String riskReasons, boolean authenticated, boolean disputable,
                              Instant at) {}

    private final JdbcClient jdbc;
    private final IssuerStore store;
    private final CardKeys keys;
    private final CardIssuing issuing;
    private final Risk risk;
    private final Clock clock;
    private final SecretKeySpec sessionKey;

    public Cardholders(JdbcClient jdbc, IssuerStore store, CardKeys keys, CardIssuing issuing, Risk risk, IssuerProperties props,
                       Clock clock) {
        this.jdbc = jdbc;
        this.store = store;
        this.keys = keys;
        this.issuing = issuing;
        this.risk = risk;
        this.clock = clock;
        try {
            byte[] derived = MessageDigest.getInstance("SHA-256").digest(
                    (props.hashKey() + "|cardholder-sessions").getBytes(StandardCharsets.US_ASCII));
            this.sessionKey = new SecretKeySpec(derived, "HmacSHA256");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Signs in with the card itself. The same answer for every wrong detail, so it can't be used to find card numbers. */
    public Session signIn(String number, String expiryMmYy, String cvv2) {
        String pan = number == null ? "" : number.replaceAll("[ -]", "");
        String expiry = expiryMmYy == null || !expiryMmYy.matches("[0-9]{2}/[0-9]{2}") ? ""
                : expiryMmYy.substring(3) + expiryMmYy.substring(0, 2);
        var card = store.cardByPanHash(keys.panHash(pan))
                .filter(c -> c.expiry().equals(expiry))
                .filter(c -> cvv2 != null && MessageDigest.isEqual(cvv2.getBytes(StandardCharsets.US_ASCII),
                        keys.cvv2(pan, expiry).getBytes(StandardCharsets.US_ASCII)))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "wrong_card_details", "those card details don't match a Kestrel Bank card"));
        return session(card.id());
    }

    Session session(UUID cardId) {
        Instant expires = clock.instant().plus(SESSION);
        String body = cardId + "." + expires.getEpochSecond();
        return new Session(body + "." + sign(body), cardId, expires);
    }

    /** The card a session token is for, or 401. */
    public UUID cardFor(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw unauthorised();
        }
        String[] parts = authorization.substring(7).trim().split("\\.");
        if (parts.length != 3) {
            throw unauthorised();
        }
        String body = parts[0] + "." + parts[1];
        if (!MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.US_ASCII), parts[2].getBytes(StandardCharsets.US_ASCII))) {
            throw unauthorised();
        }
        if (Instant.ofEpochSecond(Long.parseLong(parts[1])).isBefore(clock.instant())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "session_expired", "your session has ended; sign in again");
        }
        return UUID.fromString(parts[0]);
    }

    public CardView card(UUID cardId) {
        record Row(UUID id, UUID accountId, String last4, String bin, String status, String expiry) {}
        Row c = jdbc.sql("select id, account_id, last4, bin, status, expiry from cards where id = ?").param(cardId)
                .query(Row.class).optional().orElseThrow(() -> ApiException.notFound("card"));
        var account = store.account(c.accountId()).orElseThrow();
        long held = store.heldCents(c.accountId());
        return new CardView(c.id(), c.last4(), account.kind(), c.status(), c.expiry().substring(2) + "/" + c.expiry().substring(0, 2),
                risk.controls(cardId).shared(), account.balanceCents(), account.availableCents(held), held, account.creditLimitCents());
    }

    public List<Transaction> transactions(UUID cardId) {
        return jdbc.sql("""
                select a.rrn, a.kind, coalesce(a.merchant, ''), a.amount_cents, a.status, a.response_code, a.fraud_score,
                       a.risk_reasons, a.authenticated, a.created_at,
                       (a.status = 'CLEARED' and a.kind = 'PURCHASE' and not exists (select 1 from disputes d where d.authorisation_id = a.id))
                from authorisations a
                where a.account_id = (select account_id from cards where id = ?) order by a.created_at desc limit 50""")
                .param(cardId)
                .query((rs, n) -> new Transaction(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5),
                        rs.getString(6), app.cardrail.iso8583.ResponseCode.describe(rs.getString(6)), (Integer) rs.getObject(7),
                        rs.getString(8), rs.getBoolean(9), rs.getBoolean(11), rs.getTimestamp(10).toInstant()))
                .list();
    }

    @Transactional
    public CardView freeze(UUID cardId, boolean frozen) {
        ownCard(cardId);
        String from = frozen ? "ACTIVE" : "BLOCKED";
        String to = frozen ? "BLOCKED" : "ACTIVE";
        if (jdbc.sql("update cards set status = ? where id = ? and status = ?").params(to, cardId, from).update() == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "not_possible", "this card can't be " + (frozen ? "frozen" : "unfrozen") + " now");
        }
        return card(cardId);
    }

    public record Replacement(CardIssuing.IssuedCard card, Session session) {}

    /** The card is lost or stolen: it stops working now, and a new number on the same account must be activated. */
    @Transactional
    public Replacement reportLost(UUID cardId, boolean stolen) {
        ownCard(cardId);
        record Row(UUID accountId, String status) {}
        Row row = jdbc.sql("select account_id, status from cards where id = ? for update").param(cardId).query(Row.class).single();
        if (!"ACTIVE".equals(row.status()) && !"BLOCKED".equals(row.status()) && !"INACTIVE".equals(row.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "not_possible", "this card was replaced already");
        }
        jdbc.sql("update cards set status = ? where id = ?").params(stolen ? "STOLEN" : "LOST", cardId).update();
        CardIssuing.IssuedCard fresh = issuing.replace(row.accountId(), cardId);
        risk.setControls(fresh.cardId(), risk.controls(cardId));
        return new Replacement(fresh, session(fresh.cardId()));
    }

    /** A new card works once its cardholder proves they have it. */
    @Transactional
    public CardView activate(UUID cardId, String cvv2) {
        String pan = keys.decrypt(store.encryptedPan(cardId).orElseThrow());
        String expiry = jdbc.sql("select expiry from cards where id = ?").param(cardId).query(String.class).single();
        if (cvv2 == null || !MessageDigest.isEqual(cvv2.getBytes(StandardCharsets.US_ASCII), keys.cvv2(pan, expiry).getBytes(StandardCharsets.US_ASCII))) {
            throw ApiException.invalid("wrong_code", "that security code doesn't match the card");
        }
        if (jdbc.sql("update cards set status = 'ACTIVE', activated_at = now() where id = ? and status = 'INACTIVE'").param(cardId).update() == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "not_possible", "this card isn't waiting to be activated");
        }
        return card(cardId);
    }

    public Risk.Controls setControls(UUID cardId, boolean onlineEnabled, Long singleLimitCents, Long dailyLimitCents, List<String> blockedMccs) {
        ownCard(cardId);
        Risk.Controls now = risk.controls(cardId);
        Risk.Controls next = new Risk.Controls(onlineEnabled, singleLimitCents, dailyLimitCents, blockedMccs, now.stepUpAlways(), false);
        risk.setControls(cardId, next);
        return next;
    }

    /** A card of the visitor's own, to freeze, lose and replace without spoiling the shared ones. Demo only. */
    public Replacement openOwn(String kind) {
        var card = "CREDIT".equals(kind) ? issuing.open("Demo visitor", "CREDIT", 0, 100_000L) : issuing.open("Demo visitor", "DEBIT", 100_000, null);
        return new Replacement(card, session(card.cardId()));
    }

    public UUID accountOf(UUID cardId) {
        return jdbc.sql("select account_id from cards where id = ?").param(cardId).query(UUID.class).single();
    }

    void ownCardOnly(UUID cardId) {
        ownCard(cardId);
    }

    /** Shared test cards are for looking at and paying with; changing them would change them for everyone. */
    private void ownCard(UUID cardId) {
        if (risk.controls(cardId).shared()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "shared_card",
                    "this is a shared test card, so it can't be changed; open a card of your own to try this");
        }
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(sessionKey);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException unauthorised() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "unauthorised", "sign in with your card first");
    }
}
