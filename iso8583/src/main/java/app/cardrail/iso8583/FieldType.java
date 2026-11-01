package app.cardrail.iso8583;

/** What characters a field may hold, using the standard's own abbreviations. */
public enum FieldType {
    /** n: digits only. */
    NUMERIC,
    /** an: letters and digits. */
    ALPHANUMERIC,
    /** ans: any printable ASCII, including spaces. */
    ALPHANUMERIC_SPECIAL,
    /** z: track 2 data, digits and the '=' or 'D' separator. */
    TRACK2,
    /** b: raw bytes. In an {@link IsoMessage} they are held as upper-case hex. */
    BINARY;

    boolean accepts(char c) {
        return switch (this) {
            case NUMERIC -> c >= '0' && c <= '9';
            case ALPHANUMERIC -> (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
            case ALPHANUMERIC_SPECIAL -> c >= 0x20 && c <= 0x7E;
            case TRACK2 -> (c >= '0' && c <= '9') || c == '=' || c == 'D';
            case BINARY -> (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F');
        };
    }
}
