package app.cardrail.closedloop;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Meridian's card secrets, three working keys from one configured key. Like American Express, its cards carry a
 * four-digit security code (the CID) and are fifteen digits long.
 */
@Component
public class MeridianKeys {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec sealing;
    private final SecretKeySpec hashing;
    private final SecretKeySpec cid;

    public MeridianKeys(MeridianApplication.Properties props) {
        if (props.key() == null || props.key().isBlank()) {
            throw new IllegalStateException("Meridian's key (MERIDIAN_KEY) isn't set");
        }
        byte[] master = Base64.getDecoder().decode(props.key());
        if (master.length != 32) {
            throw new IllegalStateException("Meridian's key must be 32 bytes");
        }
        this.sealing = new SecretKeySpec(derive(master, "pan"), "AES");
        this.hashing = new SecretKeySpec(derive(master, "pan-hash"), "HmacSHA256");
        this.cid = new SecretKeySpec(derive(master, "cid"), "HmacSHA256");
    }

    public byte[] panHash(String pan) {
        return mac(hashing, pan);
    }

    /** The four-digit card identification number, derived from the card, never stored. */
    public String cid(String pan, String expiry) {
        byte[] m = mac(cid, pan + "|" + expiry);
        int n = ((m[0] & 0xFF) << 16 | (m[1] & 0xFF) << 8 | (m[2] & 0xFF)) % 10_000;
        return String.format("%04d", n);
    }

    public byte[] encrypt(String pan) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, sealing, new GCMParameterSpec(128, iv));
            byte[] sealed = cipher.doFinal(pan.getBytes(StandardCharsets.US_ASCII));
            return ByteBuffer.allocate(12 + sealed.length).put(iv).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String decrypt(byte[] stored) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, sealing, new GCMParameterSpec(128, stored, 0, 12));
            return new String(cipher.doFinal(stored, 12, stored.length - 12), StandardCharsets.US_ASCII);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
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

    private static byte[] derive(byte[] master, String label) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(master);
            sha.update(label.getBytes(StandardCharsets.US_ASCII));
            return sha.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
