package app.cardrail.gateway;

import app.cardrail.common.CommonWeb;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Railpay, the payment gateway. Reads {@code gateway.yml}. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@Import(CommonWeb.class)
public class GatewayApplication {

    public static final String CONFIG_NAME = "gateway";

    public static void main(String[] args) {
        builder().run(args);
    }

    public static SpringApplicationBuilder builder() {
        return new SpringApplicationBuilder(GatewayApplication.class).properties("spring.config.name=" + CONFIG_NAME);
    }
}
