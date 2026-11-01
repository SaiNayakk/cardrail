package app.cardrail.network;

import app.cardrail.common.iso.IsoLink;
import app.cardrail.iso8583.Iso8583Codec;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One connection to each issuer, opened when first needed. The network numbers what it sends to an issuer
 * itself (field 11), so trace numbers from different acquirers can never collide on an issuer's link.
 */
@Component
public class IssuerLinks {

    private final NetworkProperties props;
    private final Iso8583Codec codec;
    private final Map<String, IsoLink> links = new ConcurrentHashMap<>();
    private final AtomicInteger stan = new AtomicInteger((int) (Math.random() * 900_000));

    public IssuerLinks(NetworkProperties props, Iso8583Codec codec) {
        this.props = props;
        this.codec = codec;
    }

    public Optional<IsoLink> link(String issuerId) {
        NetworkProperties.Endpoint endpoint = props.issuers() == null ? null : props.issuers().get(issuerId);
        if (endpoint == null) {
            return Optional.empty();
        }
        return Optional.of(links.computeIfAbsent(issuerId,
                id -> new IsoLink("railnet-to-" + id, endpoint.host(), endpoint.port(), codec)));
    }

    public String nextStan() {
        return String.format("%06d", stan.updateAndGet(n -> n >= 999_999 ? 1 : n + 1));
    }

    @PreDestroy
    void close() {
        links.values().forEach(IsoLink::close);
    }
}
