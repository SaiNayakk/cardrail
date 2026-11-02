package app.cardrail.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param keyMode "test" or "live": what new API keys are called (sk_test_...). Everything here is a demo, so test.
 * @param demo    the demo shop: a merchant made at startup with keys from the environment
 */
@ConfigurationProperties("gateway")
public record GatewayProperties(
        String vaultUrl,
        String acquirerUrl,
        String networkUrl,
        Duration acquirerTimeout,
        String keyMode,
        String traceUrl,
        String internalKey,
        Demo demo) {

    public record Demo(boolean enabled, String secretKey, String publishableKey, String webhookUrl, String webhookSecret) {}
}
