package app.cardrail.vault;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
class VaultBeans {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
