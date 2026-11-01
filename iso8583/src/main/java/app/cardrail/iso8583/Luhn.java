package app.cardrail.iso8583;

/** The mod-10 check digit every card number ends with (ISO/IEC 7812). */
public final class Luhn {

    private Luhn() {
    }

    public static boolean valid(String pan) {
        return pan != null && pan.matches("[0-9]{12,19}") && checkDigit(pan.substring(0, pan.length() - 1)) == pan.charAt(pan.length() - 1) - '0';
    }

    /** The digit to append to {@code partial} to make a valid number. */
    public static int checkDigit(String partial) {
        int sum = 0;
        boolean twice = true;
        for (int i = partial.length() - 1; i >= 0; i--) {
            int d = partial.charAt(i) - '0';
            if (twice) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            twice = !twice;
        }
        return (10 - sum % 10) % 10;
    }
}
