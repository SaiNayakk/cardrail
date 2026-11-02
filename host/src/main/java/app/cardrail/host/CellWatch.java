package app.cardrail.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Watches the other live cell and takes its institutions over when it is lost (docs/decisions.md, ADR-015).
 *
 * <p>A cell is {@code NORMAL} (running its own half), {@code HOLDING} (running everything, the other's half too) or
 * {@code TAKEN_OVER} (running nothing: the other holds its half until it is handed back). The state lives in a file,
 * so a restart keeps it, and the host picks what to run from it. Each cell publishes its state on the home network.
 *
 * <p>A cell takes the other over only when the other has said nothing on the home network for two minutes <em>and</em>
 * Sprout's public witness shows the other device's region down: a broken home network alone, with both devices
 * up, must not make both cells take each other over. On the phone it also waits until Sprout's own takeover has
 * finished, so the two never start JVMs at once.
 */
final class CellWatch {

    private static final Logger log = LoggerFactory.getLogger(CellWatch.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    static final String NORMAL = "NORMAL";
    static final String HOLDING = "HOLDING";
    static final String TAKEN_OVER = "TAKEN_OVER";

    /** What one look at the world found. */
    record View(String peerState, boolean peerRegionUp, boolean sproutTakingOver) {
    }

    enum Action { NOTHING, FENCE, TAKE_OVER, WAIT_FOR_SPROUT }

    /**
     * The rules, kept pure so each is tested. {@code peerSeen}: the other cell answered at least once since this one
     * started (one never seen is not set up yet, not lost); {@code silentFor}: how long it hasn't answered.
     */
    static Action decide(String state, View view, boolean peerSeen, Duration silentFor, Duration takeoverAfter) {
        if (!state.equals(NORMAL)) {
            return Action.NOTHING;
        }
        if (HOLDING.equals(view.peerState())) {
            return Action.FENCE;                                   // the other cell holds this one's half already
        }
        if (view.peerState() != null || !peerSeen || silentFor.compareTo(takeoverAfter) < 0 || view.peerRegionUp()) {
            return Action.NOTHING;
        }
        return view.sproutTakingOver() ? Action.WAIT_FOR_SPROUT : Action.TAKE_OVER;
    }

    private final Map<String, String> env;
    private final String cell;
    private final Path stateFile;
    private final Duration takeoverAfter;
    private volatile String group = "";
    private boolean peerSeen;
    private Instant peerLastSeen = Instant.now();

    CellWatch(Map<String, String> env) {
        this.env = env;
        this.cell = env.getOrDefault("CARDRAIL_CELL", "");
        this.stateFile = Path.of(env.getOrDefault("CARDRAIL_STATE_FILE", "cell-state"));
        this.takeoverAfter = Duration.ofSeconds(Long.parseLong(env.getOrDefault("CARDRAIL_TAKEOVER_AFTER_SECONDS", "120")));
    }

    String state() {
        try {
            String s = Files.readString(stateFile).trim();
            return s.equals(HOLDING) || s.equals(TAKEN_OVER) ? s : NORMAL;
        } catch (IOException e) {
            return NORMAL;
        }
    }

    /** The group this cell runs in its state: its own half, everything, or nothing. */
    String groupFor(String own) {
        return switch (state()) {
            case HOLDING -> "cell";
            case TAKEN_OVER -> "fenced";
            default -> own;
        };
    }

    /** Before any service starts: a cell the other already holds must not serve. */
    void checkBeforeStart() {
        if (state().equals(NORMAL) && HOLDING.equals(peerState())) {
            log.warn("Cell {} holds this cell's institutions; serving nothing until they are handed back", peer());
            write(TAKEN_OVER);
        }
    }

    void start(String group) throws IOException {
        this.group = group;
        int port = Integer.parseInt(env.getOrDefault("CARDRAIL_STATUS_PORT", "8209"));
        HttpServer server = HttpServer.create(new InetSocketAddress(env.getOrDefault("CARDRAIL_STATUS_BIND", "0.0.0.0"), port), 8);
        server.createContext("/cell/status", exchange -> {
            byte[] body = JSON.writeValueAsBytes(Map.of("cell", cell, "state", state(), "group", this.group));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cellwatch");
            t.setDaemon(true);
            return t;
        }).scheduleWithFixedDelay(this::check, 10, 10, TimeUnit.SECONDS);
        log.info("Cell {} is {}, running {}; status on port {}", cell, state(), group, port);
    }

    private void check() {
        try {
            String peerState = peerState();
            if (peerState != null) {
                peerSeen = true;
                peerLastSeen = Instant.now();
            }
            View view = new View(peerState, peerState == null && peerRegionUp(), peerState == null && sproutTakingOver());
            switch (decide(state(), view, peerSeen, Duration.between(peerLastSeen, Instant.now()), takeoverAfter)) {
                case FENCE -> {
                    log.warn("Cell {} holds this cell's institutions; stopping them here", peer());
                    write(TAKEN_OVER);
                    System.exit(3);
                }
                case TAKE_OVER -> {
                    log.warn("Cell {} is lost (silent here, its region down at the witness); taking its institutions over", peer());
                    new CellAdmin(env).promote();
                    write(HOLDING);
                    System.exit(3);             // restarted as the whole cell on the now-whole database
                }
                case WAIT_FOR_SPROUT -> log.info("Cell {} is lost; waiting for Sprout's takeover to finish first", peer());
                case NOTHING -> {
                }
            }
        } catch (Exception e) {
            log.warn("The cell watch couldn't check: {}", e.toString());
        }
    }

    private String peer() {
        return cell.equals("a") ? "b" : "a";
    }

    /** The other cell's state, or null when it doesn't answer. */
    private String peerState() {
        String url = env.getOrDefault("CARDRAIL_PEER_STATUS_URL", "");
        if (url.isBlank()) {
            return null;
        }
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(4)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 ? JSON.readTree(r.body()).path("state").asText(null) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Whether Sprout's witness shows the other device's region up. When in doubt, up: takeovers need proof. */
    private boolean peerRegionUp() {
        try {
            String url = env.getOrDefault("CARDRAIL_WITNESS_URL", "https://sprout-witness.sai31nayak.workers.dev/status");
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).build(),
                    HttpResponse.BodyHandlers.ofString());
            for (JsonNode region : JSON.readTree(r.body()).path("regions")) {
                if (region.path("id").asText().equals(peer())) {
                    return region.path("status").asText().equals("up") && region.path("last_seen_s").asLong(Long.MAX_VALUE) < 120;
                }
            }
        } catch (Exception e) {
            log.info("The witness can't be read: {}", e.toString());
        }
        return true;
    }

    /** Whether Sprout's cellwatch on this device is in the middle of a takeover (read only). */
    private boolean sproutTakingOver() {
        String file = env.getOrDefault("SPROUT_STATUS_FILE", "");
        if (file.isBlank()) {
            return false;
        }
        try {
            return Files.readString(Path.of(file), StandardCharsets.UTF_8).contains("TAKING_OVER");
        } catch (IOException e) {
            return false;
        }
    }

    private void write(String state) {
        try {
            Files.writeString(stateFile, state + "\n");
        } catch (IOException e) {
            throw new IllegalStateException("Can't write the cell state to " + stateFile, e);
        }
    }
}
