package app.cardrail.issuer;

import app.cardrail.common.ApiException;
import app.cardrail.common.ledger.Ledger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Credit card statements, the way US cards work.
 *
 * <ul>
 *   <li>Each cycle closes with a statement: what was owed at the start, purchases, refunds, payments, interest and fees,
 *       what is owed now, the minimum due and when.</li>
 *   <li><b>Interest</b> is the average daily balance times the APR (24.99%), charged only when the previous statement
 *       wasn't paid in full by its due date: pay in full each month and purchases cost nothing (the grace period).</li>
 *   <li><b>Minimum due</b>: the whole balance if under $25; otherwise the greater of $25 and 1% of the balance plus the
 *       interest and fees just charged.</li>
 *   <li><b>A late fee</b> of $25 when the previous minimum wasn't paid by its due date.</li>
 * </ul>
 *
 * Cycles are 30 days (in pre-prod, minutes); interest always counts a cycle as 30 days, so a fast demo charges what a
 * real month would. Everything is booked in the ledger: interest and fees as income, payments into reserves.
 */
@Service
public class Statements {

    private static final Logger log = LoggerFactory.getLogger(Statements.class);
    static final long MINIMUM_FLOOR_CENTS = 2_500;
    static final long LATE_FEE_CENTS = 2_500;
    static final int CYCLE_DAYS_FOR_INTEREST = 30;

    public record Statement(String id, UUID accountId, Instant periodStart, Instant periodEnd, long openingCents, long purchasesCents,
                            long refundsCents, long paymentsCents, long interestCents, long feesCents, long closingCents,
                            long minimumDueCents, Instant dueAt) {}

    private final JdbcClient jdbc;
    private final Ledger ledger;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Duration cycle;
    private final Duration dueAfter;

    public Statements(JdbcClient jdbc, TransactionTemplate tx, Clock clock,
                      @Value("${issuer.statement-cycle:30d}") Duration cycle, @Value("${issuer.payment-due-after:21d}") Duration dueAfter) {
        this.jdbc = jdbc;
        this.ledger = new Ledger(jdbc);
        this.tx = tx;
        this.clock = clock;
        this.cycle = cycle;
        this.dueAfter = dueAfter;
    }

    /** Closes every credit account's cycle that has run its length. */
    @Scheduled(fixedDelayString = "${issuer.statement-check-every:1m}", initialDelayString = "${issuer.statement-check-every:1m}")
    public void closeDue() {
        Instant now = clock.instant();
        for (UUID account : jdbc.sql("select id from accounts where kind = 'CREDIT'").query(UUID.class).list()) {
            Instant start = periodStart(account);
            if (!start.plus(cycle).isAfter(now)) {
                close(account, start.plus(cycle));
            }
        }
    }

    /** Closes the account's cycle now (the demo's "end the month" button). */
    public Statement closeNow(UUID account) {
        return close(account, clock.instant());
    }

    Statement close(UUID account, Instant end) {
        return tx.execute(s -> {
            jdbc.sql("select id from accounts where id = ? for update").param(account).query(UUID.class).single();
            Instant start = periodStart(account);
            String acct = IssuerBooks.cardholder(account);
            Optional<Statement> previous = latest(account);

            long opening = previous.map(Statement::closingCents).orElse(0L);
            long purchases = sum("""
                    select coalesce(sum(cleared_cents), 0) from authorisations
                    where account_id = ? and kind = 'PURCHASE' and cleared_at >= ? and cleared_at < ?""", account, start, end);
            long refunds = sum("""
                    select coalesce(sum(cleared_cents), 0) from authorisations
                    where account_id = ? and kind = 'REFUND' and cleared_at >= ? and cleared_at < ?""", account, start, end);
            long payments = sum("select coalesce(sum(amount_cents), 0) from card_payments where account_id = ? and created_at >= ? and created_at < ?",
                    account, start, end);

            // grace: no interest if the last statement was paid in full by its due date
            boolean paidInFull = previous.isEmpty() || previous.get().closingCents() <= 0
                    || paidBy(account, previous.get().periodEnd(), previous.get().dueAt()) >= previous.get().closingCents();
            long apr = jdbc.sql("select apr_bps from accounts where id = ?").param(account).query(Long.class).single();
            long interest = paidInFull ? 0 : interest(acct, start, end, apr);
            boolean late = previous.isPresent() && previous.get().minimumDueCents() > 0 && previous.get().dueAt().isBefore(end)
                    && paidBy(account, previous.get().periodEnd(), previous.get().dueAt()) < previous.get().minimumDueCents();
            long fees = late ? LATE_FEE_CENTS : 0;

            String id = "st_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
            if (interest + fees > 0) {
                ledger.post(Ledger.journal("statement:" + id, "Interest and fees, statement " + id)
                        .move(interest, "income:interest", acct)
                        .move(fees, "income:fees", acct));
                jdbc.sql("update accounts set balance_cents = balance_cents + ? where id = ?").params(interest + fees, account).update();
            }
            long closing = ledger.balance(acct);   // owed, by the books
            long minimum = closing <= 0 ? 0
                    : closing <= MINIMUM_FLOOR_CENTS ? closing
                    : Math.min(closing, Math.max(MINIMUM_FLOOR_CENTS, (closing + 50) / 100 + interest + fees));
            Instant due = end.plus(dueAfter);
            jdbc.sql("""
                    insert into statements (id, account_id, period_start, period_end, opening_cents, purchases_cents, refunds_cents,
                                            payments_cents, interest_cents, fees_cents, closing_cents, minimum_due_cents, due_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, account, ts(start), ts(end), opening, purchases, refunds, payments, interest, fees, closing, minimum, ts(due))
                    .update();
            log.info("Statement {} for {}: owes {}, minimum {}", id, account, closing, minimum);
            return byId(id);
        });
    }

    /** The cardholder pays towards their credit card, from their bank account elsewhere. */
    public long pay(UUID account, long cents) {
        return tx.execute(s -> {
            String kind = jdbc.sql("select kind from accounts where id = ? for update").param(account).query(String.class).single();
            if (!"CREDIT".equals(kind)) {
                throw new ApiException(HttpStatus.CONFLICT, "not_a_credit_card", "only a credit card is paid off");
            }
            String id = "cp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
            jdbc.sql("insert into card_payments (id, account_id, amount_cents) values (?, ?, ?)").params(id, account, cents).update();
            ledger.post(Ledger.journal("card-payment:" + id, "Payment towards the card").move(cents, IssuerBooks.cardholder(account), IssuerBooks.RESERVES));
            jdbc.sql("update accounts set balance_cents = balance_cents - ? where id = ?").params(cents, account).update();
            return jdbc.sql("select balance_cents from accounts where id = ?").param(account).query(Long.class).single();
        });
    }

    public List<Statement> list(UUID account) {
        return jdbc.sql(ROW + " where account_id = ? order by period_end desc limit 24").param(account).query(Statement.class).list();
    }

    /**
     * Interest on the average daily balance: the amount owed, weighted by how long it was owed during the period,
     * times the APR for 30 days.
     */
    long interest(String acct, Instant start, Instant end, long aprBps) {
        record Move(Instant at, long cents) {}
        long balance = jdbc.sql("""
                select coalesce(sum(p.cents), 0) from postings p join journals j on j.id = p.journal_id
                where p.account = ? and j.created_at < ?""").params(acct, ts(start)).query(Long.class).single();
        List<Move> moves = jdbc.sql("""
                select j.created_at as at, p.cents from postings p join journals j on j.id = p.journal_id
                where p.account = ? and j.created_at >= ? and j.created_at < ? order by j.created_at""")
                .params(acct, ts(start), ts(end)).query(Move.class).list();
        double length = Duration.between(start, end).toMillis();
        if (length <= 0) {
            return 0;
        }
        double weighted = 0;
        Instant at = start;
        for (Move m : moves) {
            weighted += Math.max(balance, 0) * Duration.between(at, m.at()).toMillis();
            balance += m.cents();
            at = m.at();
        }
        weighted += Math.max(balance, 0) * Duration.between(at, end).toMillis();
        double averageDaily = weighted / length;
        return Math.round(averageDaily * aprBps / 10_000.0 * CYCLE_DAYS_FOR_INTEREST / 365.0);
    }

    private long paidBy(UUID account, Instant from, Instant until) {
        return sum("select coalesce(sum(amount_cents), 0) from card_payments where account_id = ? and created_at >= ? and created_at < ?",
                account, from, until);
    }

    private Instant periodStart(UUID account) {
        return latest(account).map(Statement::periodEnd)
                .orElseGet(() -> jdbc.sql("select created_at from accounts where id = ?").param(account).query(Instant.class).single());
    }

    private Optional<Statement> latest(UUID account) {
        return jdbc.sql(ROW + " where account_id = ? order by period_end desc limit 1").param(account).query(Statement.class).optional();
    }

    private Statement byId(String id) {
        return jdbc.sql(ROW + " where id = ?").param(id).query(Statement.class).single();
    }

    private long sum(String sql, UUID account, Instant from, Instant until) {
        return jdbc.sql(sql).params(account, ts(from), ts(until)).query(Long.class).single();
    }

    private static java.sql.Timestamp ts(Instant i) {
        return java.sql.Timestamp.from(i);
    }

    private static final String ROW = """
            select id, account_id, period_start, period_end, opening_cents, purchases_cents, refunds_cents, payments_cents, interest_cents,
                   fees_cents, closing_cents, minimum_due_cents, due_at from statements""";
}
