package app.cardrail.trace;

import app.cardrail.common.CommonWeb;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/** The live trace. Reads {@code trace.yml}. */
@SpringBootApplication
@EnableScheduling
@Import(CommonWeb.class)
public class TraceApplication {

    public static final String CONFIG_NAME = "trace";

    public static void main(String[] args) {
        builder().run(args);
    }

    public static SpringApplicationBuilder builder() {
        return new SpringApplicationBuilder(TraceApplication.class).properties("spring.config.name=" + CONFIG_NAME);
    }
}
