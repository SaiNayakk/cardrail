package app.cardrail.acquirer;

import app.cardrail.common.ApiException;
import app.cardrail.common.CommonWeb;
import app.cardrail.common.clearing.ClearingFile;
import app.cardrail.common.clearing.ClearingFile.Record;
import app.cardrail.common.clearing.ClearingFile.Type;
import app.cardrail.common.ledger.Ledger;
import app.cardrail.common.trace.TraceReporter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Railpay Acquiring's money side: presenting what merchants captured to Railnet, booking what Railnet settles, paying
 * merchants, and chargebacks.
 *
 * <p>The books are double entry (debits positive). For a sale of A with a merchant discount (MDR) of m, Railnet owes
 * us A and we owe the merchant A - m: the MDR is our income. At settlement Railnet keeps interchange and its fee, and
 * pays the rest; what we keep is the MDR less those, which on a small debit payment can be less than nothing.
 *
 * <p>A merchant is paid only what has settled (sales less refunds, its fees, chargebacks), and never a negative
 * amount: a merchant that owes after chargebacks has it taken from its next payout.
 */
@Service
public class AcquirerBooks {

    private static final Logger log = LoggerFactory.getLogger(AcquirerBooks.class);
    static final String DUE_RAILNET = "due:railnet";
    static final String BANK = "bank:operating";

    private final AcquirerProperties props;
    private final JdbcClient jdbc;
    private final Ledger ledger;
    private final TransactionTemplate tx;
    private final TraceReporter trace;
    private final ObjectMapper json;
    private final Clock clock;
    private final RestClient network;
    private final RestClient gateway;

    public AcquirerBooks(AcquirerProperties props, JdbcClient jdbc, TransactionTemplate tx, TraceReporter trace, ObjectMapper json,
                         Clock clock) {
        this.props = props;
        this.jdbc = jdbc;
        this.ledger = new Ledger(jdbc);
        this.tx = tx;
        this.trace = trace;
        this.json = json;
        this.clock = clock;
        this.network = CommonWeb.internalClient(props.networkUrl(), props.internalKey(), Duration.ofSeconds(15));
        this.gateway = CommonWeb.internalClient(props.gatewayUrl(), props.internalKey(), Duration.ofSeconds(5));
    }

    public Ledger ledger() {
        return ledger;
    }

    static String merchant(String mid) {
        return "merchant:" + mid;
    }

    @Scheduled(fixedDelayString = "${acquirer.clearing-every:2m}", initialDelayString = "${acquirer.clearing-every:2m}")
    public void cycle() {
        try {
            present();
            collectFiles();
            collectSettlements();
            payOut();
        } catch (RestClientException e) {
            log.debug("Railnet isn't answering ({}); clearing waits for the next round", e.getMessage());
        }
        notifyGateway();
    }

    // ---- presenting: what merchants captured and refunded, and answers to chargebacks

    record ToPresent(String rrn, String kind, String status, long amountCents, long capturedCents, String authCode, String mid,
                     String mcc, String tid, String bin, String last4, int mdrBps, String originalRrn, java.time.Instant createdAt) {}

    record ToRepresent(String disputeId, String rrn, long amountCents, String reasonCode, String authCode, String mid, String mcc,
                       String tid, String bin, String last4) {}

    record Rejected(String type, String rrn, String reason) {}

    record Result(String fileId, int accepted, List<Rejected> rejected) {}

    public void present() {
        List<ToPresent> due = jdbc.sql("""
                select a.rrn, a.kind, a.status, a.amount_cents, a.captured_cents, a.auth_code, m.mid, m.mcc, a.tid, a.bin, a.last4,
                       m.mdr_bps, a.original_rrn, a.created_at
                from authorisations a join merchants m on m.id = a.merchant_id
                where a.presented_file is null
                  and ((a.kind = 'PURCHASE' and a.status = 'CAPTURED') or (a.kind = 'REFUND' and a.status = 'APPROVED'))
                order by a.created_at limit 1000""")
                .query(ToPresent.class).list();
        List<ToRepresent> answers = jdbc.sql("""
                select d.id as dispute_id, d.rrn, d.amount_cents, d.reason_code, a.auth_code, m.mid, m.mcc, a.tid, a.bin, a.last4
                from disputes d join authorisations a on a.rrn = d.rrn join merchants m on m.id = d.merchant_id
                where d.status = 'REPRESENTING' and d.representment_file is null""")
                .query(ToRepresent.class).list();
        if (due.isEmpty() && answers.isEmpty()) {
            return;
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        List<Record> records = new ArrayList<>();
        for (ToPresent p : due) {
            boolean sale = "PURCHASE".equals(p.kind());
            records.add(new Record(sale ? Type.SALE : Type.CREDIT, props.institutionId(), "0", p.rrn(), p.authCode(),
                    sale ? p.capturedCents() : p.amountCents(), "840", p.createdAt().atZone(ZoneOffset.UTC).toLocalDate(), p.mcc(),
                    p.mid(), p.tid(), p.bin(), p.last4(), 0, null, p.originalRrn(), null));
        }
        for (ToRepresent r : answers) {
            records.add(new Record(Type.REPRESENTMENT, props.institutionId(), "0", r.rrn(), r.authCode(), r.amountCents(), "840",
                    today, r.mcc(), r.mid(), r.tid(), r.bin(), r.last4(), 0, r.reasonCode(), null, r.disputeId()));
        }
        String fileId = "RPA-" + System.currentTimeMillis();
        String file = ClearingFile.write(new ClearingFile.Header(fileId, props.institutionId(), "900001", today,
                LocalDateTime.now(clock.withZone(ZoneOffset.UTC))), records);
        Result result = network.post().uri("/internal/v1/clearing/files").contentType(MediaType.TEXT_PLAIN).body(file)
                .retrieve().body(Result.class);
        Map<String, String> rejected = new LinkedHashMap<>();
        result.rejected().forEach(r -> rejected.put(r.type() + ":" + r.rrn(), r.reason()));

        for (ToPresent p : due) {
            boolean sale = "PURCHASE".equals(p.kind());
            String reason = rejected.get((sale ? "SALE" : "CREDIT") + ":" + p.rrn());
            long amount = sale ? p.capturedCents() : p.amountCents();
            long mdr = sale ? (amount * p.mdrBps() + 5_000) / 10_000 : 0;
            tx.executeWithoutResult(s -> {
                jdbc.sql("update authorisations set presented_file = ?, presented_cents = ?, mdr_cents = ?, presentment_error = ? where rrn = ?")
                        .params(fileId, amount, mdr, reason, p.rrn()).update();
                if (reason != null) {
                    return;
                }
                var journal = Ledger.journal("presented:" + p.rrn(), (sale ? "Sale " : "Refund ") + p.rrn() + " for " + p.mid());
                if (sale) {
                    // Railnet owes us the sale; we owe the merchant the sale less its fee, which is our income
                    journal.move(amount - mdr, merchant(p.mid()), DUE_RAILNET).move(mdr, "income:mdr", DUE_RAILNET);
                } else {
                    journal.move(amount, DUE_RAILNET, merchant(p.mid()));
                }
                ledger.post(journal);
            });
            trace.note(p.rrn(), Parties.ACQUIRER, Parties.RAILNET, reason == null
                    ? "Presented for clearing: " + (sale ? "sale" : "refund") + " of " + dollars(amount) + (sale ? ", merchant fee " + dollars(mdr) : "")
                    : "Presentment refused: " + reason, Map.of("file", fileId));
        }
        for (ToRepresent r : answers) {
            String reason = rejected.get("REPRESENTMENT:" + r.rrn());
            tx.executeWithoutResult(s -> {
                jdbc.sql("update disputes set representment_file = ?, status = ?, updated_at = now() where id = ?")
                        .params(fileId, reason == null ? "WON" : "LOST", r.disputeId()).update();
                if (reason == null) {
                    // the money comes back from the issuer, and is the merchant's again
                    ledger.post(Ledger.journal("representment:" + r.disputeId(), "Dispute answered " + r.rrn())
                            .move(r.amountCents(), merchant(r.mid()), DUE_RAILNET));
                    notify(Map.of("type", "dispute.won", "disputeId", r.disputeId(), "rrn", r.rrn(), "mid", r.mid(),
                            "amountCents", r.amountCents(), "reasonCode", r.reasonCode()));
                }
            });
        }
        log.info("Clearing file {}: {} records, {} refused", fileId, records.size(), rejected.size());
    }

    // ---- what Railnet sends us: chargebacks

    record OutFile(long seq, String fileId, String content) {}

    public void collectFiles() {
        long after = Long.parseLong(cursor("files").orElse("0"));
        OutFile[] files = network.get().uri("/internal/v1/clearing/files?member={m}&after={a}", props.institutionId(), after)
                .retrieve().body(OutFile[].class);
        for (OutFile file : files == null ? new OutFile[0] : files) {
            for (Record r : ClearingFile.parse(file.content()).records()) {
                if (r.type() == Type.CHARGEBACK) {
                    tx.executeWithoutResult(s -> chargeback(r));
                }
            }
            setCursor("files", String.valueOf(file.seq()));
        }
    }

    void chargeback(Record r) {
        var merchant = jdbc.sql("""
                select m.id, m.mid from authorisations a join merchants m on m.id = a.merchant_id where a.rrn = ?""")
                .param(r.rrn()).query((rs, n) -> Map.entry(rs.getObject(1, UUID.class), rs.getString(2))).optional();
        if (merchant.isEmpty()) {
            log.warn("A chargeback for {} matches no payment of ours", r.rrn());
            return;
        }
        String id = r.disputeId() != null ? r.disputeId() : "dsp_" + r.rrn();
        boolean created = jdbc.sql("""
                insert into disputes (id, rrn, merchant_id, amount_cents, reason_code, status)
                values (?, ?, ?, ?, ?, 'NEEDS_RESPONSE') on conflict (id) do nothing""")
                .params(id, r.rrn(), merchant.get().getKey(), r.amountCents(), r.reasonCode() == null ? "0000" : r.reasonCode())
                .update() == 1;
        if (!created) {
            return;
        }
        // Railnet takes the money back from us, and we take it back from the merchant
        ledger.post(Ledger.journal("chargeback:" + id, "Chargeback " + r.rrn()).move(r.amountCents(), DUE_RAILNET,
                merchant(merchant.get().getValue())));
        notify(Map.of("type", "dispute.created", "disputeId", id, "rrn", r.rrn(), "mid", merchant.get().getValue(),
                "amountCents", r.amountCents(), "reasonCode", r.reasonCode() == null ? "0000" : r.reasonCode()));
        trace.note(r.rrn(), Parties.ACQUIRER, Parties.GATEWAY, "Chargeback of " + dollars(r.amountCents()) + " (reason "
                + r.reasonCode() + "): the merchant can answer it", Map.of("dispute", id));
    }

    // ---- settlement: what Railnet pays us

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

    /** Interchange and Railnet's fee are ours to pay; what remains arrives, and leaves nothing due. */
    void book(Report report) {
        long interchange = report.items().stream().filter(i -> "SALE".equals(i.type())).mapToLong(Item::interchangeCents).sum();
        long fees = report.items().stream().mapToLong(Item::feeCents).sum();
        boolean booked = ledger.post(Ledger.journal("stl:" + report.settlementId(), "Settlement " + report.settlementId())
                .move(interchange, DUE_RAILNET, "expense:interchange")
                .move(fees, DUE_RAILNET, "expense:scheme-fees")
                .move(-report.netCents(), DUE_RAILNET, BANK));
        if (!booked) {
            return;
        }
        for (Item item : report.items()) {
            switch (item.type()) {
                case "SALE", "CREDIT" -> jdbc.sql("update authorisations set settlement_id = ? where rrn = ?")
                        .params(report.settlementId(), item.rrn()).update();
                case "CHARGEBACK" -> jdbc.sql("update disputes set chargeback_settlement_id = ? where rrn = ?")
                        .params(report.settlementId(), item.rrn()).update();
                case "REPRESENTMENT" -> jdbc.sql("update disputes set representment_settlement_id = ? where rrn = ?")
                        .params(report.settlementId(), item.rrn()).update();
                default -> { }
            }
        }
        log.info("Settlement {}: Railpay Acquiring receives {} for {} records", report.settlementId(), dollars(-report.netCents()),
                report.items().size());
    }

    // ---- payouts

    public record Payout(String id, String mid, long amountCents, long salesCents, long refundsCents, long feesCents, long disputesCents) {}

    /** Pays each merchant what has settled since its last payout, if that is more than nothing. */
    public List<Payout> payOut() {
        record Due(UUID merchantId, String mid, long sales, long refunds, long fees, long chargebacks, long representments) {}
        List<Due> dues = jdbc.sql("""
                select m.id, m.mid,
                  coalesce((select sum(presented_cents) from authorisations where merchant_id = m.id and kind = 'PURCHASE'
                            and settlement_id is not null and payout_id is null), 0),
                  coalesce((select sum(presented_cents) from authorisations where merchant_id = m.id and kind = 'REFUND'
                            and settlement_id is not null and payout_id is null), 0),
                  coalesce((select sum(mdr_cents) from authorisations where merchant_id = m.id and kind = 'PURCHASE'
                            and settlement_id is not null and payout_id is null), 0),
                  coalesce((select sum(amount_cents) from disputes where merchant_id = m.id
                            and chargeback_settlement_id is not null and chargeback_payout_id is null), 0),
                  coalesce((select sum(amount_cents) from disputes where merchant_id = m.id
                            and representment_settlement_id is not null and representment_payout_id is null), 0)
                from merchants m""")
                .query((rs, n) -> new Due(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
                        rs.getLong(6), rs.getLong(7)))
                .list();
        List<Payout> paid = new ArrayList<>();
        for (Due d : dues) {
            long disputes = d.chargebacks() - d.representments();
            long amount = d.sales() - d.refunds() - d.fees() - disputes;
            if (amount <= 0) {
                continue;   // nothing to pay, or the merchant owes: it waits for more sales
            }
            String id = "po_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
            tx.executeWithoutResult(s -> {
                jdbc.sql("""
                        insert into payouts (id, merchant_id, amount_cents, sales_cents, refunds_cents, fees_cents, disputes_cents)
                        values (?, ?, ?, ?, ?, ?, ?)""")
                        .params(id, d.merchantId(), amount, d.sales(), d.refunds(), d.fees(), disputes).update();
                jdbc.sql("update authorisations set payout_id = ? where merchant_id = ? and settlement_id is not null and payout_id is null")
                        .params(id, d.merchantId()).update();
                jdbc.sql("update disputes set chargeback_payout_id = ? where merchant_id = ? and chargeback_settlement_id is not null and chargeback_payout_id is null")
                        .params(id, d.merchantId()).update();
                jdbc.sql("update disputes set representment_payout_id = ? where merchant_id = ? and representment_settlement_id is not null and representment_payout_id is null")
                        .params(id, d.merchantId()).update();
                ledger.post(Ledger.journal("payout:" + id, "Payout to " + d.mid()).move(amount, BANK, merchant(d.mid())));
                notify(Map.of("type", "payout.paid", "payoutId", id, "mid", d.mid(), "amountCents", amount, "salesCents", d.sales(),
                        "refundsCents", d.refunds(), "feesCents", d.fees(), "disputesCents", disputes));
            });
            paid.add(new Payout(id, d.mid(), amount, d.sales(), d.refunds(), d.fees(), disputes));
            log.info("Paid {} to merchant {}", dollars(amount), d.mid());
        }
        return paid;
    }

    // ---- disputes, as the merchant answers them

    public record DisputeView(String id, String rrn, String mid, long amountCents, String reasonCode, String status) {}

    public DisputeView answer(String disputeId, String evidence) {
        return tx.execute(s -> {
            var d = dispute(disputeId);
            if (!"NEEDS_RESPONSE".equals(d.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "already_answered", "this dispute was answered already");
            }
            jdbc.sql("update disputes set status = 'REPRESENTING', evidence = ?, updated_at = now() where id = ?")
                    .params(evidence, disputeId).update();
            return dispute(disputeId);
        });
    }

    public DisputeView accept(String disputeId) {
        return tx.execute(s -> {
            var d = dispute(disputeId);
            if (!"NEEDS_RESPONSE".equals(d.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "already_answered", "this dispute was answered already");
            }
            jdbc.sql("update disputes set status = 'LOST', updated_at = now() where id = ?").param(disputeId).update();
            notify(Map.of("type", "dispute.lost", "disputeId", disputeId, "rrn", d.rrn(), "mid", d.mid(), "amountCents", d.amountCents(),
                    "reasonCode", d.reasonCode()));
            return dispute(disputeId);
        });
    }

    private DisputeView dispute(String id) {
        return jdbc.sql("""
                select d.id, d.rrn, m.mid, d.amount_cents, d.reason_code, d.status from disputes d join merchants m on m.id = d.merchant_id
                where d.id = ?""")
                .param(id).query(DisputeView.class).optional().orElseThrow(() -> ApiException.notFound("dispute " + id));
    }

    public List<Payout> payouts(String mid) {
        return jdbc.sql("""
                select p.id, m.mid, p.amount_cents, p.sales_cents, p.refunds_cents, p.fees_cents, p.disputes_cents
                from payouts p join merchants m on m.id = p.merchant_id where m.mid = ? order by p.created_at desc limit 50""")
                .param(mid).query(Payout.class).list();
    }

    // ---- telling the gateway, until it hears

    private void notify(Map<String, Object> event) {
        try {
            jdbc.sql("insert into notifications (payload) values (?)").param(json.writeValueAsString(event)).update();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Scheduled(fixedDelayString = "${acquirer.notify-every:2s}", initialDelayString = "${acquirer.notify-every:2s}")
    public void notifyGateway() {
        record Due(long id, String payload, int attempts) {}
        for (Due n : jdbc.sql("""
                select id, payload, attempts from notifications where delivered_at is null and next_attempt_at <= now()
                order by id limit 50""").query(Due.class).list()) {
            try {
                gateway.post().uri("/internal/v1/notifications").contentType(MediaType.APPLICATION_JSON).body(n.payload())
                        .retrieve().toBodilessEntity();
                jdbc.sql("update notifications set delivered_at = now(), attempts = attempts + 1 where id = ?").param(n.id()).update();
            } catch (RestClientException e) {
                jdbc.sql("""
                        update notifications set attempts = attempts + 1,
                               next_attempt_at = now() + least(interval '10 minutes', interval '2 seconds' * power(2, attempts))
                        where id = ?""").param(n.id()).update();
                return;
            }
        }
    }

    private Optional<String> cursor(String name) {
        return jdbc.sql("select value from cursors where name = ?").param(name).query(String.class).optional();
    }

    private void setCursor(String name, String value) {
        jdbc.sql("insert into cursors (name, value) values (?, ?) on conflict (name) do update set value = excluded.value")
                .params(name, value).update();
    }

    static String dollars(long cents) {
        return (cents < 0 ? "-" : "") + String.format("$%d.%02d", Math.abs(cents) / 100, Math.abs(cents) % 100);
    }
}
