package app.cardrail.host;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Looks after one live cell's database (docs/decisions.md, ADR-015). The two cells split by institution: cell
 * {@code a} (the phone) owns the banks' schemas, cell {@code b} (the laptop) owns the rest. Each cell publishes the
 * schemas it owns and subscribes to the other's, in the same {@code cardrail} database, so each holds a whole copy
 * and no table is written in two places.
 *
 * <ul>
 *   <li>{@code migrate}: brings every schema, owned or copied, to the release's migrations. Logical replication
 *       carries rows, not DDL, so both cells migrate before either starts a release.</li>
 *   <li>{@code replicate}: publishes every owned table and, once the other cell publishes too, subscribes to it
 *       (or refreshes the subscription when a release added tables). Safe to run again; 2 means "not yet".</li>
 *   <li>{@code promote}: takes the other cell's schemas over: stops the subscription and moves every sequence
 *       past the ids that were copied, since logical replication doesn't carry sequences. It publishes them too,
 *       so the other cell can copy everything back when it returns.</li>
 * </ul>
 */
final class CellAdmin {

    private static final Logger log = LoggerFactory.getLogger(CellAdmin.class);

    /** Schema, and where its migrations live. */
    static final Map<String, String> SCHEMAS = new LinkedHashMap<>();

    static {
        SCHEMAS.put("issuer", "classpath:db/issuer");
        SCHEMAS.put("meridian", "classpath:db/closedloop");
        SCHEMAS.put("trace", "classpath:db/trace");
        SCHEMAS.put("network", "classpath:db/network");
        SCHEMAS.put("acquirer", "classpath:db/acquirer");
        SCHEMAS.put("vault", "classpath:db/vault");
        SCHEMAS.put("gateway", "classpath:db/gateway");
    }

    static final List<String> CELL_A_SCHEMAS = List.of("issuer", "meridian");

    private final String cell;
    private final String peer;
    private final String url;
    private final String user;
    private final String password;
    private final String peerUrl;
    private final String peerPassword;

    CellAdmin(Map<String, String> env) {
        this.cell = env.getOrDefault("CARDRAIL_CELL", "");
        if (!cell.equals("a") && !cell.equals("b")) {
            throw new IllegalArgumentException("CARDRAIL_CELL must be a (the phone) or b (the laptop)");
        }
        this.peer = cell.equals("a") ? "b" : "a";
        this.url = env.getOrDefault("CARDRAIL_DB_URL", "jdbc:postgresql://localhost:5432/cardrail");
        this.user = env.getOrDefault("CARDRAIL_DB_USER", "cardrail");
        this.password = env.getOrDefault("CARDRAIL_DB_PASSWORD", "cardrail");
        this.peerUrl = env.getOrDefault("CARDRAIL_PEER_DB_URL", "");
        this.peerPassword = env.getOrDefault("CARDRAIL_PEER_DB_PASSWORD", password);
    }

    static List<String> owned(String cell) {
        return cell.equals("a") ? CELL_A_SCHEMAS
                : SCHEMAS.keySet().stream().filter(s -> !CELL_A_SCHEMAS.contains(s)).toList();
    }

    void migrate() {
        SCHEMAS.forEach((schema, location) -> {
            var result = Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema)
                    .locations(location).load().migrate();
            log.info("Schema {} is at version {}", schema, result.targetSchemaVersion);
        });
    }

    /** Publishes what this cell owns and subscribes to the other cell; false while the other cell isn't ready. */
    boolean replicate() throws SQLException {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            publish(st, owned(cell));
            if (!peerPublishes()) {
                log.info("Cell {} doesn't publish yet; subscribing later", peer);
                return false;
            }

            String subscription = "cardrail_from_" + peer;
            if (exists(st, "SELECT 1 FROM pg_subscription WHERE subname = '" + subscription + "'")) {
                st.execute("ALTER SUBSCRIPTION " + subscription + " REFRESH PUBLICATION");
                log.info("Refreshed subscription {}", subscription);
                return true;
            }
            // The copy starts from the other cell's rows only: anything a migration seeded here would collide.
            List<String> copies = tables(st, owned(peer));
            if (!copies.isEmpty()) {
                st.execute("TRUNCATE " + String.join(", ", copies) + " CASCADE");
            }
            st.execute("CREATE SUBSCRIPTION " + subscription + " CONNECTION '" + conninfo(peerUrl, user, peerPassword).replace("'", "''")
                    + "' PUBLICATION cardrail_" + peer + " WITH (copy_data = true, slot_name = '" + subscription + "')");
            log.info("Subscribed to cell {}: {} tables copying", peer, copies.size());
            return true;
        }
    }

    private void publish(Statement st, List<String> schemas) throws SQLException {
        String publication = "cardrail_" + cell;
        if (!exists(st, "SELECT 1 FROM pg_publication WHERE pubname = '" + publication + "'")) {
            st.execute("CREATE PUBLICATION " + publication);
            log.info("Created publication {}", publication);
        }
        for (String table : tables(st, schemas)) {
            if (!exists(st, "SELECT 1 FROM pg_publication_tables WHERE pubname = '" + publication
                    + "' AND schemaname || '.' || tablename = '" + table + "'")) {
                st.execute("ALTER PUBLICATION " + publication + " ADD TABLE " + table);
                log.info("Publishing {}", table);
            }
        }
    }

    /** Whether the other cell's publication exists and holds every table it owns. */
    private boolean peerPublishes() {
        if (peerUrl.isBlank()) {
            throw new IllegalStateException("CARDRAIL_PEER_DB_URL is not set");
        }
        try (Connection c = DriverManager.getConnection(peerUrl, user, peerPassword); Statement st = c.createStatement()) {
            int expected = tables(st, owned(peer)).size();
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_publication_tables WHERE pubname = 'cardrail_" + peer + "'")) {
                return rs.next() && expected > 0 && rs.getInt(1) >= expected;
            }
        } catch (SQLException e) {
            log.info("Cell {}'s database can't be reached: {}", peer, e.getMessage());
            return false;
        }
    }

    /** libpq's form of a JDBC URL, for the subscription. */
    static String conninfo(String jdbcUrl, String user, String password) {
        var m = java.util.regex.Pattern.compile("jdbc:postgresql://([^:/]+)(?::([0-9]+))?/([^?]+).*").matcher(jdbcUrl);
        if (!m.matches()) {
            throw new IllegalArgumentException("Not a PostgreSQL JDBC URL: " + jdbcUrl);
        }
        return "host=" + m.group(1) + " port=" + (m.group(2) == null ? "5432" : m.group(2)) + " dbname=" + m.group(3)
                + " user=" + user + " password=" + password + " sslmode=disable";
    }

    /** Takes the other cell's schemas over. The other cell's replication slot is left for its failback. */
    void promote() throws SQLException {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            String subscription = "cardrail_from_" + peer;
            if (exists(st, "SELECT 1 FROM pg_subscription WHERE subname = '" + subscription + "'")) {
                st.execute("ALTER SUBSCRIPTION " + subscription + " DISABLE");
                st.execute("ALTER SUBSCRIPTION " + subscription + " SET (slot_name = NONE)");
                st.execute("DROP SUBSCRIPTION " + subscription);
                log.info("Stopped copying from cell {}", peer);
            }
            List<String[]> sequences = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("""
                    SELECT s.relnamespace::regnamespace::text, s.relname, t.relnamespace::regnamespace::text, t.relname, a.attname
                    FROM pg_class s
                    JOIN pg_depend d ON d.objid = s.oid AND d.deptype IN ('a', 'i')
                    JOIN pg_class t ON t.oid = d.refobjid
                    JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = d.refobjsubid
                    WHERE s.relkind = 'S' AND s.relnamespace::regnamespace::text = ANY (string_to_array('%s', ','))
                    """.formatted(String.join(",", owned(peer))))) {
                while (rs.next()) {
                    sequences.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)});
                }
            }
            for (String[] s : sequences) {
                st.execute("SELECT setval('" + s[0] + "." + s[1] + "', GREATEST((SELECT max(" + s[4] + ") FROM "
                        + s[2] + "." + s[3] + "), 0) + 1000, false)");
            }
            publish(st, new ArrayList<>(SCHEMAS.keySet()));
            log.info("Cell {} now owns every schema; moved {} sequences past the copied ids", cell, sequences.size());
        }
    }

    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(url, user, password);
        c.setAutoCommit(true);
        return c;
    }

    private static boolean exists(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            return rs.next();
        }
    }

    /** Every table in these schemas but Flyway's history, which each cell keeps for itself. */
    private static List<String> tables(Statement st, List<String> schemas) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("SELECT schemaname || '.' || tablename FROM pg_tables WHERE schemaname = ANY "
                + "(string_to_array('" + String.join(",", schemas) + "', ',')) AND tablename <> 'flyway_schema_history' ORDER BY 1")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        return tables;
    }
}
