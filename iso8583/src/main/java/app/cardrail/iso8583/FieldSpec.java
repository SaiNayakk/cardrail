package app.cardrail.iso8583;

/**
 * One data element. {@code length} is the exact length of a fixed field and the most a variable one may hold:
 * characters for text fields, bytes for binary ones.
 */
public record FieldSpec(int number, String name, FieldType type, LengthType lengthType, int length) {

    public FieldSpec {
        if (number < 2 || number > 128) {
            throw new IllegalArgumentException("field number must be 2..128, was " + number);
        }
        int max = lengthType == LengthType.LLVAR ? 99 : lengthType == LengthType.LLLVAR ? 999 : Integer.MAX_VALUE;
        if (length < 1 || length > max) {
            throw new IllegalArgumentException("field " + number + ": length " + length + " doesn't fit " + lengthType);
        }
    }

    /** Checks a value as held in a message (hex for binary fields), and returns its length in characters or bytes. */
    int check(String value) {
        if (type == FieldType.BINARY && value.length() % 2 != 0) {
            throw new Iso8583Exception("field " + number + " (" + name + "): hex has an odd number of digits");
        }
        for (int i = 0; i < value.length(); i++) {
            if (!type.accepts(value.charAt(i))) {
                throw new Iso8583Exception("field " + number + " (" + name + "): character " + i + " isn't allowed in " + type);
            }
        }
        int size = type == FieldType.BINARY ? value.length() / 2 : value.length();
        if (lengthType == LengthType.FIXED ? size != length : size > length) {
            throw new Iso8583Exception("field " + number + " (" + name + "): length " + size
                    + (lengthType == LengthType.FIXED ? " but must be " : " but at most ") + length);
        }
        return size;
    }
}
