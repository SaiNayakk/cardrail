package app.cardrail.network;

import app.cardrail.common.ledger.Ledger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Net settlement: at each cut-off, every member's position (what it owes Railnet for cleared sales, less what it
 * is owed) is settled through the settlement bank in one payment each way. A member with a hundred sales and ten
 * refunds pays or receives once. Each member then reads its settlement report, with every record it covers, and
 * books it in its own ledger.
 *
 * <p>Railnet's own account at the settlement bank ends each cut-off holding exactly the fees it earned: members'
 * positions always add up to them, which is checked as each settlement is made.
 */
@Service
public class Settlement {

    private static final Logger log = LoggerFactory.getLogger(Settlement.class);

    public record Item(String type, String acquirerId, String issuerId, String rrn, long amountCents, long interchangeCents,
                       long feeCents) {}

    public record Report(String settlementId, LocalDate businessDate, String memberId, long netCents, List<Item> items) {}

    private final JdbcClient jdbc;
    private final Ledger ledger;
    private final TransactionTemplate tx;
    private final Clock clock;

    public Settlement(JdbcClient jdbc, TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc;
        this.ledger = new Ledger(jdbc);
        this.tx = tx;
        this.clock = clock;
    }

    /** Settles everything cleared since the last cut-off. Nothing to settle, nothing happens. */
    @Scheduled(fixedDelayString = "${network.settle-every:5m}", initialDelayString = "${network.settle-every:5m}")
    public Optional<String> settle() {
        return tx.execute(status -> {
            // one cut-off at a time, even with two network hosts running (a cell and its standby)
            jdbc.sql("select pg_advisory_xact_lock(4242)").query(Object.class).single();
            List<Long> records = jdbc.sql("select id from clearing_records where status = 'ROUTED' and settlement_id is null")
                    .query(Long.class).list();
            if (records.isEmpty()) {
                return Optional.<String>empty();
            }
            String id = "stl_" + LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString().replace("-", "") + "_"
                    + jdbc.sql("select count(*) + 1 from settlements").query(Long.class).single();
            jdbc.sql("insert into settlements (id, business_date) values (?, ?)")
                    .params(id, LocalDate.now(clock.withZone(ZoneOffset.UTC))).update();
            jdbc.sql("update clearing_records set settlement_id = ? where status = 'ROUTED' and settlement_id is null").param(id).update();

            Map<String, Long> balances = ledger.balances("member:");
            var journal = Ledger.journal("stl:" + id, "Net settlement " + id);
            long sum = 0;
            for (var entry : balances.entrySet()) {
                String member = entry.getKey().substring("member:".length());
                long net = entry.getValue();
                if (net == 0) {
                    continue;
                }
                // a member that owes pays the settlement bank; one that is owed is paid by it
                journal.move(net, entry.getKey(), "bank:settlement");
                int count = jdbc.sql("""
                        select count(*)::int from clearing_records
                        where settlement_id = ? and (acquirer_id = ? or issuer_id = ?)""")
                        .params(id, member, member).query(Integer.class).single();
                jdbc.sql("insert into settlement_positions (settlement_id, member_id, net_cents, records) values (?, ?, ?, ?)")
                        .params(id, member, net, count).update();
                sum += net;
            }
            long fees = -ledger.balance("income:scheme-fees") - ledger.balance("bank:settlement");
            if (sum != fees) {
                // positions must add up to the fees not yet collected: anything else means a booking went wrong
                throw new IllegalStateException("settlement " + id + " doesn't add up: positions " + sum + ", fees " + fees);
            }
            ledger.post(journal);
            log.info("Settlement {}: {} records, {} members, {} cents of fees", id, records.size(), balances.size(), sum);
            return Optional.of(id);
        });
    }

    /** A member's settlement reports after the one it last read (or all, from the first). */
    public List<Report> reports(String memberId, String afterSettlementId) {
        record Row(String settlementId, long netCents, LocalDate businessDate) {}
        List<Row> rows = jdbc.sql("""
                select p.settlement_id, p.net_cents, s.business_date from settlement_positions p
                join settlements s on s.id = p.settlement_id
                where p.member_id = ?
                  and s.created_at > coalesce((select created_at from settlements where id = ?), '-infinity'::timestamptz)
                order by s.created_at limit 20""")
                .params(memberId, afterSettlementId)
                .query(Row.class).list();
        return rows.stream().map(row -> new Report(row.settlementId(), row.businessDate(), memberId, row.netCents(),
                jdbc.sql("""
                        select type, acquirer_id, issuer_id, rrn, amount_cents, interchange_cents,
                               case when acquirer_id = ? then acquirer_fee_cents else issuer_fee_cents end as fee_cents
                        from clearing_records where settlement_id = ? and (acquirer_id = ? or issuer_id = ?) order by id""")
                        .params(memberId, row.settlementId(), memberId, memberId)
                        .query(Item.class).list())).toList();
    }
}
