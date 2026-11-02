package app.cardrail.closedloop;

import app.cardrail.common.ApiException;
import app.cardrail.common.CommonWeb;
import app.cardrail.common.clearing.ClearingFile;
import app.cardrail.common.clearing.ClearingFile.Record;
import app.cardrail.common.clearing.ClearingFile.Type;
import app.cardrail.common.ledger.Ledger;
import app.cardrail.common.trace.TraceReporter;
import app.cardrail.iso8583.IsoMessage;
import app.cardrail.iso8583.Luhn;
import app.cardrail.iso8583.Subelements;
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

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static app.cardrail.iso8583.ResponseCode.*;

/**
 * Meridian, a closed loop: the cardmember's bank, the merchant's bank and the network are one company.
 *
 * <p>An acquirer that processes for a merchant (here Railpay Acquiring) sends Meridian cards straight to Meridian,
 * not to a network: Meridian decides, as an issuer would. The acquirer then submits the day's Meridian sales to
 * Meridian, which posts them to its cardmembers and pays the merchant itself, less its discount (3%). There is no
 * interchange and no network fee to share: Meridian keeps the whole discount, and sees both sides of every payment.
 */
@Service
public class Meridian {

    private static final Logger log = LoggerFactory.getLogger(Meridian.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final String PARTY = "meridian";
    static final String BANK = "bank:operating";

    public record Card(UUID id, UUID accountId, String last4, String expiry, String status) {}

    public record Issued(UUID accountId, UUID cardId, String pan, String expiry, String cid) {}

    private final MeridianApplication.Properties props;
    private final JdbcClient jdbc;
    private final MeridianKeys keys;
    private final Ledger ledger;
    private final TransactionTemplate tx;
    private final TraceReporter trace;
    private final Clock clock;
    private final RestClient gateway;

    public Meridian(MeridianApplication.Properties props, JdbcClient jdbc, MeridianKeys keys, TransactionTemplate tx, TraceReporter trace,
                    Clock clock) {
        this.props = props;
        this.jdbc = jdbc;
        this.keys = keys;
        this.ledger = new Ledger(jdbc);
        this.tx = tx;
        this.trace = trace;
        this.clock = clock;
        this.gateway = CommonWeb.internalClient(props.gatewayUrl(), props.internalKey(), Duration.ofSeconds(5));
    }

    public Ledger ledger() {
        return ledger;
    }

    // ---- cards

    public Issued open(String name, long limitCents, String fourteen, String expiry, String label) {
        String pan = fourteen + Luhn.checkDigit(fourteen);
        UUID account = UUID.randomUUID();
        UUID card = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            jdbc.sql("insert into accounts (id, name, limit_cents) values (?, ?, ?)").params(account, name, limitCents).update();
            jdbc.sql("""
                    insert into cards (id, account_id, pan_hash, pan_encrypted, last4, expiry, status, label)
                    values (?, ?, ?, ?, ?, ?, 'ACTIVE', ?)""")
                    .params(card, account, keys.panHash(pan), keys.encrypt(pan), pan.substring(pan.length() - 4), expiry, label).update();
        });
        return new Issued(account, card, pan, expiry, keys.cid(pan, expiry));
    }

    public Optional<Card> card(String pan) {
        return jdbc.sql("select id, account_id, last4, expiry, status from cards where pan_hash = ?").param(keys.panHash(pan))
                .query(Card.class).optional();
    }

    // ---- authorisation: Meridian as the issuer, reached without a network

    public IsoMessage authorise(IsoMessage request) {
        for (int field : new int[] {2, 3, 4, 11, 14, 32, 37, 49}) {
            if (!request.has(field)) {
                return request.response(FORMAT_ERROR);
            }
        }
        var seen = answer(request.get(32), request.get(37));
        if (seen.isPresent()) {
            return seen.get();
        }
        boolean refund = request.get(3).startsWith("20");
        long amount = Long.parseLong(request.get(4));
        UUID merchant = merchant(request.get(32), request.get(42), request.has(43) ? request.get(43).substring(0, 25).trim() : request.get(42));
        String pan = request.get(2);
        Optional<Card> found = card(pan);
        if (found.isEmpty()) {
            return record(request, null, merchant, refund, amount, INVALID_CARD);
        }
        Card card = found.get();
        if (refund) {
            return record(request, card, merchant, true, amount, APPROVED);
        }
        if (!"ACTIVE".equals(card.status())) {
            return record(request, card, merchant, false, amount, "LOST".equals(card.status()) ? LOST_CARD : RESTRICTED_CARD);
        }
        YearMonth expires = YearMonth.of(2000 + Integer.parseInt(card.expiry().substring(0, 2)), Integer.parseInt(card.expiry().substring(2)));
        if (!card.expiry().equals(request.get(14)) || YearMonth.now(clock.withZone(ZoneOffset.UTC)).isAfter(expires)) {
            return record(request, card, merchant, false, amount, EXPIRED_CARD);
        }
        if (request.has(48)) {
            String cid = Subelements.decode(request.get(48)).get(Subelements.CVV2);
            if (cid != null && !MessageDigest.isEqual(cid.getBytes(), keys.cid(pan, card.expiry()).getBytes())) {
                return record(request, card, merchant, false, amount, CVV2_MISMATCH);
            }
        }
        return tx.execute(s -> {
            record Account(long balanceCents, long limitCents) {}
            Account a = jdbc.sql("select balance_cents, limit_cents from accounts where id = ? for update").param(card.accountId())
                    .query(Account.class).single();
            long held = jdbc.sql("select coalesce(sum(hold_cents), 0) from authorisations where account_id = ? and status = 'APPROVED'")
                    .param(card.accountId()).query(Long.class).single();
            return record(request, card, merchant, false, amount, a.balanceCents() + held + amount > a.limitCents() ? INSUFFICIENT_FUNDS : APPROVED);
        });
    }

    private IsoMessage record(IsoMessage request, Card card, UUID merchant, boolean refund, long amount, String code) {
        boolean approved = APPROVED.equals(code);
        jdbc.sql("""
                insert into authorisations (id, card_id, account_id, merchant_id, acquirer_id, rrn, kind, amount_cents, response_code,
                                            auth_code, status, hold_cents)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict (acquirer_id, rrn) do nothing""")
                .params(UUID.randomUUID(), card == null ? null : card.id(), card == null ? null : card.accountId(), merchant, request.get(32),
                        request.get(37), refund ? "REFUND" : "PURCHASE", amount, code, approved ? authCode() : null,
                        approved ? "APPROVED" : "DECLINED", approved && !refund ? amount : 0)
                .update();
        return answer(request.get(32), request.get(37)).map(r -> respond(request, r)).orElseThrow();
    }

    /** A reversal: the hold goes, whatever the original's fate. A reversal of something never seen is remembered. */
    public IsoMessage reverse(IsoMessage reversal) {
        int changed = jdbc.sql("update authorisations set status = 'REVERSED', hold_cents = 0 where acquirer_id = ? and rrn = ? and status = 'APPROVED'")
                .params(reversal.get(32), reversal.get(37)).update();
        if (changed == 0) {
            jdbc.sql("""
                    insert into authorisations (id, acquirer_id, rrn, amount_cents, response_code, status)
                    values (?, ?, ?, 0, '25', 'REVERSED') on conflict (acquirer_id, rrn) do nothing""")
                    .params(UUID.randomUUID(), reversal.get(32), reversal.get(37)).update();
        }
        return reversal.response(APPROVED);
    }

    private Optional<IsoMessage> answer(String acquirerId, String rrn) {
        record Row(String responseCode, String authCode) {}
        return jdbc.sql("select response_code, auth_code from authorisations where acquirer_id = ? and rrn = ?").params(acquirerId, rrn)
                .query(Row.class).optional().map(r -> {
                    IsoMessage m = new IsoMessage("0110").set(39, r.responseCode());
                    if (r.authCode() != null) {
                        m.set(38, r.authCode());
                    }
                    return m;
                });
    }

    private static IsoMessage respond(IsoMessage request, IsoMessage decided) {
        IsoMessage response = request.response(decided.get(39));
        if (decided.has(38)) {
            response.set(38, decided.get(38));
        }
        return response;
    }

    /** The merchant, known by its acquirer and merchant id; signed up with Meridian the first time it takes a Meridian card. */
    private UUID merchant(String acquirerId, String mid, String name) {
        if (mid == null) {
            return null;
        }
        jdbc.sql("insert into merchants (id, acquirer_id, mid, name) values (?, ?, ?, ?) on conflict (acquirer_id, mid) do nothing")
                .params(UUID.randomUUID(), acquirerId, mid.trim(), name).update();
        return jdbc.sql("select id from merchants where acquirer_id = ? and mid = ?").params(acquirerId, mid.trim()).query(UUID.class).single();
    }

    // ---- submissions: the acquirer sends the day's Meridian sales; Meridian posts them and owes the merchant

    public record Submitted(String fileId, int accepted, List<Map<String, String>> rejected) {}

    public Submitted submit(String content) {
        ClearingFile.Parsed file;
        try {
            file = ClearingFile.parse(content);
        } catch (ClearingFile.ClearingException e) {
            throw ApiException.invalid("unreadable_file", e.getMessage());
        }
        if (!props.institutionId().equals(file.header().receiver())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "not_for_meridian", "this file isn't for Meridian");
        }
        int accepted = 0;
        List<Map<String, String>> rejected = new ArrayList<>();
        for (Record r : file.records()) {
            String reason = tx.execute(s -> post(r));
            if (reason == null) {
                accepted++;
            } else {
                rejected.add(Map.of("type", r.type().name(), "rrn", r.rrn(), "reason", reason));
            }
        }
        return new Submitted(file.header().fileId(), accepted, rejected);
    }

    private String post(Record r) {
        if (r.type() != Type.SALE && r.type() != Type.CREDIT) {
            return "Meridian takes sales and refunds";
        }
        record Auth(UUID id, UUID accountId, UUID merchantId, long amountCents, String status, String kind, int discountBps, String mid) {}
        var found = jdbc.sql("""
                select a.id, a.account_id, a.merchant_id, a.amount_cents, a.status, a.kind, m.discount_bps, m.mid
                from authorisations a join merchants m on m.id = a.merchant_id
                where a.acquirer_id = ? and a.rrn = ? for update of a""")
                .params(r.acquirerId(), r.rrn()).query(Auth.class).optional();
        if (found.isEmpty() || !"APPROVED".equals(found.get().status())) {
            return found.isPresent() && "SUBMITTED".equals(found.get().status()) ? "submitted before" : "no approved authorisation with this reference";
        }
        Auth a = found.get();
        boolean sale = r.type() == Type.SALE;
        if (sale != "PURCHASE".equals(a.kind()) || r.amountCents() > a.amountCents()) {
            return sale ? "more than was approved" : "not a refund Meridian approved";
        }
        long amount = r.amountCents();
        long discount = sale ? (amount * a.discountBps() + 5_000) / 10_000 : 0;
        String cardmember = "cardmember:" + a.accountId();
        String merchant = "merchant:" + a.mid();
        var journal = Ledger.journal("submitted:" + r.type() + ":" + r.acquirerId() + ":" + r.rrn(), (sale ? "Sale " : "Refund ") + r.rrn());
        if (sale) {
            // the cardmember owes the sale; Meridian owes the merchant the sale less its discount, and earns the discount
            journal.move(amount - discount, merchant, cardmember).move(discount, "income:discount", cardmember);
        } else {
            journal.move(amount, cardmember, merchant);
        }
        ledger.post(journal);
        jdbc.sql("update authorisations set status = 'SUBMITTED', hold_cents = 0, submitted_cents = ? where id = ?").params(amount, a.id()).update();
        jdbc.sql("update accounts set balance_cents = balance_cents + ? where id = ?").params(sale ? amount : -amount, a.accountId()).update();
        trace.note(r.rrn(), PARTY, PARTY, "Submitted: " + (sale ? "sale" : "refund") + " of " + dollars(amount)
                + (sale ? "; Meridian keeps its " + dollars(discount) + " discount and owes the merchant the rest" : ""), Map.of("merchant", a.mid()));
        return null;
    }

    // ---- paying merchants directly

    public record Payout(String id, String mid, long amountCents, long salesCents, long refundsCents, long discountCents) {}

    private record Due(UUID id, String mid, long sales, long refunds, long discount) {}

    @Scheduled(fixedDelayString = "${meridian.payout-every:5m}", initialDelayString = "${meridian.payout-every:5m}")
    public List<Payout> payOut() {
        List<Due> dues = jdbc.sql("""
                select m.id, m.mid,
                  coalesce(sum(a.submitted_cents) filter (where a.kind = 'PURCHASE'), 0),
                  coalesce(sum(a.submitted_cents) filter (where a.kind = 'REFUND'), 0),
                  coalesce(sum((a.submitted_cents * m.discount_bps + 5000) / 10000) filter (where a.kind = 'PURCHASE'), 0)
                from merchants m join authorisations a on a.merchant_id = m.id
                where a.status = 'SUBMITTED' and a.payout_id is null group by m.id, m.mid""")
                .query((rs, n) -> new Due(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)))
                .list();
        List<Payout> paid = new ArrayList<>();
        for (Due d : dues) {
            long amount = d.sales() - d.refunds() - d.discount();
            if (amount <= 0) {
                continue;
            }
            String id = "mpo_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
            tx.executeWithoutResult(s -> {
                jdbc.sql("insert into payouts (id, merchant_id, amount_cents, sales_cents, refunds_cents, discount_cents) values (?, ?, ?, ?, ?, ?)")
                        .params(id, d.id(), amount, d.sales(), d.refunds(), d.discount()).update();
                jdbc.sql("update authorisations set payout_id = ? where merchant_id = ? and status = 'SUBMITTED' and payout_id is null")
                        .params(id, d.id()).update();
                ledger.post(Ledger.journal("payout:" + id, "Payout to " + d.mid()).move(amount, BANK, "merchant:" + d.mid()));
            });
            paid.add(new Payout(id, d.mid(), amount, d.sales(), d.refunds(), d.discount()));
            tellGateway(id, d, amount);
        }
        return paid;
    }

    /** Railpay shows its merchants every payout, Meridian's too. Best effort: Meridian's own books are the record. */
    private void tellGateway(String id, Due d, long amount) {
        try {
            gateway.post().uri("/internal/v1/notifications").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("type", "payout.paid", "payoutId", id, "mid", d.mid(), "amountCents", amount, "salesCents", d.sales(),
                            "refundsCents", d.refunds(), "feesCents", d.discount(), "disputesCents", 0, "source", "meridian"))
                    .retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("Couldn't tell Railpay about Meridian payout {}: {}", id, e.getMessage());
        }
    }

    public List<Payout> payouts(String mid) {
        return jdbc.sql("""
                select p.id, m.mid, p.amount_cents, p.sales_cents, p.refunds_cents, p.discount_cents
                from payouts p join merchants m on m.id = p.merchant_id where m.mid = ? order by p.created_at desc limit 50""")
                .param(mid).query(Payout.class).list();
    }

    private static String authCode() {
        StringBuilder code = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            code.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        return code.toString();
    }

    static String dollars(long cents) {
        return String.format("$%d.%02d", cents / 100, cents % 100);
    }
}
