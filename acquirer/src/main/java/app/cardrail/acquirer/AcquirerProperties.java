package app.cardrail.acquirer;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** @param institutionId Railpay Acquiring's id on Railnet (field 32) */
@ConfigurationProperties("acquirer")
public record AcquirerProperties(
        String institutionId,
        String networkHost,
        int networkPort,
        Duration networkTimeout,
        String traceUrl,
        String internalKey) {
}
