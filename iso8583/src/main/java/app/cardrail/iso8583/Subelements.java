package app.cardrail.iso8583;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Field 48 as a list of subelements, each a 2-digit tag, a 2-digit length and the value, as in Mastercard's
 * DE 48. Railnet uses tag 92 for the card security code (CVV2) on card-not-present payments, 42 for the
 * e-commerce security level (210 not authenticated, 212 authenticated), and 43 and 44 for step-up's proof.
 */
public final class Subelements {

    public static final String CVV2 = "92";
    public static final String ECOMMERCE_SECURITY = "42";
    /** The cardholder authentication value from the issuer's ACS, proof that step-up happened (like Visa's CAVV). */
    public static final String AUTHENTICATION_VALUE = "43";
    /** The directory server's id for that authentication. */
    public static final String AUTHENTICATION_ID = "44";

    private Subelements() {
    }

    public static String encode(Map<String, String> subelements) {
        StringBuilder out = new StringBuilder();
        subelements.forEach((tag, value) -> {
            if (!tag.matches("[0-9]{2}") || value.length() > 99) {
                throw new Iso8583Exception("subelement " + tag + " doesn't fit a 2-digit tag and length");
            }
            out.append(tag).append(String.format("%02d", value.length())).append(value);
        });
        return out.toString();
    }

    public static Map<String, String> decode(String field48) {
        Map<String, String> out = new LinkedHashMap<>();
        int i = 0;
        while (i < field48.length()) {
            if (i + 4 > field48.length()) {
                throw new Iso8583Exception("field 48 ends inside a subelement header");
            }
            String tag = field48.substring(i, i + 2);
            String length = field48.substring(i + 2, i + 4);
            if (!tag.chars().allMatch(Character::isDigit) || !length.chars().allMatch(Character::isDigit)) {
                throw new Iso8583Exception("field 48 has a subelement header that isn't digits");
            }
            int end = i + 4 + Integer.parseInt(length);
            if (end > field48.length()) {
                throw new Iso8583Exception("field 48 ends inside subelement " + tag);
            }
            out.put(tag, field48.substring(i + 4, end));
            i = end;
        }
        return out;
    }
}
