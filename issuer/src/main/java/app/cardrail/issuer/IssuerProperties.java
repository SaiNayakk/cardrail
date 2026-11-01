package app.cardrail.issuer;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param institutionId Kestrel Bank's id on Railnet (field 100 when the network sends to us)
 * @param isoBind       where Railnet connects to send authorisations
 * @param panKey        AES-256 key (base64) that encrypts card numbers at rest
 * @param hashKey       HMAC key (base64) that turns a card number into the id it is looked up by
 * @param cvk           card verification key (base64): CVV2s are derived from it, never stored
 * @param demo          whether the published test cards exist (and are listed)
 */
@ConfigurationProperties("issuer")
public record IssuerProperties(
        String institutionId,
        String isoBind,
        int isoPort,
        String panKey,
        String hashKey,
        String cvk,
        String traceUrl,
        String internalKey,
        boolean demo) {
}
