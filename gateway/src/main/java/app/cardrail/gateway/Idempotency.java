package app.cardrail.gateway;

import app.cardrail.common.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.function.Supplier;

/**
 * Idempotency keys, as Stripe and Adyen offer them. The first request with a key claims it before doing
 * anything; a repeat with the same body gets the first answer; a repeat while the first is still running is
 * told to wait; the same key with a different body is refused, because it is a bug in the caller.
 */
@Component
public class Idempotency {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public Idempotency(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * @param key      the key as stored: the caller's, scoped (a v2 key never replays a v1 answer); ignored when
     *                 {@code given} is null
     * @param given    the Idempotency-Key header as the caller sent it, or null for no idempotency
     */
    public <T> ResponseEntity<?> run(String merchantId, String key, String given, Object request, Supplier<ResponseEntity<T>> work) {
        if (given == null) {
            return work.get();
        }
        if (given.isBlank() || given.length() > 255) {
            throw ApiException.invalid("invalid_idempotency_key", "an Idempotency-Key is 1 to 255 characters");
        }
        byte[] hash = Keys.sha256(write(request));
        boolean claimed = jdbc.sql("""
                insert into idempotency_keys (merchant_id, key, request_hash) values (?, ?, ?)
                on conflict do nothing""").params(merchantId, key, hash).update() == 1;
        if (!claimed) {
            record Stored(byte[] requestHash, Integer statusCode, String body) {}
            Stored stored = jdbc.sql("select request_hash, status_code, body from idempotency_keys where merchant_id = ? and key = ?")
                    .params(merchantId, key).query(Stored.class).single();
            if (!MessageDigest.isEqual(stored.requestHash(), hash)) {
                throw ApiException.invalid("idempotency_key_reused", "this Idempotency-Key was used with a different request");
            }
            if (stored.statusCode() == null) {
                throw new ApiException(HttpStatus.CONFLICT, "request_in_progress",
                        "a request with this Idempotency-Key is still being worked on; try again in a moment");
            }
            return ResponseEntity.status(stored.statusCode()).header("Idempotent-Replayed", "true")
                    .header("Content-Type", "application/json").body(stored.body());
        }
        ResponseEntity<T> response;
        try {
            response = work.get();
        } catch (ApiException e) {
            if (e.status().is4xxClientError()) {
                // a refusal is an answer too: the same request will be refused the same way
                remember(merchantId, key, e.status().value(), problem(e));
            } else {
                forget(merchantId, key);
            }
            throw e;
        } catch (RuntimeException e) {
            forget(merchantId, key);
            throw e;
        }
        remember(merchantId, key, response.getStatusCode().value(), write(response.getBody()));
        return response;
    }

    private void remember(String merchantId, String key, int status, String body) {
        jdbc.sql("update idempotency_keys set status_code = ?, body = ? where merchant_id = ? and key = ?")
                .params(status, body, merchantId, key).update();
    }

    private void forget(String merchantId, String key) {
        jdbc.sql("delete from idempotency_keys where merchant_id = ? and key = ? and status_code is null").params(merchantId, key).update();
    }

    private String problem(ApiException e) {
        return write(java.util.Map.of("status", e.status().value(), "code", e.code(), "detail", e.getMessage()));
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
