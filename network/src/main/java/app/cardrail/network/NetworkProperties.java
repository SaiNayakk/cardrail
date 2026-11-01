package app.cardrail.network;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * @param institutionId Railnet's own id (field 33 on what it forwards)
 * @param issuers       where each issuer listens, by institution id
 */
@ConfigurationProperties("network")
public record NetworkProperties(
        String institutionId,
        String isoBind,
        int isoPort,
        Duration issuerTimeout,
        Map<String, Endpoint> issuers,
        String traceUrl,
        String internalKey) {

    public record Endpoint(String host, int port) {}
}
