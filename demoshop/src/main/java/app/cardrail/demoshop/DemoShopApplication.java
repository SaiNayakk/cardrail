package app.cardrail.demoshop;

import app.cardrail.common.CommonWeb;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcClientAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

/**
 * The demo shop: a merchant's server. It has no database (a shared host's classpath would otherwise make Spring
 * set one up for it). Reads {@code demoshop.yml}.
 */
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class, JdbcClientAutoConfiguration.class, FlywayAutoConfiguration.class})
@ConfigurationPropertiesScan
@Import(CommonWeb.class)
public class DemoShopApplication {

    public static final String CONFIG_NAME = "demoshop";

    public static void main(String[] args) {
        builder().run(args);
    }

    public static SpringApplicationBuilder builder() {
        return new SpringApplicationBuilder(DemoShopApplication.class).properties("spring.config.name=" + CONFIG_NAME);
    }
}
