package app.cardrail.iso8583;

/** A message that can't be built, encoded or decoded. The text never contains a field's value. */
public class Iso8583Exception extends RuntimeException {

    public Iso8583Exception(String message) {
        super(message);
    }
}
