package app.cardrail.closedloop;

import app.cardrail.common.CommonWeb;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Meridian, the closed loop. Reads {@code closedloop.yml}. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@Import(CommonWeb.class)
public class MeridianApplication {

    public static final String CONFIG_NAME = "closedloop";

    public static void main(String[] args) {
        builder().run(args);
    }

    public static SpringApplicationBuilder builder() {
        return new SpringApplicationBuilder(MeridianApplication.class).properties("spring.config.name=" + CONFIG_NAME);
    }

    /**
     * @param institutionId Meridian's id: field 100 on what acquirers send it, the receiver of their submissions
     * @param key           32 bytes (base64): card numbers encrypted and hashed, card security codes derived
     * @param gatewayUrl    Railpay's internal API, told about Meridian's payouts to its merchants
     */
    @ConfigurationProperties("meridian")
    public record Properties(String institutionId, String isoBind, int isoPort, String key, String gatewayUrl, String traceUrl,
                             String internalKey, boolean demo) {}
}
