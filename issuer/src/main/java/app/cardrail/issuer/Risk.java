package app.cardrail.issuer;

import app.cardrail.iso8583.IsoMessage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static app.cardrail.iso8583.ResponseCode.*;

/**
 * What Kestrel Bank checks after "is this the real card" and before "is there money": the cardholder's own rules,
 * how fast the card is being used, and a fraud score.
 *
 * <p>The fraud score is a handful of explainable signals added up, not a model: a payment far above what this card
 * usually spends, a large one, a burst of payments, a risky kind of merchant, a large first payment. Each decision
 * records its score and the reasons, so a decline can be explained to the cardholder. At 80 or more a payment is
 * declined as suspected fraud; from 50, an online payment must be confirmed by the cardholder (step-up), unless it
 * already was.
 */
@Component
public class Risk {

    static final int DECLINE_AT = 80;
    static final int STEP_UP_AT = 50;
    static final int VELOCITY_WINDOW_MINUTES = 10;
    static final int VELOCITY_MAX = 5;
    /** Gambling, money transfer, quasi-cash, direct marketing: where stolen cards are tried first. */
    static final Set<String> RISKY_MCCS = Set.of("7995", "4829", "6051", "5967");

    public record Controls(boolean onlineEnabled, Long singleLimitCents, Long dailyLimitCents, List<String> blockedMccs,
                           boolean stepUpAlways, boolean shared) {

        static final Controls NONE = new Controls(true, null, null, List.of(), false, false);
    }

    /** @param code a decline code, or null; @param stepUp the cardholder must confirm it first */
    public record Verdict(String code, boolean stepUp, int score, String reasons) {}

    private final JdbcClient jdbc;

    public Risk(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Verdict assess(UUID cardId, IsoMessage request, long amountCents, boolean authenticated) {
        Controls controls = controls(cardId);
        String mcc = request.get(18);
        boolean online = request.has(22) && request.get(22).startsWith("81");

        if (online && !controls.onlineEnabled()) {
            return new Verdict(NOT_PERMITTED_TO_CARDHOLDER, false, 0, "online payments are turned off for this card");
        }
        if (mcc != null && controls.blockedMccs().contains(mcc)) {
            return new Verdict(NOT_PERMITTED_TO_CARDHOLDER, false, 0, "the cardholder blocks merchant category " + mcc);
        }
        if (controls.singleLimitCents() != null && amountCents > controls.singleLimitCents()) {
            return new Verdict(EXCEEDS_LIMIT, false, 0, "over the cardholder's limit per payment");
        }
        if (controls.dailyLimitCents() != null && spentToday(cardId) + amountCents > controls.dailyLimitCents()) {
            return new Verdict(EXCEEDS_LIMIT, false, 0, "over the cardholder's daily limit");
        }
        int recent = controls.shared() ? 0 : approvedInLast(cardId, VELOCITY_WINDOW_MINUTES);
        if (recent >= VELOCITY_MAX) {
            return new Verdict(EXCEEDS_FREQUENCY, false, 0, recent + " payments in " + VELOCITY_WINDOW_MINUTES + " minutes");
        }

        int score = 0;
        List<String> reasons = new ArrayList<>();
        var history = controls.shared() ? new History(-1, 0) : history(cardId);
        if (history.count() >= 3 && amountCents > 3 * history.averageCents()) {
            score += 30;
            reasons.add("more than 3 times this card's usual payment");
        }
        if (amountCents >= 50_000) {
            score += 20;
            reasons.add("$500 or more");
        }
        if (recent >= 3) {
            score += 25;
            reasons.add(recent + " payments in the last " + VELOCITY_WINDOW_MINUTES + " minutes");
        }
        if (mcc != null && RISKY_MCCS.contains(mcc)) {
            score += 25;
            reasons.add("a risky kind of merchant (" + mcc + ")");
        }
        if (history.count() == 0 && amountCents >= 10_000) {
            score += 20;
            reasons.add("a large first payment");
        }
        score = Math.min(score, 100);
        String why = reasons.isEmpty() ? "nothing unusual" : String.join("; ", reasons);

        if (score >= DECLINE_AT) {
            return new Verdict(app.cardrail.iso8583.ResponseCode.SUSPECTED_FRAUD, false, score, why);
        }
        boolean wantsStepUp = online && (score >= STEP_UP_AT || controls.stepUpAlways());
        if (wantsStepUp && !authenticated) {
            return new Verdict(STEP_UP_REQUIRED, true, score, controls.stepUpAlways() ? "the cardholder confirms every online payment" : why);
        }
        return new Verdict(null, false, score, why);
    }

    public Controls controls(UUID cardId) {
        return jdbc.sql("""
                select online_enabled, single_limit_cents, daily_limit_cents, blocked_mccs, step_up_always, shared
                from card_controls where card_id = ?""")
                .param(cardId)
                .query((rs, n) -> {
                    Array mccs = rs.getArray(4);
                    return new Controls(rs.getBoolean(1), (Long) rs.getObject(2), (Long) rs.getObject(3),
                            mccs == null ? List.of() : List.of((String[]) mccs.getArray()), rs.getBoolean(5), rs.getBoolean(6));
                })
                .optional().orElse(Controls.NONE);
    }

    public void setControls(UUID cardId, Controls c) {
        jdbc.sql("""
                insert into card_controls (card_id, online_enabled, single_limit_cents, daily_limit_cents, blocked_mccs, step_up_always, shared)
                values (?, ?, ?, ?, ?::text[], ?, ?)
                on conflict (card_id) do update set online_enabled = excluded.online_enabled,
                    single_limit_cents = excluded.single_limit_cents, daily_limit_cents = excluded.daily_limit_cents,
                    blocked_mccs = excluded.blocked_mccs, step_up_always = excluded.step_up_always, shared = excluded.shared,
                    updated_at = now()""")
                .params(cardId, c.onlineEnabled(), c.singleLimitCents(), c.dailyLimitCents(),
                        "{" + String.join(",", c.blockedMccs()) + "}", c.stepUpAlways(), c.shared())
                .update();
    }

    record History(long count, long averageCents) {}

    private History history(UUID cardId) {
        return jdbc.sql("""
                select count(*), coalesce(avg(amount_cents), 0)::bigint from authorisations
                where card_id = ? and kind = 'PURCHASE' and status in ('APPROVED', 'CLEARED')""")
                .param(cardId).query((rs, n) -> new History(rs.getLong(1), rs.getLong(2))).single();
    }

    private int approvedInLast(UUID cardId, int minutes) {
        return jdbc.sql("""
                select count(*)::int from authorisations
                where card_id = ? and kind = 'PURCHASE' and response_code = '00' and created_at > now() - make_interval(mins => ?)""")
                .params(cardId, minutes).query(Integer.class).single();
    }

    private long spentToday(UUID cardId) {
        var since = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        return jdbc.sql("""
                select coalesce(sum(amount_cents), 0) from authorisations
                where card_id = ? and kind = 'PURCHASE' and response_code = '00' and status <> 'REVERSED' and created_at >= ?""")
                .params(cardId, java.sql.Timestamp.from(since)).query(Long.class).single();
    }

}
