package app.cardrail.iso8583;

import java.util.Map;
import java.util.TreeMap;

import static app.cardrail.iso8583.FieldType.*;
import static app.cardrail.iso8583.LengthType.*;

/** The fields a network uses and how each is laid out. A field missing from the spec can't be sent or received. */
public final class MessageSpec {

    /**
     * Railnet's dialect: ASCII text fields, a binary bitmap, binary PIN, chip and MAC fields. The field numbers,
     * names and lengths follow ISO 8583:1987, as Visa's and Mastercard's dialects do.
     */
    public static final MessageSpec RAILNET = new MessageSpec(
            new FieldSpec(2, "Primary account number", NUMERIC, LLVAR, 19),
            new FieldSpec(3, "Processing code", NUMERIC, FIXED, 6),
            new FieldSpec(4, "Amount, transaction", NUMERIC, FIXED, 12),
            new FieldSpec(5, "Amount, settlement", NUMERIC, FIXED, 12),
            new FieldSpec(6, "Amount, cardholder billing", NUMERIC, FIXED, 12),
            new FieldSpec(7, "Transmission date and time", NUMERIC, FIXED, 10),
            new FieldSpec(9, "Conversion rate, settlement", NUMERIC, FIXED, 8),
            new FieldSpec(10, "Conversion rate, cardholder billing", NUMERIC, FIXED, 8),
            new FieldSpec(11, "System trace audit number", NUMERIC, FIXED, 6),
            new FieldSpec(12, "Time, local transaction", NUMERIC, FIXED, 6),
            new FieldSpec(13, "Date, local transaction", NUMERIC, FIXED, 4),
            new FieldSpec(14, "Date, expiration", NUMERIC, FIXED, 4),
            new FieldSpec(15, "Date, settlement", NUMERIC, FIXED, 4),
            new FieldSpec(18, "Merchant category code", NUMERIC, FIXED, 4),
            new FieldSpec(19, "Acquiring institution country code", NUMERIC, FIXED, 3),
            new FieldSpec(22, "Point of service entry mode", NUMERIC, FIXED, 3),
            new FieldSpec(25, "Point of service condition code", NUMERIC, FIXED, 2),
            new FieldSpec(32, "Acquiring institution id", NUMERIC, LLVAR, 11),
            new FieldSpec(33, "Forwarding institution id", NUMERIC, LLVAR, 11),
            new FieldSpec(35, "Track 2 data", TRACK2, LLVAR, 37),
            new FieldSpec(37, "Retrieval reference number", ALPHANUMERIC, FIXED, 12),
            new FieldSpec(38, "Authorization id response", ALPHANUMERIC, FIXED, 6),
            new FieldSpec(39, "Response code", ALPHANUMERIC, FIXED, 2),
            new FieldSpec(41, "Card acceptor terminal id", ALPHANUMERIC_SPECIAL, FIXED, 8),
            new FieldSpec(42, "Card acceptor id", ALPHANUMERIC_SPECIAL, FIXED, 15),
            new FieldSpec(43, "Card acceptor name and location", ALPHANUMERIC_SPECIAL, FIXED, 40),
            new FieldSpec(48, "Additional data, private", ALPHANUMERIC_SPECIAL, LLLVAR, 999),
            new FieldSpec(49, "Currency code, transaction", NUMERIC, FIXED, 3),
            new FieldSpec(50, "Currency code, settlement", NUMERIC, FIXED, 3),
            new FieldSpec(51, "Currency code, cardholder billing", NUMERIC, FIXED, 3),
            new FieldSpec(52, "PIN data", BINARY, FIXED, 8),
            new FieldSpec(54, "Additional amounts", ALPHANUMERIC_SPECIAL, LLLVAR, 120),
            new FieldSpec(55, "ICC data", BINARY, LLLVAR, 255),
            new FieldSpec(70, "Network management information code", NUMERIC, FIXED, 3),
            new FieldSpec(90, "Original data elements", NUMERIC, FIXED, 42),
            new FieldSpec(95, "Replacement amounts", ALPHANUMERIC_SPECIAL, FIXED, 42),
            new FieldSpec(100, "Receiving institution id", NUMERIC, LLVAR, 11),
            new FieldSpec(102, "Account id 1", ALPHANUMERIC_SPECIAL, LLVAR, 28),
            new FieldSpec(128, "Message authentication code", BINARY, FIXED, 8));

    private final Map<Integer, FieldSpec> fields = new TreeMap<>();

    public MessageSpec(FieldSpec... specs) {
        for (FieldSpec spec : specs) {
            if (fields.put(spec.number(), spec) != null) {
                throw new IllegalArgumentException("field " + spec.number() + " is defined twice");
            }
        }
    }

    public FieldSpec field(int number) {
        FieldSpec spec = fields.get(number);
        if (spec == null) {
            throw new Iso8583Exception("field " + number + " isn't in this network's spec");
        }
        return spec;
    }
}
