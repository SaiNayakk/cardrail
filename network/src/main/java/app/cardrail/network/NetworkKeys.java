package app.cardrail.network;

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
 * Railnet's secrets. One configured key gives two working keys (by hashing it with a label), so the key that
 * encrypts stored advices is never the one that hashes card numbers.
 */
@Component
public class NetworkKeys {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec sealing;
    private final SecretKeySpec hashing;

    public NetworkKeys(NetworkProperties props) {
        if (props.key() == null || props.key().isBlank()) {
            throw new IllegalStateException("Railnet's key (NETWORK_KEY) isn't set");
        }
        byte[] master = Base64.getDecoder().decode(props.key());
        if (master.length != 32) {
            throw new IllegalStateException("Railnet's key must be 32 bytes");
        }
        this.sealing = new SecretKeySpec(derive(master, "advices"), "AES");
        this.hashing = new SecretKeySpec(derive(master, "card-hash"), "HmacSHA256");
    }

    public byte[] panHash(String pan) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hashing);
            return mac.doFinal(pan.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] seal(byte[] plain) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, sealing, new GCMParameterSpec(128, iv));
            byte[] sealed = cipher.doFinal(plain);
            return ByteBuffer.allocate(12 + sealed.length).put(iv).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("couldn't seal an advice", e);
        }
    }

    public byte[] open(byte[] stored) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, sealing, new GCMParameterSpec(128, stored, 0, 12));
            return cipher.doFinal(stored, 12, stored.length - 12);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("couldn't open an advice", e);
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
