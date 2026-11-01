package app.cardrail.trace;

import app.cardrail.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
class TraceController {

    /** At most this many people watching live at once; the phone has little memory to give. */
    static final int MAX_STREAMS = 50;

    private final Traces traces;
    private final AtomicInteger streams = new AtomicInteger();

    TraceController(Traces traces) {
        this.traces = traces;
    }

    record Report(@NotBlank @Size(max = 32) String traceKey, @NotBlank String reporter, @NotBlank String from,
                  @NotBlank String to, @Pattern(regexp = "iso8583|note") String kind, String mti,
                  @NotBlank @Size(max = 300) String summary, Map<String, Object> details, @NotNull Instant at) {}

    /** A party reports a message it sent. */
    @PostMapping("/internal/v1/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void report(@Valid @RequestBody Report r) {
        traces.record(r.traceKey(), r.reporter(), r.from(), r.to(), r.kind(), r.mti(), r.summary(), r.details(), r.at());
    }

    record TraceView(String traceKey, List<Traces.Event> events) {}

    @GetMapping("/v1/traces/{key}")
    TraceView trace(@PathVariable String key) {
        List<Traces.Event> events = traces.events(key);
        if (events.isEmpty()) {
            throw ApiException.notFound("trace " + key);
        }
        return new TraceView(key, events);
    }

    @GetMapping("/v1/traces")
    List<Traces.Summary> recent(@RequestParam(defaultValue = "20") int limit) {
        return traces.recent(Math.max(1, Math.min(limit, 50)));
    }

    /**
     * The trace as it happens: every event so far, then each new one, for up to two minutes. A browser reconnects
     * by itself; the event ids let it skip what it has seen.
     */
    @GetMapping(value = "/v1/traces/{key}/stream", produces = "text/event-stream")
    SseEmitter stream(@PathVariable String key) {
        if (streams.incrementAndGet() > MAX_STREAMS) {
            streams.decrementAndGet();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "too_many_streams", "too many people are watching; try again soon");
        }
        SseEmitter emitter = new SseEmitter(120_000L);
        Runnable[] stop = new Runnable[1];
        Runnable done = () -> {
            if (stop[0] != null) {
                stop[0].run();
                stop[0] = null;
                streams.decrementAndGet();
            }
        };
        stop[0] = traces.watch(key, event -> send(emitter, event, done));
        emitter.onCompletion(done);
        emitter.onTimeout(done);
        emitter.onError(e -> done.run());
        for (Traces.Event event : traces.events(key)) {
            send(emitter, event, done);
        }
        return emitter;
    }

    private static void send(SseEmitter emitter, Traces.Event event, Runnable done) {
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().id(String.valueOf(event.id())).name("event").data(event));
            }
        } catch (IOException | IllegalStateException e) {
            done.run();
        }
    }
}
