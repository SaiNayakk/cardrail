package app.cardrail.network;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class NetworkStore {

    public record Answer(String responseCode, String authCode) {}

    private final JdbcClient jdbc;

    public NetworkStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Records a request as it arrives; false if the acquirer sent it before. */
    public boolean requested(String acquirerId, String rrn, String acquirerStan, String issuerId, String networkStan,
                             String pan, String mti, long amountCents, String currency) {
        return jdbc.sql("""
                insert into authorisations (id, acquirer_id, rrn, acquirer_stan, issuer_id, network_stan, bin, last4, mti,
                                            amount_cents, currency)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (acquirer_id, rrn, acquirer_stan) do nothing""")
                .params(UUID.randomUUID(), acquirerId, rrn, acquirerStan, issuerId, networkStan, pan.substring(0, 6),
                        pan.substring(pan.length() - 4), mti, amountCents, currency)
                .update() == 1;
    }

    public void answered(String acquirerId, String rrn, String acquirerStan, String responseCode, String authCode, String decidedBy) {
        jdbc.sql("""
                update authorisations set response_code = ?, auth_code = ?, decided_by = ?, answered_at = now()
                where acquirer_id = ? and rrn = ? and acquirer_stan = ?""")
                .params(responseCode, authCode, decidedBy, acquirerId, rrn, acquirerStan).update();
    }

    /** The answer already given to this request, if any. */
    public Optional<Answer> answer(String acquirerId, String rrn, String acquirerStan) {
        return jdbc.sql("""
                select response_code, auth_code from authorisations
                where acquirer_id = ? and rrn = ? and acquirer_stan = ? and response_code is not null""")
                .params(acquirerId, rrn, acquirerStan).query(Answer.class).optional();
    }
}
