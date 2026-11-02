package app.cardrail.network;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * @param institutionId Railnet's own id (field 33 on what it forwards)
 * @param key           32 bytes (base64): encrypts the advices it stores, and keys the card hashes stand-in limits use
 * @param echoEvery     how often each issuer is asked "are you there" (0800, code 301)
 * @param issuers       where each issuer listens, and what Railnet may approve for it when it can't answer
 */
@ConfigurationProperties("network")
public record NetworkProperties(
        String institutionId,
        String isoBind,
        int isoPort,
        Duration issuerTimeout,
        Duration echoEvery,
        String key,
        Map<String, Endpoint> issuers,
        String traceUrl,
        String internalKey) {

    /**
     * @param standInMaxCents     the most one stand-in approval may be for
     * @param standInDailyCount   how many stand-in approvals one card may get in a day
     * @param standInDailyCents   how much one card may be approved for by stand-in in a day
     * @param acsUrl              the issuer's access control server, for step-up through the directory
     */
    public record Endpoint(String host, int port, Long standInMaxCents, Integer standInDailyCount, Long standInDailyCents,
                           String acsUrl) {

        public boolean standsIn() {
            return standInMaxCents != null && standInMaxCents > 0;
        }
    }
}
