package app.cardrail.iso8583;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * An ISO 8583 message: a message type indicator and its data elements, as text (binary fields as hex).
 * Field 1, the secondary bitmap, is never set by hand: the codec adds it when a field above 64 is present.
 */
public final class IsoMessage {

    private final String mti;
    private final TreeMap<Integer, String> fields = new TreeMap<>();

    public IsoMessage(String mti) {
        if (mti == null || !mti.matches("\\d{4}")) {
            throw new Iso8583Exception("an MTI is 4 digits");
        }
        this.mti = mti;
    }

    public String mti() {
        return mti;
    }

    public IsoMessage set(int field, String value) {
        if (field < 2 || field > 128) {
            throw new Iso8583Exception("field number must be 2..128, was " + field);
        }
        fields.put(field, Objects.requireNonNull(value, "value"));
        return this;
    }

    public String get(int field) {
        return fields.get(field);
    }

    public boolean has(int field) {
        return fields.containsKey(field);
    }

    public IsoMessage remove(int field) {
        fields.remove(field);
        return this;
    }

    /** The fields in number order. Contains card data: never log it; log {@link Masking#mask(IsoMessage)} instead. */
    public SortedMap<Integer, String> fields() {
        return Collections.unmodifiableSortedMap(fields);
    }

    /** The response's MTI: 0100 is answered by 0110, 0420 by 0430. */
    public String responseMti() {
        int function = mti.charAt(2) - '0';
        if (function % 2 != 0) {
            throw new Iso8583Exception(mti + " is itself a response");
        }
        return mti.substring(0, 2) + (function + 1) + mti.charAt(3);
    }

    /** A response that echoes the fields the standard says a response carries back unchanged. */
    public IsoMessage response(String responseCode) {
        IsoMessage response = new IsoMessage(responseMti());
        for (int field : new int[] {2, 3, 4, 7, 11, 12, 13, 32, 37, 41, 42, 49, 70}) {
            if (has(field)) {
                response.set(field, get(field));
            }
        }
        if (responseCode != null) {
            response.set(39, responseCode);
        }
        return response;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof IsoMessage other && mti.equals(other.mti) && fields.equals(other.fields);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mti, fields);
    }

    /** Masked, so it is safe to log. */
    @Override
    public String toString() {
        Map<Integer, String> masked = Masking.mask(this);
        return mti + " " + masked;
    }
}
