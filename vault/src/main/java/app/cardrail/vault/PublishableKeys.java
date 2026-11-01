package app.cardrail.vault;

import app.cardrail.common.ApiException;
import app.cardrail.common.CommonWeb;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which merchant a publishable key belongs to, asked of the gateway and remembered for five minutes. A
 * publishable key is public (it is in the merchant's web page); it can only make tokens for that merchant.
 */
@Component
class PublishableKeys {

    private record Cached(String merchantId, Instant until) {}

    record Answer(String merchantId) {}

    private final RestClient gateway;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    PublishableKeys(VaultProperties props) {
        this.gateway = CommonWeb.internalClient(props.gatewayUrl(), props.internalKey(), Duration.ofSeconds(3));
    }

    String merchantFor(String publishableKey) {
        if (publishableKey == null || !publishableKey.matches("pk_(test|live)_[A-Za-z0-9]{8,64}")) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_key", "that isn't a publishable key");
        }
        Cached cached = cache.get(publishableKey);
        if (cached != null && cached.until().isAfter(Instant.now())) {
            return cached.merchantId();
        }
        try {
            Answer answer = gateway.get().uri("/internal/v1/publishable-keys/{key}", publishableKey).retrieve().body(Answer.class);
            cache.put(publishableKey, new Cached(answer.merchantId(), Instant.now().plusSeconds(300)));
            return answer.merchantId();
        } catch (HttpClientErrorException.NotFound e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_key", "no merchant has that publishable key");
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "unavailable", "the card form can't be used right now; try again shortly");
        }
    }
}
