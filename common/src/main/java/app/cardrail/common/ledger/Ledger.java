package app.cardrail.common.ledger;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Double-entry books, one set per party, in that party's own schema (tables {@code journals} and {@code postings},
 * created by each service's migrations). A journal is a set of postings that add up to zero, in cents: money only
 * ever moves from one account to another. Each journal has a key, so posting the same thing twice is harmless: the
 * second time finds the first.
 *
 * <p>Accounts are plain names, by convention {@code kind:owner}, e.g. {@code cardholder:<account id>},
 * {@code settlement:railnet}, {@code income:interchange}.
 */
public final class Ledger {

    public record Posting(String account, long cents) {}

    public record Entry(UUID journalId, String key, String description, String account, long cents, java.time.Instant at) {}

    private final JdbcClient jdbc;

    public Ledger(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Builds a journal: debits are positive, credits negative, and they must balance. */
    public static Builder journal(String key, String description) {
        return new Builder(key, description);
    }

    public static final class Builder {
        private final String key;
        private final String description;
        private final Map<String, Long> postings = new LinkedHashMap<>();

        private Builder(String key, String description) {
            this.key = key;
            this.description = description;
        }

        /** Money moves from {@code from} to {@code to}: {@code to} is debited (goes up), {@code from} credited. */
        public Builder move(long cents, String from, String to) {
            if (cents < 0) {
                return move(-cents, to, from);
            }
            if (cents > 0) {
                postings.merge(to, cents, Long::sum);
                postings.merge(from, -cents, Long::sum);
            }
            return this;
        }

        List<Posting> postings() {
            List<Posting> out = new ArrayList<>();
            postings.forEach((account, cents) -> {
                if (cents != 0) {
                    out.add(new Posting(account, cents));
                }
            });
            return out;
        }
    }

    /**
     * Posts a journal, in the caller's transaction. Returns false (and posts nothing) if a journal with this key was
     * posted before.
     */
    public boolean post(Builder journal) {
        List<Posting> postings = journal.postings();
        long sum = postings.stream().mapToLong(Posting::cents).sum();
        if (sum != 0) {
            throw new IllegalArgumentException("journal " + journal.key + " doesn't balance: " + sum);
        }
        UUID id = UUID.randomUUID();
        boolean inserted = jdbc.sql("insert into journals (id, key, description) values (?, ?, ?) on conflict (key) do nothing")
                .params(id, journal.key, journal.description).update() == 1;
        if (!inserted) {
            return false;
        }
        for (Posting p : postings) {
            jdbc.sql("insert into postings (journal_id, account, cents) values (?, ?, ?)").params(id, p.account(), p.cents()).update();
        }
        return true;
    }

    public boolean posted(String key) {
        return jdbc.sql("select count(*) from journals where key = ?").param(key).query(Long.class).single() > 0;
    }

    /** An account's balance: what has been debited to it less what has been credited. */
    public long balance(String account) {
        return jdbc.sql("select coalesce(sum(cents), 0) from postings where account = ?").param(account).query(Long.class).single();
    }

    /** Balances of every account starting with {@code prefix}. */
    public Map<String, Long> balances(String prefix) {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.sql("select account, sum(cents) from postings where account like ? group by account order by account")
                .param(prefix + "%")
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getLong(2)))
                .list().forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /** The whole book adds up to zero, or something was posted that didn't balance. */
    public long total() {
        return jdbc.sql("select coalesce(sum(cents), 0) from postings").query(Long.class).single();
    }

    public List<Entry> entries(String account, int limit) {
        return jdbc.sql("""
                select j.id, j.key, j.description, p.account, p.cents, j.created_at
                from postings p join journals j on j.id = p.journal_id
                where p.account = ? order by j.created_at desc, p.id desc limit ?""")
                .params(account, limit)
                .query((rs, n) -> new Entry(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getLong(5), rs.getTimestamp(6).toInstant()))
                .list();
    }

    public Optional<UUID> journalId(String key) {
        return jdbc.sql("select id from journals where key = ?").param(key).query(UUID.class).optional();
    }

    /** The SQL each service's migration uses to create its books. */
    public static final String SCHEMA = """
            create table journals (
                id          uuid primary key,
                key         text not null unique,
                description text not null,
                created_at  timestamptz not null default now()
            );
            create table postings (
                id          bigserial primary key,
                journal_id  uuid not null references journals (id),
                account     text not null,
                cents       bigint not null check (cents <> 0)
            );
            create index postings_account on postings (account);
            """;
}
