package app.cardrail.issuer;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * The issuer's card secrets. A card number is stored encrypted (AES-GCM) and found by its keyed hash, so the
 * database alone reveals neither. The CVV2 is derived from the card number and expiry with the card verification
 * key, the way real issuers derive it from theirs, so there is nothing to store and nothing to leak.
 */
public final class CardKeys {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec panKey;
    private final SecretKeySpec hashKey;
    private final SecretKeySpec cvk;

    public CardKeys(String panKey, String hashKey, String cvk) {
        this.panKey = new SecretKeySpec(key(panKey, "pan key"), "AES");
        this.hashKey = new SecretKeySpec(key(hashKey, "hash key"), "HmacSHA256");
        this.cvk = new SecretKeySpec(key(cvk, "card verification key"), "HmacSHA256");
    }

    private static byte[] key(String base64, String what) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException("the issuer's " + what + " isn't set");
        }
        byte[] key = Base64.getDecoder().decode(base64);
        if (key.length != 32) {
            throw new IllegalStateException("the issuer's " + what + " must be 32 bytes");
        }
        return key;
    }

    public byte[] panHash(String pan) {
        return mac(hashKey, pan);
    }

    public String cvv2(String pan, String expiryYymm) {
        byte[] mac = mac(cvk, pan + "|" + expiryYymm + "|000");
        int n = ((mac[0] & 0xFF) << 16 | (mac[1] & 0xFF) << 8 | (mac[2] & 0xFF)) % 1000;
        return String.format("%03d", n);
    }

    public byte[] encrypt(String pan) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, panKey, new GCMParameterSpec(128, iv));
            byte[] sealed = cipher.doFinal(pan.getBytes(StandardCharsets.US_ASCII));
            return ByteBuffer.allocate(12 + sealed.length).put(iv).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("couldn't encrypt a card number", e);
        }
    }

    public String decrypt(byte[] stored) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, panKey, new GCMParameterSpec(128, stored, 0, 12));
            return new String(cipher.doFinal(stored, 12, stored.length - 12), StandardCharsets.US_ASCII);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("couldn't decrypt a card number", e);
        }
    }

    private static byte[] mac(SecretKeySpec key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(data.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
