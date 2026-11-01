package app.cardrail.gateway;

import app.cardrail.common.CommonWeb;
import app.cardrail.common.trace.TraceReporter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;

/** The gateway's calls to the vault and the acquirer. */
@Component
public class Clients {

    private final RestClient vault;
    private final RestClient acquirer;

    public Clients(GatewayProperties props) {
        this.vault = CommonWeb.internalClient(props.vaultUrl(), props.internalKey(), Duration.ofSeconds(3));
        this.acquirer = CommonWeb.internalClient(props.acquirerUrl(), props.internalKey(), props.acquirerTimeout());
    }

    public RestClient vault() {
        return vault;
    }

    public RestClient acquirer() {
        return acquirer;
    }

    @Bean
    static TraceReporter traceReporter(GatewayProperties props, ObjectMapper json) {
        return new TraceReporter(Parties.GATEWAY, props.traceUrl(), props.internalKey(), json);
    }

    @Bean
    static Clock clock() {
        return Clock.systemUTC();
    }
}
