package app.cardrail.acquirer;

import app.cardrail.common.iso.IsoLink;
import app.cardrail.common.trace.TraceReporter;
import app.cardrail.iso8583.Iso8583Codec;
import app.cardrail.iso8583.MessageSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/** The acquirer's one connection to Railnet, and its own trace numbering (field 11). */
@Component
public class NetworkLink {

    private final IsoLink link;
    private final AtomicInteger stan = new AtomicInteger((int) (Math.random() * 900_000));

    public NetworkLink(AcquirerProperties props, Iso8583Codec codec) {
        this.link = new IsoLink("acquirer-to-railnet", props.networkHost(), props.networkPort(), codec);
    }

    public IsoLink link() {
        return link;
    }

    public String nextStan() {
        return String.format("%06d", stan.updateAndGet(n -> n >= 999_999 ? 1 : n + 1));
    }

    @PreDestroy
    void close() {
        link.close();
    }

    @Configuration(proxyBeanMethods = false)
    static class Beans {

        @Bean
        Iso8583Codec codec() {
            return new Iso8583Codec(MessageSpec.RAILNET);
        }

        @Bean
        TraceReporter traceReporter(AcquirerProperties props, ObjectMapper json) {
            return new TraceReporter(Parties.ACQUIRER, props.traceUrl(), props.internalKey(), json);
        }

        @Bean
        java.time.Clock clock() {
            return java.time.Clock.systemUTC();
        }
    }
}
