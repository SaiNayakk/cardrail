package app.cardrail.iso8583;

import java.util.Map;
import java.util.TreeMap;

/**
 * What a message looks like in a log or the live trace. Card numbers keep their BIN and last four (as on a
 * receipt, PCI DSS 3.4); expiry dates, track data, PINs, chip data and MACs are never shown.
 */
public final class Masking {

    private Masking() {
    }

    public static Map<Integer, String> mask(IsoMessage message) {
        Map<Integer, String> masked = new TreeMap<>();
        message.fields().forEach((field, value) -> masked.put(field, mask(field, value)));
        return masked;
    }

    public static String mask(int field, String value) {
        return switch (field) {
            case 2 -> pan(value);
            case 14 -> "****";
            case 35 -> {
                int separator = Math.max(value.indexOf('='), value.indexOf('D'));
                yield (separator < 0 ? pan(value) : pan(value.substring(0, separator))) + " [rest of track hidden]";
            }
            case 52, 55, 128 -> "[" + value.length() / 2 + " bytes hidden]";
            default -> value;
        };
    }

    public static String pan(String pan) {
        if (pan.length() < 13) {
            return "*".repeat(pan.length());
        }
        return pan.substring(0, 6) + "*".repeat(pan.length() - 10) + pan.substring(pan.length() - 4);
    }
}
