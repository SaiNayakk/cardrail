package app.cardrail.common.iso;

import java.time.Duration;

/** No response came in time. The request may still have arrived and been acted on, so the outcome is unknown. */
public class IsoTimeoutException extends RuntimeException {

    public IsoTimeoutException(String link, Duration timeout) {
        super(link + ": no response within " + timeout.toMillis() + " ms");
    }
}
