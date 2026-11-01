package app.cardrail.trace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Stores events and hands each new one to whoever is watching its trace. */
@Service
public class Traces {

    public record Event(long id, String traceKey, String reporter, String from, String to, String kind, String mti,
                        String summary, JsonNode details, Instant at) {}

    public record Summary(String traceKey, String firstSummary, Instant startedAt, int events) {}

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Duration retention;
    private final Map<String, Set<Consumer<Event>>> watchers = new ConcurrentHashMap<>();

    public Traces(JdbcClient jdbc, ObjectMapper json, @Value("${trace.retention:7d}") Duration retention) {
        this.jdbc = jdbc;
        this.json = json;
        this.retention = retention;
    }

    public Event record(String traceKey, String reporter, String from, String to, String kind, String mti, String summary,
                        Object details, Instant at) {
        String detailsJson;
        try {
            detailsJson = json.writeValueAsString(details == null ? Map.of() : details);
        } catch (JsonProcessingException e) {
            detailsJson = "{}";
        }
        long id = jdbc.sql("""
                insert into events (trace_key, reporter, from_party, to_party, kind, mti, summary, details, at)
                values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?) returning id""")
                .params(traceKey, reporter, from, to, kind, mti, summary, detailsJson, java.sql.Timestamp.from(at))
                .query(Long.class).single();
        Event event = new Event(id, traceKey, reporter, from, to, kind, mti, summary, parse(detailsJson), at);
        watchers.getOrDefault(traceKey, Set.of()).forEach(w -> w.accept(event));
        return event;
    }

    public List<Event> events(String traceKey) {
        return jdbc.sql("""
                select id, trace_key, reporter, from_party, to_party, kind, mti, summary, details::text, at
                from events where trace_key = ? order by at, id""")
                .param(traceKey)
                .query((rs, n) -> new Event(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getString(8), parse(rs.getString(9)), rs.getTimestamp(10).toInstant()))
                .list();
    }

    public List<Summary> recent(int limit) {
        return jdbc.sql("""
                select trace_key, (array_agg(summary order by at, id))[1], min(at), count(*)::int
                from events where received_at > now() - interval '1 day'
                group by trace_key order by min(at) desc limit ?""")
                .param(limit)
                .query((rs, n) -> new Summary(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getInt(4)))
                .list();
    }

    public Runnable watch(String traceKey, Consumer<Event> watcher) {
        watchers.computeIfAbsent(traceKey, k -> ConcurrentHashMap.newKeySet()).add(watcher);
        return () -> watchers.computeIfPresent(traceKey, (k, set) -> {
            set.remove(watcher);
            return set.isEmpty() ? null : set;
        });
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void forgetOldTraces() {
        jdbc.sql("delete from events where received_at < ?").param(java.sql.Timestamp.from(Instant.now().minus(retention))).update();
    }

    private JsonNode parse(String s) {
        try {
            return json.readTree(s);
        } catch (IOException e) {
            return json.createObjectNode();
        }
    }
}
