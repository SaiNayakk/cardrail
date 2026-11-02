package app.cardrail.issuer;

import app.cardrail.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
class IssuerController {

    private final IssuerProperties props;
    private final IssuerStore store;
    private final CardIssuing issuing;
    private final CardKeys keys;
    private final IssuerBooks books;

    IssuerController(IssuerProperties props, IssuerStore store, CardIssuing issuing, CardKeys keys, IssuerBooks books) {
        this.props = props;
        this.store = store;
        this.issuing = issuing;
        this.keys = keys;
        this.books = books;
    }

    record DisputeRequest(@Pattern(regexp = "[A-Za-z0-9]{12}") String reference,
                          @Pattern(regexp = "1040|1310|1330|1240") String reason) {}

    /**
     * A cardholder disputes a purchase: 1040 fraud, 1310 not received, 1330 not as described, 1240 charged twice.
     * Only in the demo, where anyone may dispute a purchase made with a test card (the cardholder app signs in, in phase 4).
     */
    @PostMapping("/v1/disputes")
    @ResponseStatus(HttpStatus.CREATED)
    IssuerBooks.DisputeView dispute(@Valid @RequestBody DisputeRequest r) {
        if (!props.demo()) {
            throw ApiException.notFound("disputes");
        }
        return books.dispute(r.reference(), r.reason());
    }

    /** Collect clearing files and settlement reports, and send chargebacks, now (it also runs on a schedule). */
    @PostMapping("/internal/v1/clearing/run")
    void clearingNow() {
        books.cycle();
    }

    record Books(String party, long total, java.util.Map<String, Long> accounts) {}

    /** Kestrel Bank's books, for anyone to see that they balance. Accounts are named by id only. */
    @GetMapping("/v1/books")
    Books books() {
        return new Books("kestrel", books.ledger().total(), books.ledger().balances(""));
    }

    record OpenRequest(@NotBlank String name, @Pattern(regexp = "DEBIT|CREDIT") String kind,
                       @Min(0) long balanceCents, @Min(0) Long creditLimitCents) {}

    /** Opens an account with a card on it. Internal: only Kestrel Bank's own systems (and the demo) call it. */
    @PostMapping("/internal/v1/cards")
    @ResponseStatus(HttpStatus.CREATED)
    CardIssuing.IssuedCard open(@Valid @RequestBody OpenRequest request) {
        if ("CREDIT".equals(request.kind()) != (request.creditLimitCents() != null)) {
            throw ApiException.invalid("credit_limit", "a credit card needs a credit limit, and only a credit card has one");
        }
        return issuing.open(request.name(), request.kind(), "DEBIT".equals(request.kind()) ? request.balanceCents() : 0,
                request.creditLimitCents());
    }

    record AccountView(UUID id, String kind, String currency, long balanceCents, Long creditLimitCents, long heldCents,
                       long availableCents) {}

    @GetMapping("/internal/v1/accounts/{id}")
    AccountView account(@PathVariable UUID id) {
        var account = store.account(id).orElseThrow(() -> ApiException.notFound("account " + id));
        long held = store.heldCents(id);
        return new AccountView(id, account.kind(), "USD", account.balanceCents(), account.creditLimitCents(), held,
                account.availableCents(held));
    }

    record TestCardView(String number, String expiry, String cvv2, String kind, String outcome) {}

    /** The published test cards with everything needed to pay with them. Only in the demo. */
    @GetMapping("/v1/test-cards")
    List<TestCardView> testCards() {
        if (!props.demo()) {
            throw ApiException.notFound("test cards");
        }
        return store.labelledCards().stream().map(card -> {
            String pan = keys.decrypt(store.encryptedPan(card.id()).orElseThrow());
            String expiry = card.expiry().substring(2) + "/" + card.expiry().substring(0, 2);
            String kind = card.bin().equals(CardIssuing.CREDIT_BIN) ? "CREDIT" : "DEBIT";
            return new TestCardView(pan, expiry, keys.cvv2(pan, card.expiry()), kind, card.label());
        }).toList();
    }
}
