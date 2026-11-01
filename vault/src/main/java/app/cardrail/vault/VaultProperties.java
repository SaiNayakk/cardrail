package app.cardrail.vault;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param key        AES-256 key (base64) that encrypts card numbers and security codes at rest
 * @param gatewayUrl where to check a publishable key
 */
@ConfigurationProperties("vault")
public record VaultProperties(String key, String gatewayUrl, String internalKey) {
}
