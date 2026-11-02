package app.cardrail.acquirer;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param institutionId Railpay Acquiring's id on Railnet (field 32)
 * @param networkUrl    Railnet's internal API: clearing files and settlement reports
 * @param gatewayUrl    Railpay's internal API, told about disputes and payouts
 */
@ConfigurationProperties("acquirer")
public record AcquirerProperties(
        String institutionId,
        String networkHost,
        int networkPort,
        Duration networkTimeout,
        String traceUrl,
        String internalKey,
        String networkUrl,
        String gatewayUrl,
        String meridianHost,
        int meridianPort,
        String meridianUrl) {
}
