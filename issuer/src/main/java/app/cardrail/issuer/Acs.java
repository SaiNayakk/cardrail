package app.cardrail.issuer;

import app.cardrail.common.ApiException;
import app.cardrail.common.trace.TraceReporter;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Kestrel Bank's access control server: step-up, as in 3-D Secure. Railnet's directory server asks it to
 * authenticate a cardholder for a payment; the cardholder confirms with the one-time code their app shows, or by
 * approving in the app. The result is an authentication value (an HMAC of the authentication, the card number and
 * the amount under a key only Kestrel Bank holds), which the merchant's next authorisation carries in field 48. When
 * it arrives, Kestrel checks it and that it hasn't been used before, and skips the step-up it asked for.
 */
@Service
public class Acs {

    static final Duration LIFETIME = Duration.ofMinutes(5);
    static final int MAX_ATTEMPTS = 3;
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Started(String id, String status, String challengePath) {}

    public record Result(String id, String status, String value) {}

    public record Pending(String id, long amountCents, String merchant, String otp, Instant expiresAt) {}

    private final JdbcClient jdbc;
    private final IssuerStore store;
    private final CardKeys keys;
    private final TransactionTemplate tx;
    private final TraceReporter trace;
    private final SecretKeySpec valueKey;

    public Acs(JdbcClient jdbc, IssuerStore store, CardKeys keys, IssuerProperties props, TransactionTemplate tx, TraceReporter trace) {
        this.jdbc = jdbc;
        this.store = store;
        this.keys = keys;
        this.tx = tx;
        this.trace = trace;
        // a key of its own, derived from the card verification key, so an authentication value can't be made from a CVV2
        this.valueKey = new SecretKeySpec(sha256(Base64.getDecoder().decode(props.cvk()), "acs"), "HmacSHA256");
    }

    /** The directory server asks: authenticate this cardholder for this payment. */
    public Started start(String pan, String expiry, long amountCents, String merchant, String reference) {
        var card = store.cardByPanHash(keys.panHash(pan)).filter(c -> c.expiry().equals(expiry))
                .orElseThrow(() -> ApiException.invalid("unknown_card", "Kestrel Bank doesn't know this card"));
        String id = "acs_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String otp = String.format("%06d", RANDOM.nextInt(1_000_000));
        jdbc.sql("""
                insert into authentications (id, card_id, amount_cents, merchant, otp, status, expires_at)
                values (?, ?, ?, ?, ?, 'PENDING', ?)""")
                .params(id, card.id(), amountCents, merchant, otp, java.sql.Timestamp.from(Instant.now().plus(LIFETIME))).update();
        if (reference != null) {
            trace.note(reference, Parties.KESTREL, Parties.KESTREL, "Step-up: the cardholder is asked to confirm "
                    + dollars(amountCents) + " in the Kestrel Bank app", Map.of("authentication", id));
        }
        return new Started(id, "C", "/acs/challenge/" + id);
    }

    /** The cardholder types the code from the app. Three wrong codes and it fails. */
    public Result answer(String id, String otp) {
        return tx.execute(s -> {
            var a = lock(id);
            if (!"PENDING".equals(a.status())) {
                return new Result(id, a.status(), null);
            }
            if (a.expiresAt().isBefore(Instant.now())) {
                return decide(a, false);
            }
            if (otp != null && MessageDigest.isEqual(otp.getBytes(StandardCharsets.US_ASCII), a.otp().getBytes(StandardCharsets.US_ASCII))) {
                return decide(a, true);
            }
            int attempts = a.attempts() + 1;
            jdbc.sql("update authentications set attempts = ? where id = ?").params(attempts, id).update();
            if (attempts >= MAX_ATTEMPTS) {
                return decide(a, false);
            }
            throw ApiException.invalid("wrong_code", "that code isn't right; " + (MAX_ATTEMPTS - attempts) + " tries left");
        });
    }

    /** The cardholder approves (or refuses) in the app itself. Only for their own card. */
    public Result decideInApp(UUID cardId, String id, boolean approve) {
        return tx.execute(s -> {
            var a = lock(id);
            if (!a.cardId().equals(cardId)) {
                throw ApiException.notFound("authentication " + id);
            }
            if (!"PENDING".equals(a.status())) {
                return new Result(id, a.status(), null);
            }
            return decide(a, approve && a.expiresAt().isAfter(Instant.now()));
        });
    }

    /** The directory server asks how it ended. The value is given only once confirmed. */
    public Result result(String id) {
        var a = jdbc.sql(ROW + " where id = ?").param(id).query(Row.class).optional()
                .orElseThrow(() -> ApiException.notFound("authentication " + id));
        return new Result(id, a.status(), "Y".equals(a.status()) ? a.value() : null);
    }

    public List<Pending> pending(UUID cardId) {
        return jdbc.sql("""
                select id, amount_cents, merchant, otp, expires_at from authentications
                where card_id = ? and status = 'PENDING' and expires_at > now() order by created_at desc""")
                .param(cardId).query(Pending.class).list();
    }

    public Optional<Map<String, Object>> challenge(String id) {
        return jdbc.sql(ROW + " where id = ?").param(id).query(Row.class).optional()
                .map(a -> Map.of("id", a.id(), "amountCents", a.amountCents(), "merchant", a.merchant(), "status", a.status()));
    }

    /**
     * An authorisation carrying an authentication value: true if it is the one Kestrel made for this card, for at
     * least this amount, and hasn't been used before. Using it spends it.
     */
    public boolean verify(UUID cardId, String pan, long amountCents, String id, String value) {
        if (id == null || value == null) {
            return false;
        }
        return Boolean.TRUE.equals(tx.execute(s -> {
            var found = jdbc.sql(ROW + " where id = ? for update").param(id).query(Row.class).optional();
            if (found.isEmpty()) {
                return false;
            }
            var a = found.get();
            boolean good = "Y".equals(a.status()) && a.usedAt() == null && a.cardId().equals(cardId) && amountCents <= a.amountCents()
                    && MessageDigest.isEqual(value.getBytes(StandardCharsets.US_ASCII),
                    value(a.id(), pan, a.amountCents()).getBytes(StandardCharsets.US_ASCII));
            if (good) {
                jdbc.sql("update authentications set used_at = now() where id = ?").param(id).update();
            }
            return good;
        }));
    }

    record Row(String id, UUID cardId, long amountCents, String merchant, String otp, int attempts, String status, String value,
               Instant expiresAt, Instant usedAt) {}

    private static final String ROW = "select id, card_id, amount_cents, merchant, otp, attempts, status, value, expires_at, used_at from authentications";

    private Row lock(String id) {
        return jdbc.sql(ROW + " where id = ? for update").param(id).query(Row.class).optional()
                .orElseThrow(() -> ApiException.notFound("authentication " + id));
    }

    private Result decide(Row a, boolean yes) {
        String value = null;
        if (yes) {
            String pan = keys.decrypt(store.encryptedPan(a.cardId()).orElseThrow());
            value = value(a.id(), pan, a.amountCents());
        }
        jdbc.sql("update authentications set status = ?, value = ?, decided_at = now() where id = ?")
                .params(yes ? "Y" : "N", value, a.id()).update();
        return new Result(a.id(), yes ? "Y" : "N", value);
    }

    private String value(String id, String pan, long amountCents) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(valueKey);
            byte[] full = mac.doFinal((id + "|" + pan + "|" + amountCents).getBytes(StandardCharsets.US_ASCII));
            // 28 characters, the length of a Visa CAVV in base64
            return Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(full, 20)).substring(0, 28);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha256(byte[] key, String label) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(key);
            sha.update(label.getBytes(StandardCharsets.US_ASCII));
            return sha.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String dollars(long cents) {
        return String.format("$%d.%02d", cents / 100, cents % 100);
    }

    static ApiException expired() {
        return new ApiException(HttpStatus.GONE, "expired", "this confirmation has expired; start the payment again");
    }
}
