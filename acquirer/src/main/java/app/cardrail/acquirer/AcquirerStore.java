package app.cardrail.acquirer;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AcquirerStore {

    public record Merchant(UUID id, String mid, String name, String mcc, String city, String country, int mdrBps, String status) {}

    public record Authorisation(UUID id, UUID merchantId, String rrn, String stan, String transmitted, long amountCents,
                                String responseCode, String authCode, String status) {}

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

    public List<String> terminals(UUID merchantId) {
        return jdbc.sql("select tid from terminals where merchant_id = ? order by tid").param(merchantId).query(String.class).list();
    }

    public boolean terminalBelongs(String tid, UUID merchantId) {
        return jdbc.sql("select count(*) from terminals where tid = ? and merchant_id = ?").params(tid, merchantId)
                .query(Long.class).single() == 1;
    }

    /** False if this RRN was used before. */
    public boolean pending(UUID merchantId, String tid, String rrn, String stan, String transmitted, String pan,
                           long amountCents, String currency) {
        return jdbc.sql("""
                insert into authorisations (id, merchant_id, tid, rrn, stan, transmitted, bin, last4, amount_cents, currency, status)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
                on conflict (rrn) do nothing""")
                .params(UUID.randomUUID(), merchantId, tid, rrn, stan, transmitted, pan.substring(0, 6),
                        pan.substring(pan.length() - 4), amountCents, currency)
                .update() == 1;
    }

    public void answered(String rrn, String responseCode, String authCode, String status) {
        jdbc.sql("""
                update authorisations set response_code = ?, auth_code = ?, status = ?, answered_at = now()
                where rrn = ? and status in ('PENDING', 'UNKNOWN')""")
                .params(responseCode, authCode, status, rrn).update();
    }

    public Optional<Authorisation> authorisation(String rrn) {
        return jdbc.sql("""
                select id, merchant_id, rrn, stan, transmitted, amount_cents, response_code, auth_code, status
                from authorisations where rrn = ?""")
                .param(rrn).query(Authorisation.class).optional();
    }
}
