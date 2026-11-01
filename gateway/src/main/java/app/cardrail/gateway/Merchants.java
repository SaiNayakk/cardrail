package app.cardrail.gateway;

import app.cardrail.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;

/** Merchants and their API keys. A merchant is onboarded at Railpay Acquiring first, which gives it its MID. */
@Service
public class Merchants {

    public record Merchant(String id, String name, String acquirerMid, String secretKeyHint, String publishableKey, String webhookUrl) {}

    public record Created(Merchant merchant, String secretKey, String webhookSecret) {}

    record AcquirerMerchant(String mid) {}

    private final JdbcClient jdbc;
    private final RestClient acquirer;
    private final GatewayProperties props;

    public Merchants(JdbcClient jdbc, Clients clients, GatewayProperties props) {
        this.jdbc = jdbc;
        this.acquirer = clients.acquirer();
        this.props = props;
    }

    /** Signs a merchant up. Keys are made here unless given (the demo shop's come from the environment). */
    public Created create(String name, String mcc, String city, String webhookUrl, String secretKey, String publishableKey,
                          String webhookSecret) {
        AcquirerMerchant onboarded = acquirer.post().uri("/internal/v1/merchants")
                .body(Map.of("name", name, "mcc", mcc, "city", city, "country", "US"))
                .retrieve().body(AcquirerMerchant.class);
        String mode = "live".equals(props.keyMode()) ? "live" : "test";
        String secret = secretKey != null ? secretKey : Keys.random("sk_" + mode + "_", 32);
        String publishable = publishableKey != null ? publishableKey : Keys.random("pk_" + mode + "_", 24);
        String signing = webhookSecret != null ? webhookSecret : Keys.random("whsec_", 32);
        String id = Keys.random("mer_", 16);
        jdbc.sql("""
                insert into merchants (id, name, acquirer_mid, secret_key_hash, secret_key_hint, publishable_key, webhook_url, webhook_secret)
                values (?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(id, name, onboarded.mid(), Keys.sha256(secret), Keys.hint(secret), publishable, webhookUrl, signing)
                .update();
        return new Created(byId(id).orElseThrow(), secret, signing);
    }

    public Optional<Merchant> byId(String id) {
        return jdbc.sql("select id, name, acquirer_mid, secret_key_hint, publishable_key, webhook_url from merchants where id = ?")
                .param(id).query(Merchant.class).optional();
    }

    public Optional<Merchant> byPublishableKey(String key) {
        return jdbc.sql("select id, name, acquirer_mid, secret_key_hint, publishable_key, webhook_url from merchants where publishable_key = ?")
                .param(key).query(Merchant.class).optional();
    }

    public void setWebhook(String secretKey, String webhookUrl, String webhookSecret) {
        jdbc.sql("update merchants set webhook_url = ?, webhook_secret = ? where secret_key_hash = ?")
                .params(webhookUrl, webhookSecret, Keys.sha256(secretKey)).update();
    }

    public boolean secretKeyExists(String secretKey) {
        return jdbc.sql("select count(*) from merchants where secret_key_hash = ?").param(Keys.sha256(secretKey))
                .query(Long.class).single() > 0;
    }

    /** The merchant a request's {@code Authorization: Bearer sk_...} header belongs to. */
    public Merchant authenticate(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer sk_")) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorised", "send your secret key as 'Authorization: Bearer sk_...'");
        }
        String key = authorization.substring("Bearer ".length()).trim();
        return jdbc.sql("select id, name, acquirer_mid, secret_key_hint, publishable_key, webhook_url from merchants where secret_key_hash = ?")
                .param(Keys.sha256(key)).query(Merchant.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unauthorised", "that secret key isn't valid"));
    }
}
