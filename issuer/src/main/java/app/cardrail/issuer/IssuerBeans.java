package app.cardrail.issuer;

import app.cardrail.common.trace.TraceReporter;
import app.cardrail.iso8583.Iso8583Codec;
import app.cardrail.iso8583.MessageSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
class IssuerBeans {

    @Bean
    CardKeys cardKeys(IssuerProperties props) {
        return new CardKeys(props.panKey(), props.hashKey(), props.cvk());
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    Iso8583Codec codec() {
        return new Iso8583Codec(MessageSpec.RAILNET);
    }

    @Bean
    TraceReporter traceReporter(IssuerProperties props, ObjectMapper json) {
        return new TraceReporter(Parties.KESTREL, props.traceUrl(), props.internalKey(), json);
    }
}
