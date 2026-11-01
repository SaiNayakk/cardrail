package app.cardrail.iso8583;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Iso8583CodecTest {

    private final Iso8583Codec codec = new Iso8583Codec(MessageSpec.RAILNET);

    /** The expected bytes are written out by hand, so the test doesn't trust the codec's own bitmap arithmetic. */
    @Test
    void encodesAnAuthorisationRequestByteForByte() {
        IsoMessage request = new IsoMessage("0100")
                .set(2, "4111111111111111")
                .set(3, "000000")
                .set(4, "000000001999")
                .set(11, "000001")
                .set(14, "2812")
                .set(41, "TERM0001")
                .set(49, "840");

        byte[] expected = concat(
                ascii("0100"),
                // fields 2,3,4 | 11,14 | - | - | - | 41 | 49 | -
                hex("7024000000808000"),
                ascii("16" + "4111111111111111" + "000000" + "000000001999" + "000001" + "2812" + "TERM0001" + "840"));

        assertThat(codec.encode(request)).isEqualTo(expected);
        assertThat(codec.decode(expected)).isEqualTo(request);
    }

    @Test
    void addsTheSecondaryBitmapOnlyWhenAFieldAbove64IsPresent() {
        IsoMessage echo = new IsoMessage("0800").set(7, "1102093015").set(11, "000042").set(70, "301");

        byte[] expected = concat(
                ascii("0800"),
                hex("8220000000000000"), // bit 1 (secondary follows), 7, 11
                hex("0400000000000000"), // 70
                ascii("1102093015" + "000042" + "301"));

        assertThat(codec.encode(echo)).isEqualTo(expected);
        assertThat(codec.decode(expected)).isEqualTo(echo);
    }

    @Test
    void binaryFieldsTravelAsRawBytesWithByteLengths() {
        IsoMessage message = new IsoMessage("0100").set(52, "0123456789ABCDEF").set(55, "9F2608AABBCCDD");

        byte[] encoded = codec.encode(message);

        byte[] fields = new byte[encoded.length - 12];
        System.arraycopy(encoded, 12, fields, 0, fields.length);
        assertThat(fields).isEqualTo(concat(hex("0123456789ABCDEF"), ascii("007"), hex("9F2608AABBCCDD")));
        assertThat(codec.decode(encoded)).isEqualTo(message);
    }

    @Test
    void refusesValuesThatBreakTheSpec() {
        assertThatThrownBy(() -> codec.encode(new IsoMessage("0100").set(4, "19.99")))
                .isInstanceOf(Iso8583Exception.class).hasMessageContaining("field 4");
        assertThatThrownBy(() -> codec.encode(new IsoMessage("0100").set(49, "8400")))
                .hasMessageContaining("must be 3");
        assertThatThrownBy(() -> codec.encode(new IsoMessage("0100").set(2, "41111111111111111111")))
                .hasMessageContaining("at most 19");
        assertThatThrownBy(() -> codec.encode(new IsoMessage("0100").set(8, "00000000")))
                .hasMessageContaining("isn't in this network's spec");
        assertThatThrownBy(() -> codec.encode(new IsoMessage("0100")))
                .hasMessageContaining("no fields");
    }

    @Test
    void errorsNeverEchoTheValue() {
        assertThatThrownBy(() -> codec.encode(new IsoMessage("0100").set(2, "4111x11111111111")))
                .message().doesNotContain("4111");
    }

    @Test
    void refusesTruncatedOrPaddedMessages() {
        byte[] good = codec.encode(new IsoMessage("0100").set(2, "4111111111111111").set(11, "000001"));

        byte[] truncated = new byte[good.length - 3];
        System.arraycopy(good, 0, truncated, 0, truncated.length);
        assertThatThrownBy(() -> codec.decode(truncated)).hasMessageContaining("ends inside field 11");

        assertThatThrownBy(() -> codec.decode(concat(good, ascii("X")))).hasMessageContaining("left over");
        assertThatThrownBy(() -> codec.decode(ascii("01"))).hasMessageContaining("MTI");
    }

    @Test
    void responsesEchoTheRequestAndCarryAResponseCode() {
        IsoMessage request = new IsoMessage("0100")
                .set(2, "4111111111111111").set(4, "000000001999").set(11, "000001").set(14, "2812").set(37, "000000000001");

        IsoMessage response = request.response("00");

        assertThat(response.mti()).isEqualTo("0110");
        assertThat(response.get(39)).isEqualTo("00");
        assertThat(response.get(11)).isEqualTo("000001");
        assertThat(response.has(14)).isFalse();
        assertThat(new IsoMessage("0420").responseMti()).isEqualTo("0430");
        assertThatThrownBy(() -> response.responseMti()).hasMessageContaining("is itself a response");
    }

    @Test
    void framesMessagesWithATwoByteLength() throws Exception {
        byte[] message = codec.encode(new IsoMessage("0800").set(11, "000001").set(70, "001"));
        ByteArrayOutputStream wire = new ByteArrayOutputStream();

        Framing.write(wire, message);
        Framing.write(wire, message);

        byte[] bytes = wire.toByteArray();
        assertThat(bytes[0]).isEqualTo((byte) 0);
        assertThat(bytes[1]).isEqualTo((byte) message.length);
        var in = new java.io.ByteArrayInputStream(bytes);
        assertThat(Framing.read(in)).isEqualTo(message);
        assertThat(Framing.read(in)).isEqualTo(message);
        assertThat(Framing.read(in)).isNull();
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] hex(String s) {
        return HexFormat.of().parseHex(s);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
