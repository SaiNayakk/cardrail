package app.cardrail.issuer;

import app.cardrail.common.ApiException;
import app.cardrail.common.CommonWeb;
import app.cardrail.common.clearing.ClearingFile;
import app.cardrail.common.clearing.ClearingFile.Record;
import app.cardrail.common.clearing.ClearingFile.Type;
import app.cardrail.common.ledger.Ledger;
import app.cardrail.common.trace.TraceReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Kestrel Bank's books, and the money side of cards: what clearing posts to cardholders' accounts, what settlement
 * pays Railnet, holds that are never cleared, and disputes.
 *
 * <p>The books are double entry (debits positive). A cardholder's account is {@code cardholder:<id>}: negative when
 * Kestrel holds their money (a debit account), positive when they owe it (a credit card). The balance shown on the
 * account row always equals the books: a debit account's balance is minus its ledger balance, a credit card's what is
 * owed, and the books check is part of reconciliation.
 */
@Service
public class IssuerBooks implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IssuerBooks.class);
    static final String DUE_RAILNET = "due:railnet";
    static final String RESERVES = "bank:reserves";

    private final IssuerProperties props;
    private final JdbcClient jdbc;
    private final Ledger ledger;
    private final TransactionTemplate tx;
    private final TraceReporter trace;
    private final Clock clock;
    private final RestClient network;

    public IssuerBooks(IssuerProperties props, JdbcClient jdbc, TransactionTemplate tx, TraceReporter trace, Clock clock) {
        this.props = props;
        this.jdbc = jdbc;
        this.ledger = new Ledger(jdbc);
        this.tx = tx;
        this.trace = trace;
        this.clock = clock;
        this.network = CommonWeb.internalClient(props.networkUrl(), props.internalKey(), Duration.ofSeconds(10));
    }

    public Ledger ledger() {
        return ledger;
    }

    static String cardholder(UUID accountId) {
        return "cardholder:" + accountId;
    }

    /** An account's opening money: a debit account's deposit, a credit card's opening debt. In the caller's transaction. */
    public void open(UUID accountId, String kind, long balanceCents) {
        if (balanceCents == 0) {
            return;
        }
        var journal = Ledger.journal("open:" + accountId, "Opening balance");
        if ("DEBIT".equals(kind)) {
            journal.move(balanceCents, cardholder(accountId), RESERVES);
        } else {
            journal.move(balanceCents, RESERVES, cardholder(accountId));
        }
        ledger.post(journal);
    }

    /** Accounts opened before the books existed get their opening journal. */
    @Override
    public void run(ApplicationArguments args) {
        record Row(UUID id, String kind, long balanceCents) {}
        for (Row row : jdbc.sql("select id, kind, balance_cents from accounts").query(Row.class).list()) {
            if (!ledger.posted("open:" + row.id())) {
                tx.executeWithoutResult(s -> open(row.id(), row.kind(), row.balanceCents()));
            }
        }
    }

    // ---- clearing: what Railnet sends us

    @Scheduled(fixedDelayString = "${issuer.clearing-every:30s}", initialDelayString = "${issuer.clearing-every:30s}")
    public void cycle() {
        try {
            collectFiles();
            collectSettlements();
            sendChargebacks();
            expireHolds();
        } catch (RestClientException e) {
            log.debug("Railnet isn't answering ({}); clearing waits for the next round", e.getMessage());
        }
    }

    record OutFile(long seq, String fileId, String content) {}

    public void collectFiles() {
        long after = Long.parseLong(cursor("files").orElse("0"));
        OutFile[] files = network.get().uri("/internal/v1/clearing/files?member={m}&after={a}", props.institutionId(), after)
                .retrieve().body(OutFile[].class);
        for (OutFile file : files == null ? new OutFile[0] : files) {
            for (Record r : ClearingFile.parse(file.content()).records()) {
                tx.executeWithoutResult(s -> post(r));
            }
            setCursor("files", String.valueOf(file.seq()));
        }
    }

    record Auth(UUID id, UUID accountId, String kind, String accountKind, long holdCents, String status) {}

    private Optional<Auth> lockAuth(String acquirerId, String rrn) {
        return jdbc.sql("""
                select a.id, a.account_id, a.kind, acc.kind as account_kind, a.hold_cents, a.status
                from authorisations a join accounts acc on acc.id = a.account_id
                where a.acquirer_id = ? and a.rrn = ? for update of a""")
                .params(acquirerId, rrn).query(Auth.class).optional();
    }

    /** One clearing record, once: its journal key makes a repeat harmless. */
    void post(Record r) {
        String key = "clr:" + r.type() + ":" + r.acquirerId() + ":" + r.rrn();
        if (ledger.posted(key)) {
            return;
        }
        Optional<Auth> found = lockAuth(r.acquirerId(), r.rrn());
        if (found.isEmpty()) {
            // Nothing to post it to: kept aside for a person, and reconciliation reports it.
            ledger.post(Ledger.journal(key, "Unmatched " + r.type() + " " + r.rrn()).move(r.amountCents(), DUE_RAILNET, "suspense:unmatched"));
            log.warn("Clearing record {} {} matches no authorisation; held in suspense", r.type(), r.rrn());
            return;
        }
        Auth auth = found.get();
        String account = cardholder(auth.accountId());
        long a = r.amountCents();
        switch (r.type()) {
            case SALE -> {
                // the cardholder pays; Kestrel owes Railnet the sale and earns interchange on it
                ledger.post(Ledger.journal(key, "Purchase " + r.rrn() + " at " + r.merchantId())
                        .move(a, DUE_RAILNET, account)
                        .move(r.interchangeCents(), "income:interchange", DUE_RAILNET));
                jdbc.sql("""
                        update authorisations set status = 'CLEARED', hold_cents = 0, cleared_cents = cleared_cents + ?,
                               cleared_at = now(), merchant_id = ?, terminal_id = ?
                        where id = ?""").params(a, r.merchantId(), r.terminalId(), auth.id()).update();
                adjustBalance(auth.accountId(), auth.accountKind(), -a);
            }
            case CREDIT -> {
                ledger.post(Ledger.journal(key, "Refund " + r.rrn()).move(a, account, DUE_RAILNET));
                jdbc.sql("update authorisations set status = 'CLEARED', cleared_cents = ?, cleared_at = now() where id = ?")
                        .params(a, auth.id()).update();
                adjustBalance(auth.accountId(), auth.accountKind(), a);
            }
            case REPRESENTMENT -> {
                // the merchant showed the purchase was good: the cardholder is charged again
                ledger.post(Ledger.journal(key, "Dispute answered by the merchant " + r.rrn()).move(a, DUE_RAILNET, account));
                jdbc.sql("update disputes set status = 'REPRESENTED', updated_at = now() where acquirer_id = ? and rrn = ?")
                        .params(r.acquirerId(), r.rrn()).update();
                adjustBalance(auth.accountId(), auth.accountKind(), -a);
            }
            case CHARGEBACK -> log.warn("Railnet sent Kestrel Bank its own kind of record ({}); ignored", r.rrn());
        }
        trace.note(r.rrn(), Parties.KESTREL, Parties.KESTREL, "Posted: " + describe(r.type()) + " of " + dollars(a)
                + (r.interchangeCents() > 0 ? "; interchange earned " + dollars(r.interchangeCents()) : ""), Map.of("account", account));
    }

    /** The account row follows the books: money in or out of a debit account; owed less or more on a credit card. */
    private void adjustBalance(UUID accountId, String kind, long moneyIn) {
        jdbc.sql("update accounts set balance_cents = balance_cents + ? where id = ?")
                .params("DEBIT".equals(kind) ? moneyIn : -moneyIn, accountId).update();
    }

    // ---- settlement: what we pay Railnet

    record Item(String type, String acquirerId, String issuerId, String rrn, long amountCents, long interchangeCents, long feeCents) {}

    record Report(String settlementId, LocalDate businessDate, String memberId, long netCents, List<Item> items) {}

    public void collectSettlements() {
        String after = cursor("settlements").orElse(null);
        Report[] reports = network.get().uri(after == null ? "/internal/v1/settlements?member={m}" : "/internal/v1/settlements?member={m}&after={a}",
                        after == null ? new Object[] {props.institutionId()} : new Object[] {props.institutionId(), after})
                .retrieve().body(Report[].class);
        for (Report report : reports == null ? new Report[0] : reports) {
            tx.executeWithoutResult(s -> {
                book(report);
                setCursor("settlements", report.settlementId());
            });
        }
    }

    /** Railnet's fee, then the net payment through the settlement bank, which leaves nothing due either way. */
    void book(Report report) {
        long fees = report.items().stream().mapToLong(Item::feeCents).sum();
        if (ledger.post(Ledger.journal("stl:" + report.settlementId(), "Settlement " + report.settlementId())
                .move(fees, DUE_RAILNET, "expense:scheme-fees")
                .move(report.netCents(), RESERVES, DUE_RAILNET))) {
            log.info("Settlement {}: Kestrel Bank {} {}", report.settlementId(), report.netCents() >= 0 ? "pays" : "receives",
                    dollars(Math.abs(report.netCents())));
        }
    }

    // ---- disputes

    public record DisputeView(String id, String rrn, long amountCents, String reasonCode, String status) {}

    /** A cardholder disputes a cleared purchase: they get the money back now, and the merchant's bank is charged back. */
    public DisputeView dispute(String rrn, String reasonCode) {
        return tx.execute(s -> {
            record Row(UUID id, UUID accountId, String acquirerId, String kind, String accountKind, long clearedCents, String status) {}
            Row row = jdbc.sql("""
                    select a.id, a.account_id, a.acquirer_id, a.kind, acc.kind as account_kind, a.cleared_cents, a.status
                    from authorisations a join accounts acc on acc.id = a.account_id
                    where a.rrn = ? and a.kind = 'PURCHASE' for update of a""")
                    .param(rrn).query(Row.class).optional()
                    .orElseThrow(() -> ApiException.notFound("purchase " + rrn));
            if (!"CLEARED".equals(row.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "not_cleared",
                        "only a purchase that has been cleared can be disputed; an uncleared one can still be reversed by the merchant");
            }
            String id = "dsp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
            boolean created = jdbc.sql("""
                    insert into disputes (id, authorisation_id, account_id, acquirer_id, rrn, amount_cents, reason_code, status)
                    values (?, ?, ?, ?, ?, ?, ?, 'OPEN') on conflict (acquirer_id, rrn) do nothing""")
                    .params(id, row.id(), row.accountId(), row.acquirerId(), rrn, row.clearedCents(), reasonCode).update() == 1;
            if (!created) {
                throw new ApiException(HttpStatus.CONFLICT, "already_disputed", "this purchase is disputed already");
            }
            ledger.post(Ledger.journal("dispute:" + id, "Dispute " + rrn + ": money back while the merchant is asked")
                    .move(row.clearedCents(), cardholder(row.accountId()), DUE_RAILNET));
            adjustBalance(row.accountId(), row.accountKind(), row.clearedCents());
            trace.note(rrn, Parties.KESTREL, Parties.KESTREL, "Disputed by the cardholder (reason " + reasonCode
                    + "): " + dollars(row.clearedCents()) + " given back while the merchant is asked", Map.of("dispute", id));
            return new DisputeView(id, rrn, row.clearedCents(), reasonCode, "OPEN");
        });
    }

    /** Disputes not yet charged back go to Railnet in a clearing file of Kestrel Bank's own. */
    public void sendChargebacks() {
        record Open(String id, String acquirerId, String rrn, long amountCents, String reasonCode, String authCode, String mcc,
                    String merchantId, String terminalId, String bin, String last4) {}
        List<Open> open = jdbc.sql("""
                select d.id, d.acquirer_id, d.rrn, d.amount_cents, d.reason_code, a.auth_code, coalesce(a.mcc, '0000') as mcc,
                       coalesce(a.merchant_id, '') as merchant_id, coalesce(a.terminal_id, '') as terminal_id, c.bin, c.last4
                from disputes d join authorisations a on a.id = d.authorisation_id join cards c on c.id = a.card_id
                where d.status = 'OPEN' order by d.created_at limit 500""")
                .query(Open.class).list();
        if (open.isEmpty()) {
            return;
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        List<Record> records = new ArrayList<>();
        for (Open d : open) {
            records.add(new Record(Type.CHARGEBACK, d.acquirerId(), props.institutionId(), d.rrn(), d.authCode(), d.amountCents(),
                    "840", today, d.mcc(), d.merchantId(), d.terminalId(), d.bin(), d.last4(), 0, d.reasonCode(), null, d.id()));
        }
        String fileId = "KB-" + System.currentTimeMillis();
        String file = ClearingFile.write(new ClearingFile.Header(fileId, props.institutionId(), "900001", today,
                LocalDateTime.now(clock.withZone(ZoneOffset.UTC))), records);
        record Rejected(String type, String rrn, String reason) {}
        record Result(String fileId, int accepted, List<Rejected> rejected) {}
        Result result = network.post().uri("/internal/v1/clearing/files").contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                .body(file).retrieve().body(Result.class);
        var rejectedRrns = result.rejected().stream().map(Rejected::rrn).toList();
        for (Open d : open) {
            if (!rejectedRrns.contains(d.rrn())) {
                jdbc.sql("update disputes set status = 'CHARGED_BACK', updated_at = now() where id = ?").param(d.id()).update();
                trace.note(d.rrn(), Parties.KESTREL, Parties.RAILNET, "Chargeback of " + dollars(d.amountCents())
                        + " (reason " + d.reasonCode() + ")", Map.of("dispute", d.id(), "file", fileId));
            } else {
                log.warn("Railnet refused the chargeback for {}: {}", d.rrn(), result.rejected());
            }
        }
    }

    /** An approval never cleared doesn't hold the cardholder's money for ever. */
    public void expireHolds() {
        int days = props.holdDays() == null ? 7 : props.holdDays();
        int expired = jdbc.sql("""
                update authorisations set status = 'EXPIRED', hold_cents = 0
                where status = 'APPROVED' and kind = 'PURCHASE' and created_at < now() - make_interval(days => ?)""")
                .param(days).update();
        if (expired > 0) {
            log.info("Released {} holds older than {} days", expired, days);
        }
    }

    private Optional<String> cursor(String name) {
        return jdbc.sql("select value from cursors where name = ?").param(name).query(String.class).optional();
    }

    private void setCursor(String name, String value) {
        jdbc.sql("insert into cursors (name, value) values (?, ?) on conflict (name) do update set value = excluded.value")
                .params(name, value).update();
    }

    static String describe(Type type) {
        return switch (type) {
            case SALE -> "purchase";
            case CREDIT -> "refund";
            case CHARGEBACK -> "chargeback";
            case REPRESENTMENT -> "the merchant's answer to a dispute";
        };
    }

    static String dollars(long cents) {
        return String.format("$%d.%02d", cents / 100, cents % 100);
    }
}
