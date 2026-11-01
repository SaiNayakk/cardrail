package app.cardrail.network;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class NetworkStore {

    public record Answer(String responseCode, String authCode) {}

    public record Original(String issuerId, String acquirerStan, String decidedBy, String responseCode, Instant reversedAt) {}

    public record Advice(long id, String issuerId, String acquirerId, String rrn, String mti, byte[] message, int attempts) {}

    public record StandInUse(int count, long cents) {}

    private final JdbcClient jdbc;

    public NetworkStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Records a request as it arrives; false if the acquirer sent it before. */
    public boolean requested(String acquirerId, String rrn, String acquirerStan, String issuerId, String networkStan,
                             String pan, byte[] panHash, String mti, long amountCents, String currency) {
        return jdbc.sql("""
                insert into authorisations (id, acquirer_id, rrn, acquirer_stan, issuer_id, network_stan, bin, last4, pan_hash,
                                            mti, amount_cents, currency)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (acquirer_id, rrn, acquirer_stan) do nothing""")
                .params(UUID.randomUUID(), acquirerId, rrn, acquirerStan, issuerId, networkStan, pan.substring(0, 6),
                        pan.substring(pan.length() - 4), panHash, mti, amountCents, currency)
                .update() == 1;
    }

    public void answered(String acquirerId, String rrn, String acquirerStan, String responseCode, String authCode, String decidedBy) {
        jdbc.sql("""
                update authorisations set response_code = ?, auth_code = ?, decided_by = ?, answered_at = now()
                where acquirer_id = ? and rrn = ? and acquirer_stan = ?""")
                .params(responseCode, authCode, decidedBy, acquirerId, rrn, acquirerStan).update();
    }

    /** The answer already given to this request, if any. */
    public Optional<Answer> answer(String acquirerId, String rrn, String acquirerStan) {
        return jdbc.sql("""
                select response_code, auth_code from authorisations
                where acquirer_id = ? and rrn = ? and acquirer_stan = ? and response_code is not null""")
                .params(acquirerId, rrn, acquirerStan).query(Answer.class).optional();
    }

    /** The authorisation a reversal refers to: the acquirer's, by its reference. */
    public Optional<Original> original(String acquirerId, String rrn) {
        return jdbc.sql("""
                select issuer_id, acquirer_stan, decided_by, response_code, reversed_at from authorisations
                where acquirer_id = ? and rrn = ? and mti in ('0100', '0200') order by requested_at limit 1""")
                .params(acquirerId, rrn).query(Original.class).optional();
    }

    /**
     * A reversal of something the network never saw: recorded as if answered "original not found", so if the
     * original arrives late it is answered from here and never reaches an issuer.
     */
    public void tombstone(String acquirerId, String rrn, String acquirerStan, String mti) {
        jdbc.sql("""
                insert into authorisations (id, acquirer_id, rrn, acquirer_stan, bin, last4, pan_hash, mti, amount_cents,
                                            currency, response_code, decided_by, answered_at, reversed_at)
                values (?, ?, ?, ?, '000000', '0000', '\\x'::bytea, ?, 0, '840', '25', 'NETWORK', now(), now())
                on conflict (acquirer_id, rrn, acquirer_stan) do nothing""")
                .params(UUID.randomUUID(), acquirerId, rrn, acquirerStan, mti).update();
    }

    public void reversed(String acquirerId, String rrn) {
        jdbc.sql("update authorisations set reversed_at = now() where acquirer_id = ? and rrn = ? and reversed_at is null")
                .params(acquirerId, rrn).update();
    }

    /** How much stand-in has approved for this card today (UTC). */
    public StandInUse standInToday(byte[] panHash) {
        var since = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        return jdbc.sql("""
                select count(*)::int, coalesce(sum(amount_cents), 0) from authorisations
                where pan_hash = ? and decided_by = 'STAND_IN' and response_code = '00' and requested_at >= ?""")
                .params(panHash, java.sql.Timestamp.from(since))
                .query((rs, n) -> new StandInUse(rs.getInt(1), rs.getLong(2))).single();
    }

    public void enqueueAdvice(String issuerId, String acquirerId, String rrn, String mti, byte[] sealed) {
        jdbc.sql("insert into advices (issuer_id, acquirer_id, rrn, mti, message) values (?, ?, ?, ?, ?)")
                .params(issuerId, acquirerId, rrn, mti, sealed).update();
    }

    public List<Advice> pendingAdvices(String issuerId, int limit) {
        return jdbc.sql("""
                select id, issuer_id, acquirer_id, rrn, mti, message, attempts from advices
                where issuer_id = ? and acknowledged_at is null order by id limit ?""")
                .params(issuerId, limit).query(Advice.class).list();
    }

    public boolean hasPendingAdvice(String issuerId, String acquirerId, String rrn) {
        return jdbc.sql("select count(*) from advices where issuer_id = ? and acquirer_id = ? and rrn = ? and acknowledged_at is null")
                .params(issuerId, acquirerId, rrn).query(Long.class).single() > 0;
    }

    public long pendingAdviceCount() {
        return jdbc.sql("select count(*) from advices where acknowledged_at is null").query(Long.class).single();
    }

    public void acknowledged(long adviceId) {
        jdbc.sql("update advices set acknowledged_at = now(), attempts = attempts + 1, last_error = null where id = ?")
                .param(adviceId).update();
    }

    public void adviceFailed(long adviceId, String error) {
        jdbc.sql("update advices set attempts = attempts + 1, last_error = ? where id = ?").params(error, adviceId).update();
    }
}
