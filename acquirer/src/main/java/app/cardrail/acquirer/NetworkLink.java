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
    private final IsoLink meridian;
    private final AtomicInteger stan = new AtomicInteger((int) (Math.random() * 900_000));

    public NetworkLink(AcquirerProperties props, Iso8583Codec codec) {
        this.link = new IsoLink("acquirer-to-railnet", props.networkHost(), props.networkPort(), codec);
        this.meridian = new IsoLink("acquirer-to-meridian", props.meridianHost(), props.meridianPort(), codec);
    }

    public IsoLink link() {
        return link;
    }

    /** Railnet for the cards it routes, Meridian for its own: a closed loop is reached directly. */
    public IsoLink link(String scheme) {
        return "MERIDIAN".equals(scheme) ? meridian : link;
    }

    /** Meridian's cards start 39, as American Express cards start 34 and 37. */
    public static String schemeOf(String pan) {
        return pan != null && pan.startsWith("39") ? "MERIDIAN" : "RAILNET";
    }

    public String nextStan() {
        return String.format("%06d", stan.updateAndGet(n -> n >= 999_999 ? 1 : n + 1));
    }

    @PreDestroy
    void close() {
        link.close();
        meridian.close();
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
