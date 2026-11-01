package app.cardrail.vault;

import app.cardrail.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.name=vault",
        "vault.key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "cardrail.internal-key=test-internal-key-0123456789"})
class VaultTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&currentSchema=vault");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired Vault vault;
    @Autowired JdbcClient jdbc;
    @Autowired TestRestTemplate http;

    @Test
    void aTokenRevealsItsCardOnceAndForgetsTheSecurityCode() {
        var token = vault.tokenise("m_1", new Vault.Card("2860 0100 0000 0012", 12, 30, "123"));
        assertThat(token.last4()).isEqualTo("0012");
        assertThat(token.brand()).isEqualTo("kestrel");

        var card = vault.reveal(token.id(), "m_1");
        assertThat(card.pan()).isEqualTo("2860010000000012");
        assertThat(card.expiry()).isEqualTo("3012");
        assertThat(card.cvc()).isEqualTo("123");

        assertThat(jdbc.sql("select cvc_encrypted is null from tokens where id = ?").param(token.id()).query(Boolean.class).single()).isTrue();
        assertThatThrownBy(() -> vault.reveal(token.id(), "m_1")).hasMessageContaining("used already");

        // the card is kept for refunds, without its security code, for the same merchant only
        var kept = vault.revealCard(card.cardRef(), "m_1");
        assertThat(kept.pan()).isEqualTo("2860010000000012");
        assertThat(kept.cvc()).isNull();
        assertThatThrownBy(() -> vault.revealCard(card.cardRef(), "m_2")).hasMessageContaining("doesn't exist");
    }

    @Test
    void aTokenBelongsToOneMerchant() {
        var token = vault.tokenise("m_1", new Vault.Card("2860010000000012", 12, 2030, null));
        assertThatThrownBy(() -> vault.reveal(token.id(), "m_2")).isInstanceOf(ApiException.class).hasMessageContaining("doesn't exist");
    }

    @Test
    void cardNumbersAreNotStoredInTheClear() {
        var token = vault.tokenise("m_1", new Vault.Card("2860010000000012", 12, 2030, "123"));
        byte[] stored = jdbc.sql("select pan_encrypted from tokens where id = ?").param(token.id()).query(byte[].class).single();
        assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("2860010000000012");
    }

    @Test
    void refusesBadCards() {
        assertThatThrownBy(() -> vault.tokenise("m_1", new Vault.Card("2860010000000018", 12, 2030, null))).hasMessageContaining("number");
        assertThatThrownBy(() -> vault.tokenise("m_1", new Vault.Card("2860010000000012", 13, 2030, null))).hasMessageContaining("expiry");
        assertThatThrownBy(() -> vault.tokenise("m_1", new Vault.Card("2860010000000012", 1, 2020, null))).hasMessageContaining("expired");
    }

    @Test
    void servesTheCardFormFramableWithNoCaching() {
        var response = http.getForEntity("/hosted/card-form", String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getFirst("Content-Security-Policy")).contains("frame-ancestors *");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody()).contains("cardrail:token");
    }

    @Test
    void internalRoutesNeedTheKey() {
        var response = http.postForEntity("/internal/v1/tokens/tok_x/reveal", java.util.Map.of("merchantId", "m_1"), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }
}
