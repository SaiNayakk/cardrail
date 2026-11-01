package app.cardrail.issuer;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Kestrel Bank's tables. Plain SQL, every query visible. */
@Repository
public class IssuerStore {

    public record Card(UUID id, UUID accountId, String bin, String last4, String expiry, String status, String label) {}

    public record Account(UUID id, UUID cardholderId, String kind, long balanceCents, Long creditLimitCents) {

        /** What can be spent: a debit account's money, or a credit account's unused limit, less open holds. */
        public long availableCents(long heldCents) {
            return "DEBIT".equals(kind) ? balanceCents - heldCents : creditLimitCents - balanceCents - heldCents;
        }
    }

    public record Authorisation(UUID id, String responseCode, String authCode, long amountCents, String status) {}

    private final JdbcClient jdbc;

    public IssuerStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID createCardholder(String name) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into cardholders (id, name) values (?, ?)").params(id, name).update();
        return id;
    }

    public UUID createAccount(UUID cardholderId, String kind, long balanceCents, Long creditLimitCents) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into accounts (id, cardholder_id, kind, balance_cents, credit_limit_cents) values (?, ?, ?, ?, ?)")
                .params(id, cardholderId, kind, balanceCents, creditLimitCents).update();
        return id;
    }

    public UUID createCard(UUID accountId, byte[] panHash, byte[] panEncrypted, String pan, String expiry, String status, String label) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into cards (id, account_id, pan_hash, pan_encrypted, bin, last4, expiry, status, label)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(id, accountId, panHash, panEncrypted, pan.substring(0, 6), pan.substring(pan.length() - 4), expiry, status, label)
                .update();
        return id;
    }

    public Optional<Card> cardByPanHash(byte[] panHash) {
        return jdbc.sql("select id, account_id, bin, last4, expiry, status, label from cards where pan_hash = ?")
                .param(panHash).query(Card.class).optional();
    }

    public Optional<byte[]> encryptedPan(UUID cardId) {
        return jdbc.sql("select pan_encrypted from cards where id = ?").param(cardId).query(byte[].class).optional();
    }

    public List<Card> labelledCards() {
        return jdbc.sql("select id, account_id, bin, last4, expiry, status, label from cards where label is not null order by bin, last4")
                .query(Card.class).list();
    }

    /** The account, locked until the transaction ends, so two authorisations can't both spend the same money. */
    public Account lockAccount(UUID accountId) {
        return jdbc.sql("select id, cardholder_id, kind, balance_cents, credit_limit_cents from accounts where id = ? for update")
                .param(accountId).query(Account.class).single();
    }

    public Optional<Account> account(UUID accountId) {
        return jdbc.sql("select id, cardholder_id, kind, balance_cents, credit_limit_cents from accounts where id = ?")
                .param(accountId).query(Account.class).optional();
    }

    public long heldCents(UUID accountId) {
        return jdbc.sql("select coalesce(sum(hold_cents), 0) from authorisations where account_id = ? and status = 'APPROVED'")
                .param(accountId).query(Long.class).single();
    }

    public Optional<Authorisation> authorisation(String acquirerId, String rrn, String stan) {
        return jdbc.sql("""
                select id, response_code, auth_code, amount_cents, status from authorisations
                where acquirer_id = ? and rrn = ? and stan = ?""")
                .params(acquirerId, rrn, stan).query(Authorisation.class).optional();
    }

    public record NewAuthorisation(UUID cardId, UUID accountId, String acquirerId, String rrn, String stan, String mti,
                                   long amountCents, String currency, String mcc, String merchant,
                                   String responseCode, String authCode, String status, long holdCents) {}

    /** Records the decision; false if the same request was recorded meanwhile (its first answer stands). */
    public boolean record(NewAuthorisation a) {
        return jdbc.sql("""
                insert into authorisations (id, card_id, account_id, acquirer_id, rrn, stan, mti, amount_cents, currency,
                                            mcc, merchant, response_code, auth_code, status, hold_cents)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (acquirer_id, rrn, stan) do nothing""")
                .params(UUID.randomUUID(), a.cardId(), a.accountId(), a.acquirerId(), a.rrn(), a.stan(), a.mti(),
                        a.amountCents(), a.currency(), a.mcc(), a.merchant(), a.responseCode(), a.authCode(), a.status(),
                        a.holdCents())
                .update() == 1;
    }
}
