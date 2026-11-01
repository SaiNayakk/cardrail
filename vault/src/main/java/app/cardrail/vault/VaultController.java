package app.cardrail.vault;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
class VaultController {

    private final Vault vault;
    private final PublishableKeys keys;
    private final String cardForm;

    VaultController(Vault vault, PublishableKeys keys) throws IOException {
        this.vault = vault;
        this.keys = keys;
        // not under static/: in a shared host every service would serve it
        this.cardForm = new ClassPathResource("vault/card-form.html").getContentAsString(StandardCharsets.UTF_8);
    }

    record TokenRequest(@NotBlank String publishableKey, @NotBlank String number, @NotNull Integer expMonth,
                        @NotNull Integer expYear, String cvc) {}

    /** Called by the hosted card form in the cardholder's browser. The merchant's servers never see this. */
    @PostMapping({"/v1/tokens", "/hosted/tokens"})
    @ResponseStatus(HttpStatus.CREATED)
    Vault.TokenView tokenise(@Valid @RequestBody TokenRequest r) {
        String merchant = keys.merchantFor(r.publishableKey());
        return vault.tokenise(merchant, new Vault.Card(r.number(), r.expMonth(), r.expYear(), r.cvc()));
    }

    /**
     * The card form, to be shown in an iframe on the merchant's checkout page. It posts the card here and hands
     * the merchant's page only the token. Any site may frame it; a token is useless without the merchant's secret key.
     */
    @GetMapping(value = "/hosted/card-form", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> cardForm() {
        return ResponseEntity.ok()
                .header("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; "
                        + "connect-src 'self'; frame-ancestors *; form-action 'none'; base-uri 'none'")
                .header("Cache-Control", "no-store")
                .header("Referrer-Policy", "no-referrer")
                .body(cardForm);
    }

    record RevealRequest(@NotBlank String merchantId) {}

    /** The gateway, about to authorise, asks for the card behind a token. Once per token. */
    @PostMapping("/internal/v1/tokens/{id}/reveal")
    Vault.Revealed reveal(@PathVariable String id, @Valid @RequestBody RevealRequest r) {
        return vault.reveal(id, r.merchantId());
    }

    /** The gateway, refunding, asks for the card a payment was made with. */
    @PostMapping("/internal/v1/cards/{id}/reveal")
    Vault.Revealed revealCard(@PathVariable String id, @Valid @RequestBody RevealRequest r) {
        return vault.revealCard(id, r.merchantId());
    }
}
