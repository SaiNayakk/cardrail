package app.cardrail.vault;

import app.cardrail.common.CommonWeb;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Railpay's card vault. Reads {@code vault.yml}. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@Import(CommonWeb.class)
public class VaultApplication {

    public static final String CONFIG_NAME = "vault";

    public static void main(String[] args) {
        builder().run(args);
    }

    public static SpringApplicationBuilder builder() {
        return new SpringApplicationBuilder(VaultApplication.class).properties("spring.config.name=" + CONFIG_NAME);
    }
}
