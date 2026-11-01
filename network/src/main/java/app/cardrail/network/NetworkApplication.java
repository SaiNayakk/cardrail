package app.cardrail.network;

import app.cardrail.common.CommonWeb;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Railnet, the card network. Reads {@code network.yml}. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@Import(CommonWeb.class)
public class NetworkApplication {

    public static final String CONFIG_NAME = "network";

    public static void main(String[] args) {
        builder().run(args);
    }

    public static SpringApplicationBuilder builder() {
        return new SpringApplicationBuilder(NetworkApplication.class).properties("spring.config.name=" + CONFIG_NAME);
    }
}
