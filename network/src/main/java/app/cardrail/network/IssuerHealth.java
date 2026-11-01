package app.cardrail.network;

import app.cardrail.iso8583.IsoMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Whether each issuer is answering, from network management echoes (0800, code 301) and from how its real
 * traffic goes. An issuer that misses two in a row is DOWN: authorisations for it go straight to stand-in instead
 * of each waiting out the timeout. One answer brings it back UP.
 */
@Component
public class IssuerHealth {

    private static final Logger log = LoggerFactory.getLogger(IssuerHealth.class);
    private static final DateTimeFormatter FIELD_7 = DateTimeFormatter.ofPattern("MMddHHmmss");
    static final int MISSES_TO_DOWN = 2;

    public enum State { UP, DOWN }

    private final NetworkProperties props;
    private final IssuerLinks links;
    private final Map<String, Integer> misses = new ConcurrentHashMap<>();
    private final Map<String, State> states = new ConcurrentHashMap<>();

    public IssuerHealth(NetworkProperties props, IssuerLinks links) {
        this.props = props;
        this.links = links;
    }

    public State state(String issuerId) {
        return states.getOrDefault(issuerId, State.UP);
    }

    public boolean isUp(String issuerId) {
        return state(issuerId) == State.UP;
    }

    public Map<String, State> states() {
        return Map.copyOf(states);
    }

    public void answered(String issuerId) {
        misses.put(issuerId, 0);
        if (states.put(issuerId, State.UP) == State.DOWN) {
            log.info("Issuer {} is answering again", issuerId);
        }
    }

    public void missed(String issuerId) {
        if (misses.merge(issuerId, 1, Integer::sum) >= MISSES_TO_DOWN && states.put(issuerId, State.DOWN) != State.DOWN) {
            log.warn("Issuer {} isn't answering: standing in for it", issuerId);
        }
    }

    @Scheduled(fixedDelayString = "${network.echo-every:5s}", initialDelayString = "${network.echo-every:5s}")
    public void echo() {
        if (props.issuers() == null) {
            return;
        }
        Duration timeout = Duration.ofSeconds(2);
        props.issuers().keySet().forEach(issuerId -> links.link(issuerId).ifPresent(link -> {
            IsoMessage echo = new IsoMessage("0800")
                    .set(7, Instant.now().atZone(ZoneOffset.UTC).format(FIELD_7))
                    .set(11, links.nextStan())
                    .set(33, props.institutionId())
                    .set(37, "ECHO" + String.format("%08d", System.nanoTime() % 100_000_000L))
                    .set(70, "301")
                    .set(100, issuerId);
            try {
                IsoMessage response = link.send(echo, timeout).get(timeout.toMillis() + 500, TimeUnit.MILLISECONDS);
                if ("00".equals(response.get(39))) {
                    answered(issuerId);
                } else {
                    missed(issuerId);
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                missed(issuerId);
            }
        }));
    }
}
