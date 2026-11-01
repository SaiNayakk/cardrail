package app.cardrail.network;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The BIN table, held in memory and reloaded every minute, so routing never waits on the database. A card is
 * routed to the issuer whose range holds its first six digits.
 */
@Component
public class Routing {

    public record Range(String low, String high, String issuerId, String product) {}

    private final JdbcClient jdbc;
    private volatile TreeMap<String, Range> byLow = new TreeMap<>();
    private volatile Set<String> acquirers = Set.of();

    public Routing(JdbcClient jdbc) {
        this.jdbc = jdbc;
        reload();
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void reload() {
        TreeMap<String, Range> ranges = new TreeMap<>();
        jdbc.sql("select low, high, issuer_id, product from bin_ranges").query(Range.class).list()
                .forEach(r -> ranges.put(r.low(), r));
        byLow = ranges;
        acquirers = jdbc.sql("select institution_id from members where role = 'ACQUIRER'").query(String.class).list()
                .stream().collect(Collectors.toUnmodifiableSet());
    }

    public Optional<Range> route(String pan) {
        if (pan == null || pan.length() < 6) {
            return Optional.empty();
        }
        String bin = pan.substring(0, 6);
        var floor = byLow.floorEntry(bin);
        return floor == null || floor.getValue().high().compareTo(bin) < 0 ? Optional.empty() : Optional.of(floor.getValue());
    }

    public boolean isAcquirer(String institutionId) {
        return acquirers.contains(institutionId);
    }

    public List<Range> ranges() {
        return List.copyOf(byLow.values());
    }
}
