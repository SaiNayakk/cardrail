package app.cardrail.issuer;

import app.cardrail.common.CommonWeb;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

/**
 * Kestrel Bank, the issuer. Runs alone ({@link #main}) or in a host ({@link #builder()}); either way it reads
 * {@code issuer.yml}, so services sharing a JVM never read each other's settings.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@Import(CommonWeb.class)
public class IssuerApplication {

    public static final String CONFIG_NAME = "issuer";

    public static void main(String[] args) {
        builder().run(args);
    }

    public static SpringApplicationBuilder builder() {
        return new SpringApplicationBuilder(IssuerApplication.class).properties("spring.config.name=" + CONFIG_NAME);
    }
}
