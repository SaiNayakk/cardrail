package app.cardrail.common.trace;

import app.cardrail.iso8583.IsoMessage;
import app.cardrail.iso8583.Masking;
import app.cardrail.common.InternalKeyFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * Tells the trace service what a party sent or received, for the live trace. It never slows a payment down:
 * reports are sent in the background, at most 64 at a time, and dropped (counted) when the trace service is
 * slow or down. Card data is masked before it leaves the party.
 */
public final class TraceReporter {

    private static final Logger log = LoggerFactory.getLogger(TraceReporter.class);

    private final String party;
    private final URI endpoint;
    private final String internalKey;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private final Semaphore inFlight = new Semaphore(64);
    private volatile long dropped;

    /** {@code traceUrl} empty means reporting is off. */
    public TraceReporter(String party, String traceUrl, String internalKey, ObjectMapper json) {
        this.party = party;
        this.endpoint = traceUrl == null || traceUrl.isBlank() ? null : URI.create(traceUrl + "/internal/v1/events");
        this.internalKey = internalKey;
        this.json = json;
    }

    /** An ISO 8583 message this party sent ({@code from} = party) or received ({@code to} = party). */
    public void iso(String traceKey, String from, String to, IsoMessage message) {
        Map<String, Object> fields = new LinkedHashMap<>();
        Masking.mask(message).forEach((field, value) -> fields.put(String.valueOf(field), value));
        report(traceKey, from, to, "iso8583", message.mti(), describe(message), fields);
    }

    /** Anything that isn't ISO 8583: an API call, a decision, a webhook. */
    public void note(String traceKey, String from, String to, String summary, Map<String, ?> details) {
        report(traceKey, from, to, "note", null, summary, details);
    }

    public long dropped() {
        return dropped;
    }

    private void report(String traceKey, String from, String to, String kind, String mti, String summary, Map<String, ?> details) {
        if (endpoint == null || traceKey == null) {
            return;
        }
        if (!inFlight.tryAcquire()) {
            dropped++;
            return;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("traceKey", traceKey);
        event.put("reporter", party);
        event.put("from", from);
        event.put("to", to);
        event.put("kind", kind);
        if (mti != null) {
            event.put("mti", mti);
        }
        event.put("summary", summary);
        event.put("details", details);
        event.put("at", Instant.now().toString());
        String body;
        try {
            body = json.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            inFlight.release();
            return;
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(2))
                .header("Content-Type", "application/json")
                .header(InternalKeyFilter.HEADER, internalKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, e) -> {
            inFlight.release();
            if (e != null || response.statusCode() >= 300) {
                dropped++;
                if (dropped % 100 == 1) {
                    log.debug("Trace reports are being dropped ({} so far)", dropped);
                }
            }
        });
    }

    static String describe(IsoMessage m) {
        String what = switch (m.mti()) {
            case "0100" -> "Authorisation request";
            case "0110" -> "Authorisation response";
            case "0120" -> "Authorisation advice";
            case "0130" -> "Authorisation advice response";
            case "0200" -> "Financial request";
            case "0210" -> "Financial response";
            case "0400" -> "Reversal request";
            case "0410" -> "Reversal response";
            case "0420" -> "Reversal advice";
            case "0430" -> "Reversal advice response";
            case "0800" -> "Network management request";
            case "0810" -> "Network management response";
            default -> "Message " + m.mti();
        };
        if (m.has(39)) {
            what += ": " + app.cardrail.iso8583.ResponseCode.describe(m.get(39)) + " (" + m.get(39) + ")";
        }
        return what;
    }
}
