package app.cardrail.iso8583;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

/**
 * Turns messages into bytes and back: the MTI as 4 ASCII digits, an 8-byte binary primary bitmap, the secondary
 * bitmap when any field above 64 is present, then each field in number order. Variable fields are preceded by
 * their length as 2 or 3 ASCII digits (in bytes for binary fields).
 */
public final class Iso8583Codec {

    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    private final MessageSpec spec;

    public Iso8583Codec(MessageSpec spec) {
        this.spec = spec;
    }

    public byte[] encode(IsoMessage message) {
        if (message.fields().isEmpty()) {
            throw new Iso8583Exception(message.mti() + " has no fields");
        }
        byte[] bitmap = new byte[message.fields().lastKey() > 64 ? 16 : 8];
        if (bitmap.length == 16) {
            setBit(bitmap, 1);
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Map.Entry<Integer, String> entry : message.fields().entrySet()) {
            FieldSpec field = spec.field(entry.getKey());
            String value = entry.getValue();
            int size = field.check(value);
            setBit(bitmap, field.number());
            if (field.lengthType() != LengthType.FIXED) {
                body.writeBytes(ascii(String.format("%0" + field.lengthType().prefixDigits + "d", size)));
            }
            body.writeBytes(field.type() == FieldType.BINARY ? HEX.parseHex(value) : ascii(value));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ascii(message.mti()));
        out.writeBytes(bitmap);
        out.writeBytes(body.toByteArray());
        return out.toByteArray();
    }

    public IsoMessage decode(byte[] bytes) {
        Reader in = new Reader(bytes);
        IsoMessage message = new IsoMessage(in.text(4, "the MTI"));
        byte[] bitmap = in.bytes(8, "the primary bitmap");
        if (isSet(bitmap, 1)) {
            byte[] both = new byte[16];
            System.arraycopy(bitmap, 0, both, 0, 8);
            System.arraycopy(in.bytes(8, "the secondary bitmap"), 0, both, 8, 8);
            bitmap = both;
        }
        for (int number = 2; number <= bitmap.length * 8; number++) {
            if (!isSet(bitmap, number)) {
                continue;
            }
            FieldSpec field = spec.field(number);
            String what = "field " + number;
            int size = field.length();
            if (field.lengthType() != LengthType.FIXED) {
                String prefix = in.text(field.lengthType().prefixDigits, what + "'s length");
                if (!prefix.chars().allMatch(Character::isDigit)) {
                    throw new Iso8583Exception(what + ": the length isn't digits");
                }
                size = Integer.parseInt(prefix);
            }
            String value = field.type() == FieldType.BINARY ? HEX.formatHex(in.bytes(size, what)) : in.text(size, what);
            field.check(value);
            message.set(number, value);
        }
        if (in.remaining() > 0) {
            throw new Iso8583Exception(in.remaining() + " bytes left over after the last field");
        }
        return message;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static void setBit(byte[] bitmap, int field) {
        bitmap[(field - 1) / 8] |= (byte) (0x80 >>> ((field - 1) % 8));
    }

    private static boolean isSet(byte[] bitmap, int field) {
        return (bitmap[(field - 1) / 8] & (0x80 >>> ((field - 1) % 8))) != 0;
    }

    private static final class Reader {
        private final byte[] bytes;
        private int position;

        Reader(byte[] bytes) {
            this.bytes = bytes;
        }

        byte[] bytes(int count, String what) {
            if (count > remaining()) {
                throw new Iso8583Exception("message ends inside " + what);
            }
            byte[] out = new byte[count];
            System.arraycopy(bytes, position, out, 0, count);
            position += count;
            return out;
        }

        String text(int count, String what) {
            byte[] raw = bytes(count, what);
            for (byte b : raw) {
                if (b < 0x20 || b > 0x7E) {
                    throw new Iso8583Exception(what + " isn't printable ASCII");
                }
            }
            return new String(raw, StandardCharsets.US_ASCII);
        }

        int remaining() {
            return bytes.length - position;
        }
    }
}
