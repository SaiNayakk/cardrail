package app.cardrail.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/** API keys and ids. A secret key has 192 random bits, so a plain SHA-256 is enough to store it by. */
final class Keys {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private Keys() {
    }

    static String random(String prefix, int n) {
        char[] out = new char[n];
        for (int i = 0; i < n; i++) {
            out[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return prefix + new String(out);
    }

    static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hint(String secretKey) {
        return secretKey.substring(0, secretKey.indexOf('_', 3) + 1) + "..." + secretKey.substring(secretKey.length() - 4);
    }
}
