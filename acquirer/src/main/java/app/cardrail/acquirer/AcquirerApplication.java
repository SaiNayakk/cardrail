package app.cardrail.acquirer;

import app.cardrail.common.CommonWeb;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Railpay Acquiring. Reads {@code acquirer.yml}. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@Import(CommonWeb.class)
public class AcquirerApplication {

    public static final String CONFIG_NAME = "acquirer";

    public static void main(String[] args) {
        builder().run(args);
    }

    public static SpringApplicationBuilder builder() {
        return new SpringApplicationBuilder(AcquirerApplication.class).properties("spring.config.name=" + CONFIG_NAME);
    }
}
