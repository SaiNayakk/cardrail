package app.cardrail.network;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

/** Interchange and Railnet's own fees: basis points of the amount plus a fixed amount, rounded half up to the cent. */
@Component
public class Fees {

    record Rate(String name, int bps, int fixedCents) {}

    private final Map<String, Rate> rates;

    public Fees(JdbcClient jdbc) {
        this.rates = jdbc.sql("select name, bps, fixed_cents from fees").query(Rate.class).list().stream()
                .collect(Collectors.toUnmodifiableMap(Rate::name, r -> r));
    }

    public long fee(String name, long amountCents) {
        Rate rate = rates.get(name);
        if (rate == null) {
            return 0;
        }
        return (amountCents * rate.bps() + 5_000) / 10_000 + rate.fixedCents();
    }
}
