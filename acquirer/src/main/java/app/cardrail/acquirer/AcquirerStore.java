package app.cardrail.acquirer;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AcquirerStore {

    public record Merchant(UUID id, String mid, String name, String mcc, String city, String country, int mdrBps, String status) {}

    public record Authorisation(UUID id, UUID merchantId, String tid, String rrn, String stan, String transmitted, String kind,
                                String originalRrn, long amountCents, long capturedCents, String responseCode, String authCode,
                                String status) {}

    public record Reversal(long id, String rrn, String reason, long remainingCents, int attempts) {}

    private static final String AUTHORISATION = """
            select id, merchant_id, tid, rrn, stan, transmitted, kind, original_rrn, amount_cents, captured_cents,
                   response_code, auth_code, status
            from authorisations""";

    private final JdbcClient jdbc;

    public AcquirerStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Merchant createMerchant(String name, String mcc, String city, String country, int mdrBps) {
        UUID id = UUID.randomUUID();
        long n = jdbc.sql("select nextval('mid_seq')").query(Long.class).single();
        String mid = "RPA" + String.format("%012d", n);
        jdbc.sql("insert into merchants (id, mid, name, mcc, city, country, mdr_bps) values (?, ?, ?, ?, ?, ?, ?)")
                .params(id, mid, name, mcc, city, country, mdrBps).update();
        return merchant(mid).orElseThrow();
    }

    public String createTerminal(UUID merchantId, String kind) {
        long n = jdbc.sql("select nextval('tid_seq')").query(Long.class).single();
        String tid = ("ECOMMERCE".equals(kind) ? "W" : "P") + String.format("%07d", n);
        jdbc.sql("insert into terminals (tid, merchant_id, kind) values (?, ?, ?)").params(tid, merchantId, kind).update();
        return tid;
    }

    public Optional<Merchant> merchant(String mid) {
        return jdbc.sql("select id, mid, name, mcc, city, country, mdr_bps, status from merchants where mid = ?")
                .param(mid).query(Merchant.class).optional();
    }

    public Optional<Merchant> merchant(UUID id) {
        return jdbc.sql("select id, mid, name, mcc, city, country, mdr_bps, status from merchants where id = ?")
                .param(id).query(Merchant.class).optional();
    }

    public List<String> terminals(UUID merchantId) {
        return jdbc.sql("select tid from terminals where merchant_id = ? order by tid").param(merchantId).query(String.class).list();
    }

    public boolean terminalBelongs(String tid, UUID merchantId) {
        return jdbc.sql("select count(*) from terminals where tid = ? and merchant_id = ?").params(tid, merchantId)
                .query(Long.class).single() == 1;
    }

    /** False if this RRN was used before. */
    public boolean pending(UUID merchantId, String tid, String rrn, String stan, String transmitted, String pan,
                           long amountCents, String currency, String kind, String originalRrn) {
        return jdbc.sql("""
                insert into authorisations (id, merchant_id, tid, rrn, stan, transmitted, bin, last4, amount_cents, currency,
                                            status, kind, original_rrn)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                on conflict (rrn) do nothing""")
                .params(UUID.randomUUID(), merchantId, tid, rrn, stan, transmitted, pan.substring(0, 6),
                        pan.substring(pan.length() - 4), amountCents, currency, kind, originalRrn)
                .update() == 1;
    }

    public void answered(String rrn, String responseCode, String authCode, String status) {
        jdbc.sql("""
                update authorisations set response_code = ?, auth_code = ?, status = ?, answered_at = now()
                where rrn = ? and status in ('PENDING', 'UNKNOWN')""")
                .params(responseCode, authCode, status, rrn).update();
    }

    public Optional<Authorisation> authorisation(String rrn) {
        return jdbc.sql(AUTHORISATION + " where rrn = ?").param(rrn).query(Authorisation.class).optional();
    }

    public Optional<Authorisation> lockAuthorisation(String rrn) {
        return jdbc.sql(AUTHORISATION + " where rrn = ? for update").param(rrn).query(Authorisation.class).optional();
    }

    public void setStatus(String rrn, String status) {
        jdbc.sql("update authorisations set status = ? where rrn = ?").params(status, rrn).update();
    }

    public void captured(String rrn, long cents) {
        jdbc.sql("update authorisations set status = 'CAPTURED', captured_cents = ?, captured_at = now() where rrn = ?")
                .params(cents, rrn).update();
    }

    /** What has been refunded, or is being, against a purchase. */
    public long refundedCents(String originalRrn) {
        return jdbc.sql("""
                select coalesce(sum(amount_cents), 0) from authorisations
                where original_rrn = ? and kind = 'REFUND' and status in ('PENDING', 'APPROVED', 'CAPTURED', 'UNKNOWN')""")
                .param(originalRrn).query(Long.class).single();
    }

    /** Owes the network a reversal (0420) of this authorisation; false if one is already owed. */
    public boolean oweReversal(String rrn, String reason, long remainingCents) {
        return jdbc.sql("""
                insert into reversals (rrn, reason, remaining_cents) values (?, ?, ?)
                on conflict (rrn) do nothing""").params(rrn, reason, remainingCents).update() == 1;
    }

    public List<Reversal> dueReversals(int limit) {
        return jdbc.sql("""
                select id, rrn, reason, remaining_cents, attempts from reversals
                where status = 'PENDING' and next_attempt_at <= now() order by id limit ?""")
                .param(limit).query(Reversal.class).list();
    }

    public void reversalDone(long id) {
        jdbc.sql("update reversals set status = 'DONE', done_at = now(), attempts = attempts + 1 where id = ?").param(id).update();
    }

    /** Tries again later, waiting longer each time, up to a minute. */
    public void reversalFailed(long id, String error) {
        jdbc.sql("""
                update reversals set attempts = attempts + 1, last_error = ?,
                       next_attempt_at = now() + least(interval '60 seconds', interval '2 seconds' * power(2, attempts))
                where id = ?""").params(error, id).update();
    }

    public long reversalsOwed() {
        return jdbc.sql("select count(*) from reversals where status = 'PENDING'").query(Long.class).single();
    }
}
