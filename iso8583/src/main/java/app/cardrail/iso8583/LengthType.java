package app.cardrail.iso8583;

/** How a field's length is known: fixed by the spec, or sent in front of it as 2 or 3 ASCII digits. */
public enum LengthType {
    FIXED(0),
    LLVAR(2),
    LLLVAR(3);

    final int prefixDigits;

    LengthType(int prefixDigits) {
        this.prefixDigits = prefixDigits;
    }
}
