package app.cardrail.issuer;

import app.cardrail.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The cardholder app's API, and Kestrel Bank's ACS: the step-up challenge page and the directory server's calls. */
@RestController
class CardholderController {

    private final IssuerProperties props;
    private final Cardholders cardholders;
    private final Acs acs;
    private final Risk risk;
    private final IssuerBooks books;
    private final Statements statements;
    private final String challengePage;

    CardholderController(IssuerProperties props, Cardholders cardholders, Acs acs, Risk risk, IssuerBooks books, Statements statements)
            throws IOException {
        this.props = props;
        this.cardholders = cardholders;
        this.acs = acs;
        this.risk = risk;
        this.books = books;
        this.statements = statements;
        // not under static/: in a shared host every service would serve it
        this.challengePage = new ClassPathResource("issuer/challenge.html").getContentAsString(StandardCharsets.UTF_8);
    }

    // ---- signing in

    record SignIn(@NotBlank String number, @Pattern(regexp = "[0-9]{2}/[0-9]{2}") String expiry, @Pattern(regexp = "[0-9]{3}") String cvv2) {}

    @PostMapping("/v1/cardholder/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    Cardholders.Session signIn(@Valid @RequestBody SignIn r) {
        return cardholders.signIn(r.number(), r.expiry(), r.cvv2());
    }

    record OpenOwn(@Pattern(regexp = "DEBIT|CREDIT") String kind) {}

    /** A card of the visitor's own ($1,000 to spend), to freeze, lose and replace. Demo only. */
    @PostMapping("/v1/cardholder/cards")
    @ResponseStatus(HttpStatus.CREATED)
    Cardholders.Replacement openOwn(@Valid @RequestBody OpenOwn r) {
        if (!props.demo()) {
            throw ApiException.notFound("cards");
        }
        return cardholders.openOwn(r.kind());
    }

    // ---- the card

    @GetMapping("/v1/cardholder/card")
    Cardholders.CardView card(@RequestHeader(value = "Authorization", required = false) String auth) {
        return cardholders.card(cardholders.cardFor(auth));
    }

    @GetMapping("/v1/cardholder/transactions")
    List<Cardholders.Transaction> transactions(@RequestHeader(value = "Authorization", required = false) String auth) {
        return cardholders.transactions(cardholders.cardFor(auth));
    }

    @PostMapping("/v1/cardholder/card/freeze")
    Cardholders.CardView freeze(@RequestHeader(value = "Authorization", required = false) String auth) {
        return cardholders.freeze(cardholders.cardFor(auth), true);
    }

    @PostMapping("/v1/cardholder/card/unfreeze")
    Cardholders.CardView unfreeze(@RequestHeader(value = "Authorization", required = false) String auth) {
        return cardholders.freeze(cardholders.cardFor(auth), false);
    }

    record Lost(boolean stolen) {}

    /** Reports the card lost or stolen: it stops now, and the answer holds the new card (inactive) and a session for it. */
    @PostMapping("/v1/cardholder/card/report-lost")
    Cardholders.Replacement reportLost(@RequestHeader(value = "Authorization", required = false) String auth, @RequestBody(required = false) Lost r) {
        return cardholders.reportLost(cardholders.cardFor(auth), r != null && r.stolen());
    }

    record Activate(@Pattern(regexp = "[0-9]{3}") String cvv2) {}

    @PostMapping("/v1/cardholder/card/activate")
    Cardholders.CardView activate(@RequestHeader(value = "Authorization", required = false) String auth, @Valid @RequestBody Activate r) {
        return cardholders.activate(cardholders.cardFor(auth), r.cvv2());
    }

    @GetMapping("/v1/cardholder/controls")
    Risk.Controls controls(@RequestHeader(value = "Authorization", required = false) String auth) {
        return risk.controls(cardholders.cardFor(auth));
    }

    record Controls(boolean onlineEnabled, @Min(1) @Max(10_000_000) Long singleLimitCents, @Min(1) @Max(10_000_000) Long dailyLimitCents,
                    @Size(max = 20) List<@Pattern(regexp = "[0-9]{4}") String> blockedMccs) {}

    @PutMapping("/v1/cardholder/controls")
    Risk.Controls setControls(@RequestHeader(value = "Authorization", required = false) String auth, @Valid @RequestBody Controls r) {
        return cardholders.setControls(cardholders.cardFor(auth), r.onlineEnabled(), r.singleLimitCents(), r.dailyLimitCents(),
                r.blockedMccs() == null ? List.of() : r.blockedMccs());
    }

    record DisputeRequest(@Pattern(regexp = "[A-Za-z0-9]{12}") String reference, @Pattern(regexp = "1040|1310|1330|1240") String reason) {}

    @PostMapping("/v1/cardholder/disputes")
    @ResponseStatus(HttpStatus.CREATED)
    IssuerBooks.DisputeView dispute(@RequestHeader(value = "Authorization", required = false) String auth, @Valid @RequestBody DisputeRequest r) {
        UUID card = cardholders.cardFor(auth);
        if (cardholders.transactions(card).stream().noneMatch(t -> t.reference().equals(r.reference()))) {
            throw ApiException.notFound("purchase " + r.reference());
        }
        return books.dispute(r.reference(), r.reason());
    }

    // ---- credit card statements and payments

    @GetMapping("/v1/cardholder/statements")
    List<Statements.Statement> statements(@RequestHeader(value = "Authorization", required = false) String auth) {
        return statements.list(cardholders.accountOf(cardholders.cardFor(auth)));
    }

    record Pay(@Min(1) @Max(10_000_000) long amountCents) {}

    record Paid(long owedCents) {}

    /** Pays towards the credit card, from the cardholder's bank account elsewhere. */
    @PostMapping("/v1/cardholder/payments")
    Paid pay(@RequestHeader(value = "Authorization", required = false) String auth, @Valid @RequestBody Pay r) {
        return new Paid(statements.pay(cardholders.accountOf(cardholders.cardFor(auth)), r.amountCents()));
    }

    /** Ends the cycle now and makes the statement: the demo's way to see a month pass. Not for shared cards. */
    @PostMapping("/v1/cardholder/statements")
    @ResponseStatus(HttpStatus.CREATED)
    Statements.Statement closeStatement(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (!props.demo()) {
            throw ApiException.notFound("statements");
        }
        UUID card = cardholders.cardFor(auth);
        cardholders.ownCardOnly(card);
        return statements.closeNow(cardholders.accountOf(card));
    }

    // ---- step-up, in the app

    @GetMapping("/v1/cardholder/authentications")
    List<Acs.Pending> pending(@RequestHeader(value = "Authorization", required = false) String auth) {
        return acs.pending(cardholders.cardFor(auth));
    }

    record Decision(boolean approve) {}

    @PostMapping("/v1/cardholder/authentications/{id}")
    Acs.Result decide(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable String id,
                      @RequestBody Decision d) {
        return acs.decideInApp(cardholders.cardFor(auth), id, d.approve());
    }

    // ---- the ACS: the challenge page the shopper sees, and the directory server's calls

    @GetMapping(value = "/acs/challenge/{id}", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> challengePage(@PathVariable String id) {
        return ResponseEntity.ok()
                .header("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; "
                        + "connect-src 'self'; frame-ancestors *; form-action 'none'; base-uri 'none'")
                .header("Cache-Control", "no-store")
                .header("Referrer-Policy", "no-referrer")
                .body(challengePage);
    }

    @GetMapping("/acs/challenge/{id}/info")
    Map<String, Object> challengeInfo(@PathVariable String id) {
        return acs.challenge(id).orElseThrow(() -> ApiException.notFound("authentication " + id));
    }

    record Code(@Pattern(regexp = "[0-9]{6}") String code) {}

    @PostMapping("/acs/challenge/{id}")
    Acs.Result answer(@PathVariable String id, @Valid @RequestBody Code c) {
        Acs.Result r = acs.answer(id, c.code());
        return new Acs.Result(r.id(), r.status(), null);   // the value goes only to the directory server
    }

    record Start(@Pattern(regexp = "[0-9]{12,19}") String pan, @Pattern(regexp = "[0-9]{4}") String expiry, @Min(1) long amountCents,
                 @NotBlank @Size(max = 60) String merchant, String reference) {}

    @PostMapping("/internal/v1/acs/authentications")
    @ResponseStatus(HttpStatus.CREATED)
    Acs.Started start(@Valid @RequestBody Start r) {
        return acs.start(r.pan(), r.expiry(), r.amountCents(), r.merchant(), r.reference());
    }

    @GetMapping("/internal/v1/acs/authentications/{id}")
    Acs.Result result(@PathVariable String id) {
        return acs.result(id);
    }
}
