package app.cardrail.network;

import app.cardrail.common.ApiException;
import app.cardrail.common.CommonWeb;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Railnet's directory server, as in 3-D Secure: a merchant's gateway asks it to authenticate a cardholder, and it
 * finds the card's issuer by BIN and passes the request to that issuer's ACS. The answer (a challenge to show the
 * shopper, then the outcome and its proof) comes back the same way. Railnet never sees the one-time code.
 */
@Service
public class Directory {

    public record Started(String issuerId, String id, String status, String challengePath) {}

    public record Result(String id, String status, String value) {}

    record AcsStarted(String id, String status, String challengePath) {}

    private final NetworkProperties props;
    private final Routing routing;
    private final Map<String, RestClient> acs = new ConcurrentHashMap<>();

    public Directory(NetworkProperties props, Routing routing) {
        this.props = props;
        this.routing = routing;
    }

    public Started start(String pan, String expiry, long amountCents, String merchant, String reference) {
        String issuerId = routing.route(pan).map(Routing.Range::issuerId)
                .orElseThrow(() -> ApiException.invalid("no_issuer", "no issuer for this card"));
        AcsStarted started = call(() -> acs(issuerId).post().uri("/internal/v1/acs/authentications")
                .body(Map.of("pan", pan, "expiry", expiry, "amountCents", amountCents, "merchant", merchant,
                        "reference", reference == null ? "" : reference))
                .retrieve().body(AcsStarted.class));
        return new Started(issuerId, started.id(), started.status(), started.challengePath());
    }

    public Result result(String issuerId, String id) {
        return call(() -> acs(issuerId).get().uri("/internal/v1/acs/authentications/{id}", id).retrieve().body(Result.class));
    }

    private RestClient acs(String issuerId) {
        NetworkProperties.Endpoint endpoint = props.issuers() == null ? null : props.issuers().get(issuerId);
        if (endpoint == null || endpoint.acsUrl() == null) {
            throw ApiException.invalid("no_acs", "this card's issuer doesn't offer step-up");
        }
        return acs.computeIfAbsent(issuerId, id -> CommonWeb.internalClient(endpoint.acsUrl(), props.internalKey(), Duration.ofSeconds(5)));
    }

    private static <T> T call(java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "authentication_refused", "the card's issuer refused to authenticate it");
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "issuer_unavailable", "the card's issuer can't be reached for step-up");
        }
    }
}
