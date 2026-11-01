package app.cardrail.iso8583;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * How messages share a TCP connection: each is sent after its length as a 2-byte unsigned big-endian number,
 * the framing most card networks use. A message can be at most 65,535 bytes.
 */
public final class Framing {

    public static final int MAX_LENGTH = 0xFFFF;

    private Framing() {
    }

    public static void write(OutputStream out, byte[] message) throws IOException {
        if (message.length == 0 || message.length > MAX_LENGTH) {
            throw new Iso8583Exception("a framed message is 1.." + MAX_LENGTH + " bytes, was " + message.length);
        }
        byte[] framed = new byte[message.length + 2];
        framed[0] = (byte) (message.length >>> 8);
        framed[1] = (byte) message.length;
        System.arraycopy(message, 0, framed, 2, message.length);
        out.write(framed);
        out.flush();
    }

    /** The next message, or {@code null} if the peer closed the connection cleanly between messages. */
    public static byte[] read(InputStream in) throws IOException {
        int high = in.read();
        if (high < 0) {
            return null;
        }
        int low = in.read();
        if (low < 0) {
            throw new EOFException("connection closed inside a length prefix");
        }
        int length = (high << 8) | low;
        if (length == 0) {
            throw new Iso8583Exception("a framed message can't be empty");
        }
        byte[] message = new byte[length];
        new DataInputStream(in).readFully(message);
        return message;
    }
}
