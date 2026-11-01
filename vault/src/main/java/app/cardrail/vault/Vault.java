package app.cardrail.vault;

import app.cardrail.common.ApiException;
import app.cardrail.iso8583.Luhn;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Base64;

/**
 * Card data in, tokens out. A card number is encrypted with AES-GCM before it is stored, with the token's id as
 * associated data, so a sealed card can't be moved to another token's row.
 */
@Service
public class Vault {

    static final Duration TOKEN_LIFE = Duration.ofMinutes(15);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    public record Card(String number, int expMonth, int expYear, String cvc) {}

    public record TokenView(String id, String bin, String last4, int expMonth, int expYear, String brand, Instant expiresAt) {}

    public record Revealed(String pan, String expiry, String cvc) {}

    private final JdbcClient jdbc;
    private final SecretKeySpec key;
    private final Clock clock;

    public Vault(JdbcClient jdbc, VaultProperties props, Clock clock) {
        this.jdbc = jdbc;
        if (props.key() == null || props.key().isBlank()) {
            throw new IllegalStateException("the vault's key (VAULT_KEY) isn't set");
        }
        byte[] raw = Base64.getDecoder().decode(props.key());
        if (raw.length != 32) {
            throw new IllegalStateException("the vault's key must be 32 bytes");
        }
        this.key = new SecretKeySpec(raw, "AES");
        this.clock = clock;
    }

    public TokenView tokenise(String merchantId, Card card) {
        String pan = card.number() == null ? "" : card.number().replaceAll("[ -]", "");
        if (!Luhn.valid(pan)) {
            throw ApiException.invalid("invalid_number", "the card number isn't valid");
        }
        int year = card.expYear() < 100 ? 2000 + card.expYear() : card.expYear();
        if (card.expMonth() < 1 || card.expMonth() > 12 || year > 2099) {
            throw ApiException.invalid("invalid_expiry", "the expiry date isn't valid");
        }
        if (YearMonth.of(year, card.expMonth()).isBefore(YearMonth.now(clock.withZone(ZoneOffset.UTC)))) {
            throw ApiException.invalid("expired_card", "the card has expired");
        }
        if (card.cvc() != null && !card.cvc().matches("[0-9]{3,4}")) {
            throw ApiException.invalid("invalid_cvc", "the security code isn't valid");
        }
        String id = "tok_" + random(24);
        Instant expires = clock.instant().plus(TOKEN_LIFE);
        String expiry = String.format("%02d%02d", year % 100, card.expMonth());
        jdbc.sql("""
                insert into tokens (id, merchant_id, pan_encrypted, bin, last4, expiry, cvc_encrypted, expires_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(id, merchantId, seal(id, pan), pan.substring(0, 6), pan.substring(pan.length() - 4), expiry,
                        card.cvc() == null ? null : seal(id + ":cvc", card.cvc()), java.sql.Timestamp.from(expires))
                .update();
        return new TokenView(id, pan.substring(0, 6), pan.substring(pan.length() - 4), card.expMonth(), year,
                brand(pan), expires);
    }

    /** The card behind a token, for the merchant it was made for, once. The security code is gone afterwards. */
    @Transactional
    public Revealed reveal(String tokenId, String merchantId) {
        record Row(String merchantId, byte[] panEncrypted, String expiry, byte[] cvcEncrypted, Instant expiresAt, Instant usedAt) {}
        Row row = jdbc.sql("""
                select merchant_id, pan_encrypted, expiry, cvc_encrypted, expires_at, used_at
                from tokens where id = ? for update""")
                .param(tokenId).query(Row.class).optional()
                .filter(r -> r.merchantId().equals(merchantId))
                .orElseThrow(() -> ApiException.notFound("token " + tokenId));
        if (row.usedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "token_used", "this token was used already; ask for the card again");
        }
        if (row.expiresAt().isBefore(clock.instant())) {
            throw new ApiException(HttpStatus.GONE, "token_expired", "this token has expired; ask for the card again");
        }
        jdbc.sql("update tokens set used_at = now(), cvc_encrypted = null where id = ?").param(tokenId).update();
        return new Revealed(open(tokenId, row.panEncrypted()), row.expiry(),
                row.cvcEncrypted() == null ? null : open(tokenId + ":cvc", row.cvcEncrypted()));
    }

    /** Expired tokens are deleted: a card typed in and never paid with isn't kept. */
    @Scheduled(fixedDelay = 300_000)
    public void sweep() {
        jdbc.sql("delete from tokens where expires_at < now() - interval '1 hour'").update();
    }

    static String brand(String pan) {
        if (pan.startsWith("28600")) return "kestrel";
        if (pan.startsWith("39")) return "meridian";
        return "unknown";
    }

    private byte[] seal(String associated, String plain) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(associated.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.US_ASCII));
            return ByteBuffer.allocate(12 + sealed.length).put(iv).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("couldn't seal card data", e);
        }
    }

    private String open(String associated, byte[] stored) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, stored, 0, 12));
            cipher.updateAAD(associated.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(stored, 12, stored.length - 12), StandardCharsets.US_ASCII);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("couldn't open card data", e);
        }
    }

    private static String random(int n) {
        char[] out = new char[n];
        for (int i = 0; i < n; i++) {
            out[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(out);
    }
}
