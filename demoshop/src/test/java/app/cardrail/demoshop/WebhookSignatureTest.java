package app.cardrail.demoshop;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureTest {

    private final ShopController shop = new ShopController(
            new ShopController.ShopProperties("http://127.0.0.1:1", "sk_test_x", "pk_test_x", "whsec_test"), new ObjectMapper());

    private final String body = "{\"id\":\"evt_1\",\"type\":\"payment.captured\"}";
    private final Instant now = Instant.parse("2026-11-02T10:00:00Z");

    @Test
    void believesARecentCorrectSignature() {
        long t = now.getEpochSecond();
        String header = "t=" + t + ",v1=" + ShopController.hmac("whsec_test", t + "." + body);
        assertThat(shop.verified(header, body, now)).isTrue();
    }

    @Test
    void refusesATamperedBodyAWrongSecretAndAnOldDelivery() {
        long t = now.getEpochSecond();
        assertThat(shop.verified("t=" + t + ",v1=" + ShopController.hmac("whsec_test", t + "." + body), body + " ", now)).isFalse();
        assertThat(shop.verified("t=" + t + ",v1=" + ShopController.hmac("whsec_other", t + "." + body), body, now)).isFalse();
        long old = t - 600;
        assertThat(shop.verified("t=" + old + ",v1=" + ShopController.hmac("whsec_test", old + "." + body), body, now)).isFalse();
        assertThat(shop.verified(null, body, now)).isFalse();
        assertThat(shop.verified("v1=abc", body, now)).isFalse();
    }
}
