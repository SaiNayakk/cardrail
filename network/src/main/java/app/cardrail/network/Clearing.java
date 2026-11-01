package app.cardrail.network;

import app.cardrail.common.ApiException;
import app.cardrail.common.clearing.ClearingFile;
import app.cardrail.common.clearing.ClearingFile.Record;
import app.cardrail.common.clearing.ClearingFile.Type;
import app.cardrail.common.ledger.Ledger;
import app.cardrail.common.trace.TraceReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Clearing: members send Railnet files of what they want settled, and Railnet checks each record against what it
 * saw, routes it to the other side, works out interchange and its own fees, and books what each member now owes
 * or is owed. Money moves later, at the settlement cut-off ({@link Settlement}).
 *
 * <ul>
 *   <li><b>Sale</b> (from an acquirer): must match an approved authorisation, for no more than was approved.
 *       The acquirer pays the issuer interchange; each pays Railnet's fee.</li>
 *   <li><b>Credit</b> (a refund, from an acquirer): must match an approved refund authorisation.</li>
 *   <li><b>Chargeback</b> (from the issuer): must match a sale that was cleared, for no more than it.</li>
 *   <li><b>Representment</b> (from the acquirer): must answer a chargeback.</li>
 * </ul>
 *
 * A record presented twice is refused the second time, so a resent file never moves money twice.
 */
@Service
public class Clearing {

    private static final Logger log = LoggerFactory.getLogger(Clearing.class);

    public record Rejected(String type, String rrn, String reason) {}

    public record Result(String fileId, int accepted, List<Rejected> rejected) {}

    public record OutFile(long seq, String fileId, String content) {}

    private final NetworkProperties props;
    private final JdbcClient jdbc;
    private final Routing routing;
    private final Fees fees;
    private final Ledger ledger;
    private final TraceReporter trace;
    private final Clock clock;

    public Clearing(NetworkProperties props, JdbcClient jdbc, Routing routing, Fees fees, TraceReporter trace, Clock clock) {
        this.props = props;
        this.jdbc = jdbc;
        this.routing = routing;
        this.fees = fees;
        this.ledger = new Ledger(jdbc);
        this.trace = trace;
        this.clock = clock;
    }

    @Transactional
    public Result receive(String content) {
        ClearingFile.Parsed file;
        try {
            file = ClearingFile.parse(content);
        } catch (ClearingFile.ClearingException e) {
            throw ApiException.invalid("unreadable_file", e.getMessage());
        }
        var header = file.header();
        String sender = header.sender();
        if (!props.institutionId().equals(header.receiver()) || !isMember(sender)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "not_a_member", "this file isn't from a member to Railnet");
        }
        long total = file.records().stream().mapToLong(Record::amountCents).sum();
        Long inSeq = jdbc.sql("""
                insert into clearing_files (file_id, direction, member_id, business_date, content, records, total_cents)
                values (?, 'IN', ?, ?, ?, ?, ?) on conflict (direction, member_id, file_id) do nothing returning seq""")
                .params(header.fileId(), sender, header.businessDate(), content, file.records().size(), total)
                .query(Long.class).optional().orElse(null);
        if (inSeq == null) {
            throw new ApiException(HttpStatus.CONFLICT, "duplicate_file", "file " + header.fileId() + " was received before");
        }

        List<Rejected> rejected = new ArrayList<>();
        Map<String, List<Record>> outgoing = new LinkedHashMap<>();
        List<Long> routedIds = new ArrayList<>();
        for (Record r : file.records()) {
            Decision d = decide(sender, r);
            if (d.reason() != null) {
                rejected.add(new Rejected(r.type().name(), r.rrn(), d.reason()));
                jdbc.sql("""
                        insert into clearing_records (in_file_seq, type, acquirer_id, issuer_id, rrn, amount_cents, status, reject_reason)
                        values (?, ?, ?, ?, ?, ?, 'REJECTED', ?)""")
                        .params(inSeq, r.type().name(), r.acquirerId(), r.issuerId(), r.rrn(), r.amountCents(), d.reason()).update();
                continue;
            }
            Long id = jdbc.sql("""
                    insert into clearing_records (in_file_seq, type, acquirer_id, issuer_id, rrn, amount_cents, interchange_cents,
                                                  acquirer_fee_cents, issuer_fee_cents, status)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ROUTED')
                    on conflict (type, acquirer_id, rrn) where status = 'ROUTED' do nothing returning id""")
                    .params(inSeq, r.type().name(), r.acquirerId(), d.issuer(), r.rrn(), r.amountCents(), d.interchange(),
                            d.acquirerFee(), d.issuerFee())
                    .query(Long.class).optional().orElse(null);
            if (id == null) {
                rejected.add(new Rejected(r.type().name(), r.rrn(), "presented before"));
                continue;
            }
            routedIds.add(id);
            book(r, d);
            String to = r.type() == Type.CHARGEBACK ? r.acquirerId() : d.issuer();
            outgoing.computeIfAbsent(to, k -> new ArrayList<>()).add(r.withIssuer(d.issuer()).withInterchange(d.interchange()));
            trace.note(r.rrn(), Parties.RAILNET, r.type() == Type.CHARGEBACK ? Parties.acquirer(r.acquirerId()) : Parties.issuer(d.issuer()),
                    "Cleared: " + describe(r.type()) + " of " + dollars(r.amountCents())
                            + (d.interchange() > 0 ? ", interchange " + dollars(d.interchange()) : ""),
                    Map.of("file", header.fileId(), "type", r.type().name()));
        }

        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        outgoing.forEach((member, records) -> {
            String outId = "RN-" + member + "-" + inSeq;
            String out = ClearingFile.write(new ClearingFile.Header(outId, props.institutionId(), member, today,
                    LocalDateTime.now(clock.withZone(ZoneOffset.UTC))), records);
            long seq = jdbc.sql("""
                    insert into clearing_files (file_id, direction, member_id, business_date, content, records, total_cents)
                    values (?, 'OUT', ?, ?, ?, ?, ?) returning seq""")
                    .params(outId, member, today, out, records.size(), records.stream().mapToLong(Record::amountCents).sum())
                    .query(Long.class).single();
            for (Record r : records) {
                jdbc.sql("update clearing_records set out_file_seq = ? where in_file_seq = ? and rrn = ? and type = ? and status = 'ROUTED'")
                        .params(seq, inSeq, r.rrn(), r.type().name()).update();
            }
        });
        log.info("Clearing file {} from {}: {} routed, {} rejected", header.fileId(), sender, routedIds.size(), rejected.size());
        return new Result(header.fileId(), routedIds.size(), rejected);
    }

    /** Files Railnet has for a member, after the last one it took. */
    public List<OutFile> outgoing(String member, long afterSeq) {
        return jdbc.sql("""
                select seq, file_id, content from clearing_files
                where direction = 'OUT' and member_id = ? and seq > ? order by seq limit 50""")
                .params(member, afterSeq).query(OutFile.class).list();
    }

    record Decision(String reason, String issuer, long interchange, long acquirerFee, long issuerFee) {
        static Decision reject(String reason) {
            return new Decision(reason, null, 0, 0, 0);
        }
    }

    private Decision decide(String sender, Record r) {
        if (!"840".equals(r.currency())) {
            return Decision.reject("Railnet clears in US dollars only");
        }
        if (r.amountCents() <= 0) {
            return Decision.reject("no amount");
        }
        return switch (r.type()) {
            case SALE, CREDIT -> {
                if (!sender.equals(r.acquirerId())) {
                    yield Decision.reject("only the acquirer presents its own transactions");
                }
                var auth = approvedAuthorisation(r.acquirerId(), r.rrn());
                if (auth.isEmpty()) {
                    yield Decision.reject("no approved authorisation with this reference");
                }
                if (r.amountCents() > auth.get().amountCents()) {
                    yield Decision.reject("more than was authorised");
                }
                String issuer = auth.get().issuerId();
                if (r.type() == Type.CREDIT) {
                    yield new Decision(null, issuer, 0, 0, 0);
                }
                String funding = routing.route(r.bin()).map(Routing.Range::funding).orElse("DEBIT");
                yield new Decision(null, issuer, fees.fee("interchange:" + funding, r.amountCents()),
                        fees.fee("scheme:ACQUIRER", r.amountCents()), fees.fee("scheme:ISSUER", r.amountCents()));
            }
            case CHARGEBACK -> {
                var sale = routedSale(r.acquirerId(), r.rrn());
                if (sale.isEmpty()) {
                    yield Decision.reject("no cleared sale with this reference");
                }
                if (!sender.equals(sale.get().issuerId())) {
                    yield Decision.reject("only the card's issuer can charge it back");
                }
                if (r.amountCents() > sale.get().amountCents()) {
                    yield Decision.reject("more than the sale");
                }
                yield new Decision(null, sale.get().issuerId(), 0, 0, 0);
            }
            case REPRESENTMENT -> {
                if (!sender.equals(r.acquirerId())) {
                    yield Decision.reject("only the acquirer answers its chargebacks");
                }
                var chargeback = jdbc.sql("""
                        select issuer_id, amount_cents from clearing_records
                        where type = 'CHARGEBACK' and acquirer_id = ? and rrn = ? and status = 'ROUTED'""")
                        .params(r.acquirerId(), r.rrn()).query(Routed.class).optional();
                if (chargeback.isEmpty()) {
                    yield Decision.reject("no chargeback to answer");
                }
                yield new Decision(null, chargeback.get().issuerId(), 0, 0, 0);
            }
        };
    }

    /** What each member owes or is owed, as of now. Settled at the next cut-off. */
    private void book(Record r, Decision d) {
        String acquirer = "member:" + r.acquirerId();
        String issuer = "member:" + d.issuer();
        long a = r.amountCents();
        var journal = Ledger.journal("clr:" + r.type() + ":" + r.acquirerId() + ":" + r.rrn(), describe(r.type()) + " " + r.rrn());
        switch (r.type()) {
            // the issuer owes the sale, less interchange it earns, plus its fee; the acquirer is owed the sale less
            // interchange and its fee; Railnet earns both fees
            case SALE -> journal.move(a - d.interchange() - d.acquirerFee(), acquirer, issuer)
                    .move(d.acquirerFee() + d.issuerFee(), "income:scheme-fees", issuer);
            case CREDIT, CHARGEBACK -> journal.move(a, issuer, acquirer);
            case REPRESENTMENT -> journal.move(a, acquirer, issuer);
        }
        ledger.post(journal);
    }

    record Authorised(String issuerId, long amountCents) {}

    record Routed(String issuerId, long amountCents) {}

    private Optional<Authorised> approvedAuthorisation(String acquirerId, String rrn) {
        return jdbc.sql("""
                select issuer_id, amount_cents from authorisations
                where acquirer_id = ? and rrn = ? and response_code = '00' and reversed_at is null and issuer_id is not null
                order by requested_at limit 1""")
                .params(acquirerId, rrn).query(Authorised.class).optional();
    }

    private Optional<Routed> routedSale(String acquirerId, String rrn) {
        return jdbc.sql("select issuer_id, amount_cents from clearing_records where type = 'SALE' and acquirer_id = ? and rrn = ? and status = 'ROUTED'")
                .params(acquirerId, rrn).query(Routed.class).optional();
    }

    private boolean isMember(String id) {
        return jdbc.sql("select count(*) from members where institution_id = ?").param(id).query(Long.class).single() > 0;
    }

    static String describe(Type type) {
        return switch (type) {
            case SALE -> "sale";
            case CREDIT -> "refund";
            case CHARGEBACK -> "chargeback";
            case REPRESENTMENT -> "representment";
        };
    }

    static String dollars(long cents) {
        return String.format("$%d.%02d", cents / 100, cents % 100);
    }
}
