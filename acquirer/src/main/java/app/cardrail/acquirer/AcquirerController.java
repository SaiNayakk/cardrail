package app.cardrail.acquirer;

import app.cardrail.common.ApiException;
import app.cardrail.common.trace.TraceReporter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** The acquirer's internal API. The gateway is its only caller. */
@RestController
class AcquirerController {

    private final AcquirerStore store;
    private final Authorising authorising;
    private final Reversing reversing;
    private final TraceReporter trace;

    AcquirerController(AcquirerStore store, Authorising authorising, Reversing reversing, TraceReporter trace) {
        this.store = store;
        this.authorising = authorising;
        this.reversing = reversing;
        this.trace = trace;
    }

    record OnboardRequest(@NotBlank @Size(max = 60) String name, @Pattern(regexp = "[0-9]{4}") String mcc,
                          @NotBlank @Size(max = 40) String city, @Pattern(regexp = "[A-Z]{2}") String country,
                          @Min(0) @Max(1000) Integer mdrBps) {}

    record MerchantView(String mid, String name, String mcc, String city, String country, int mdrBps, String status,
                        List<String> terminals) {}

    /** Signs a merchant up: a MID, and one e-commerce terminal to take online payments on. */
    @PostMapping("/internal/v1/merchants")
    @ResponseStatus(HttpStatus.CREATED)
    MerchantView onboard(@Valid @RequestBody OnboardRequest r) {
        var merchant = store.createMerchant(r.name(), r.mcc(), r.city(), r.country() == null ? "US" : r.country(),
                r.mdrBps() == null ? 250 : r.mdrBps());
        store.createTerminal(merchant.id(), "ECOMMERCE");
        return view(merchant.mid());
    }

    @GetMapping("/internal/v1/merchants/{mid}")
    MerchantView merchant(@PathVariable String mid) {
        return view(mid);
    }

    private MerchantView view(String mid) {
        var m = store.merchant(mid).orElseThrow(() -> ApiException.notFound("merchant " + mid));
        return new MerchantView(m.mid(), m.name(), m.mcc(), m.city(), m.country(), m.mdrBps(), m.status(),
                store.terminals(m.id()));
    }

    record AuthoriseRequest(@NotBlank String mid, String tid, @Pattern(regexp = "[0-9]{12,19}") String pan,
                            @Pattern(regexp = "[0-9]{4}") String expiry, @Pattern(regexp = "[0-9]{3,4}") String cvv2,
                            @Min(1) @Max(99_999_999_99L) long amountCents, @Pattern(regexp = "[A-Z]{3}") String currency,
                            @Pattern(regexp = "[A-Za-z0-9]{12}") String rrn) {}

    @PostMapping("/internal/v1/authorisations")
    Authorising.Result authorise(@Valid @RequestBody AuthoriseRequest r) {
        var result = authorising.authorise(new Authorising.Request(r.mid(), r.tid(), r.pan(), r.expiry(), r.cvv2(),
                r.amountCents(), r.currency(), r.rrn(), null));
        String summary = switch (result.status()) {
            case "APPROVED" -> "Approved, code " + result.authCode();
            case "DECLINED" -> "Declined: " + result.responseText();
            default -> "Outcome unknown: " + result.responseText();
        };
        trace.note(r.rrn(), Parties.ACQUIRER, Parties.GATEWAY, summary, Map.of("status", result.status()));
        return result;
    }

    record RefundRequest(@Pattern(regexp = "[A-Za-z0-9]{12}") String originalRrn, @NotBlank String mid,
                         @Pattern(regexp = "[0-9]{12,19}") String pan, @Pattern(regexp = "[0-9]{4}") String expiry,
                         @Min(1) @Max(99_999_999_99L) long amountCents, @Pattern(regexp = "[A-Za-z0-9]{12}") String rrn) {}

    /** A refund: an 0100 with processing code 20, approved by the issuer, cleared as a credit. */
    @PostMapping("/internal/v1/refunds")
    Authorising.Result refund(@Valid @RequestBody RefundRequest r) {
        var result = authorising.authorise(new Authorising.Request(r.mid(), null, r.pan(), r.expiry(), null, r.amountCents(),
                "USD", r.rrn(), r.originalRrn()));
        trace.note(r.rrn(), Parties.ACQUIRER, Parties.GATEWAY, "Refund " + result.status().toLowerCase()
                + (result.responseCode() == null || "00".equals(result.responseCode()) ? "" : ": " + result.responseText()),
                Map.of("status", result.status(), "refundOf", r.originalRrn()));
        return result;
    }

    @PostMapping("/internal/v1/authorisations/{rrn}/void")
    Reversing.Outcome voidPayment(@PathVariable String rrn) {
        Reversing.Outcome outcome = reversing.voidPayment(rrn);
        trace.note(rrn, Parties.ACQUIRER, Parties.GATEWAY, "REVERSED".equals(outcome.status()) ? "Voided: the hold is released"
                : "Void accepted: the network will be told until it hears it", Map.of("status", outcome.status()));
        return outcome;
    }

    record CaptureRequest(@Min(1) long amountCents) {}

    @PostMapping("/internal/v1/authorisations/{rrn}/capture")
    Reversing.Outcome capture(@PathVariable String rrn, @Valid @RequestBody CaptureRequest r) {
        Reversing.Outcome outcome = reversing.capture(rrn, r.amountCents());
        trace.note(rrn, Parties.ACQUIRER, Parties.GATEWAY, "Captured: settled with the next clearing",
                Map.of("capturedCents", r.amountCents()));
        return outcome;
    }

    record AuthorisationView(String rrn, String kind, String status, long amountCents, long capturedCents, String responseCode,
                             String authCode) {}

    @GetMapping("/internal/v1/authorisations/{rrn}")
    AuthorisationView authorisation(@PathVariable String rrn) {
        var a = store.authorisation(rrn).orElseThrow(() -> ApiException.notFound("authorisation " + rrn));
        return new AuthorisationView(a.rrn(), a.kind(), a.status(), a.amountCents(), a.capturedCents(), a.responseCode(), a.authCode());
    }
}
