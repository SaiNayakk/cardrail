package app.cardrail.gateway;

import app.cardrail.common.trace.TraceReporter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Webhooks: telling a merchant's server what happened to its payments.
 *
 * <p>An event is written to an outbox in the same transaction as the change it reports, so a change is never made
 * without its event, nor an event sent for a change that rolled back. A sender delivers events until the merchant
 * answers 2xx, waiting longer after each failure (10 s, 20 s, 40 s... up to an hour) and giving up after three days.
 * Delivery is at least once: a merchant may see an event twice, and recognises it by its id.
 *
 * <p>Each delivery is signed: {@code Railpay-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256 of "t.body">} with the
 * merchant's webhook secret. The timestamp lets a merchant refuse old deliveries, so a captured one can't be replayed.
 */
@Component
public class Webhooks {

    private static final Logger log = LoggerFactory.getLogger(Webhooks.class);
    static final String SIGNATURE_HEADER = "Railpay-Signature";
    static final Duration GIVE_UP_AFTER = Duration.ofDays(3);

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final TraceReporter trace;
    private final Clock clock;
    private final RestClient http;

    public Webhooks(JdbcClient jdbc, ObjectMapper json, TraceReporter trace, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.trace = trace;
        this.clock = clock;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /** Records an event for the merchant, in the caller's transaction. Nothing is sent to a merchant without a webhook URL. */
    public void emit(Merchants.Merchant merchant, String type, Object data) {
        if (merchant.webhookUrl() == null || merchant.webhookUrl().isBlank()) {
            return;
        }
        String id = Keys.random("evt_", 20);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", id);
        event.put("object", "event");
        event.put("type", type);
        event.put("createdAt", clock.instant().toString());
        event.put("data", data);
        try {
            jdbc.sql("insert into webhook_events (id, merchant_id, type, payload) values (?, ?, ?, ?)")
                    .params(id, merchant.id(), type, json.writeValueAsString(event)).update();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    record Due(String id, String merchantId, String type, String payload, int attempts, Instant createdAt, String webhookUrl,
               String webhookSecret) {}

    @Scheduled(fixedDelayString = "${gateway.webhook-every:2s}", initialDelayString = "${gateway.webhook-every:2s}")
    public void deliver() {
        List<Due> due = jdbc.sql("""
                select e.id, e.merchant_id, e.type, e.payload, e.attempts, e.created_at, m.webhook_url, m.webhook_secret
                from webhook_events e join merchants m on m.id = e.merchant_id
                where e.delivered_at is null and e.next_attempt_at <= now() order by e.created_at limit 20""")
                .query(Due.class).list();
        for (Due event : due) {
            deliver(event);
        }
    }

    private void deliver(Due event) {
        long t = clock.instant().getEpochSecond();
        String signature = "t=" + t + ",v1=" + sign(event.webhookSecret(), t + "." + event.payload());
        Integer status = null;
        String error = null;
        try {
            var response = http.post().uri(event.webhookUrl())
                    .header("Content-Type", "application/json")
                    .header(SIGNATURE_HEADER, signature)
                    .header("Railpay-Event-Id", event.id())
                    .body(event.payload())
                    .retrieve().toBodilessEntity();
            status = response.getStatusCode().value();
        } catch (RestClientResponseException e) {
            status = e.getStatusCode().value();
            error = "answered " + status;
        } catch (RuntimeException e) {
            error = e.getClass().getSimpleName();
        }
        String rrn = reference(event.payload());
        if (error == null) {
            jdbc.sql("update webhook_events set delivered_at = now(), attempts = attempts + 1, last_status = ?, last_error = null where id = ?")
                    .params(status, event.id()).update();
            trace.note(rrn, Parties.GATEWAY, Parties.MERCHANT, "Webhook " + event.type() + " delivered", Map.of("event", event.id()));
            return;
        }
        boolean givingUp = event.createdAt().plus(GIVE_UP_AFTER).isBefore(clock.instant());
        long wait = Math.min(3600, 10L << Math.min(event.attempts(), 9));
        jdbc.sql("""
                update webhook_events set attempts = attempts + 1, last_status = ?, last_error = ?,
                       next_attempt_at = case when ? then 'infinity'::timestamptz else now() + make_interval(secs => ?) end
                where id = ?""")
                .params(status, error, givingUp, wait, event.id()).update();
        if (event.attempts() == 0) {
            log.info("Webhook {} to merchant {} failed ({}); retrying with backoff", event.id(), event.merchantId(), error);
        }
    }

    static String sign(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private String reference(String payload) {
        try {
            var data = json.readTree(payload).path("data");
            return data.hasNonNull("reference") ? data.get("reference").asText() : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
