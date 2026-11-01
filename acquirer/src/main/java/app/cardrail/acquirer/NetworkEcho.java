package app.cardrail.acquirer;

import app.cardrail.iso8583.IsoMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * Network management: asks Railnet "are you there" (0800, code 301) every 30 seconds. It keeps the connection open
 * through idle periods, and finds a broken one before a payment does.
 */
@Component
class NetworkEcho {

    private static final Logger log = LoggerFactory.getLogger(NetworkEcho.class);
    private static final DateTimeFormatter FIELD_7 = DateTimeFormatter.ofPattern("MMddHHmmss");

    private final AcquirerProperties props;
    private final NetworkLink network;
    private volatile boolean answering = true;

    NetworkEcho(AcquirerProperties props, NetworkLink network) {
        this.props = props;
        this.network = network;
    }

    @Scheduled(fixedDelayString = "${acquirer.echo-every:30s}", initialDelay = 5_000)
    void echo() {
        IsoMessage echo = new IsoMessage("0800")
                .set(7, Instant.now().atZone(ZoneOffset.UTC).format(FIELD_7))
                .set(11, network.nextStan())
                .set(32, props.institutionId())
                .set(37, "ECHO" + String.format("%08d", System.nanoTime() % 100_000_000L))
                .set(70, "301");
        Duration timeout = Duration.ofSeconds(3);
        try {
            network.link().send(echo, timeout).get(timeout.toMillis() + 500, TimeUnit.MILLISECONDS);
            if (!answering) {
                log.info("Railnet is answering again");
            }
            answering = true;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (answering) {
                log.warn("Railnet isn't answering echoes: {}", e.getMessage());
            }
            answering = false;
        }
    }

    boolean answering() {
        return answering;
    }
}
