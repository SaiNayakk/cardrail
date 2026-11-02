package app.cardrail.network;

import app.cardrail.common.ledger.Ledger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
class NetworkController {

    private final Routing routing;
    private final Clearing clearing;
    private final Settlement settlement;
    private final Ledger ledger;
    private final Directory directory;

    NetworkController(Routing routing, Clearing clearing, Settlement settlement, Directory directory, JdbcClient jdbc) {
        this.routing = routing;
        this.clearing = clearing;
        this.settlement = settlement;
        this.directory = directory;
        this.ledger = new Ledger(jdbc);
    }

    record StartAuthentication(@jakarta.validation.constraints.Pattern(regexp = "[0-9]{12,19}") String pan,
                               @jakarta.validation.constraints.Pattern(regexp = "[0-9]{4}") String expiry,
                               @jakarta.validation.constraints.Min(1) long amountCents,
                               @jakarta.validation.constraints.NotBlank String merchant, String reference) {}

    /** The directory server: a gateway asks for the cardholder to be authenticated by the card's issuer. */
    @PostMapping("/internal/v1/directory/authentications")
    Directory.Started authenticate(@jakarta.validation.Valid @RequestBody StartAuthentication r) {
        return directory.start(r.pan(), r.expiry(), r.amountCents(), r.merchant(), r.reference());
    }

    @GetMapping("/internal/v1/directory/authentications/{issuerId}/{id}")
    Directory.Result authentication(@org.springframework.web.bind.annotation.PathVariable String issuerId,
                                    @org.springframework.web.bind.annotation.PathVariable String id) {
        return directory.result(issuerId, id);
    }

    /** A member sends a clearing file (text, fixed width). The answer says what was accepted and what wasn't, and why. */
    @PostMapping(value = "/internal/v1/clearing/files", consumes = "text/plain")
    Clearing.Result receive(@RequestBody String file) {
        return clearing.receive(file);
    }

    /** The files Railnet has for a member, after the last one it took. */
    @GetMapping("/internal/v1/clearing/files")
    List<Clearing.OutFile> outgoing(@RequestParam String member, @RequestParam(defaultValue = "0") long after) {
        return clearing.outgoing(member, after);
    }

    /** A member's settlement reports, after the last one it read. */
    @GetMapping("/internal/v1/settlements")
    List<Settlement.Report> settlements(@RequestParam String member, @RequestParam(required = false) String after) {
        return settlement.reports(member, after);
    }

    record Settled(String settlementId) {}

    /** The cut-off, now (it also runs on a schedule). */
    @PostMapping("/internal/v1/settlements/run")
    Settled settle() {
        return new Settled(settlement.settle().orElse(null));
    }

    record Books(String party, long total, Map<String, Long> accounts) {}

    /** Railnet's books, for anyone to see that they balance: members' positions, fees, the settlement bank. */
    @GetMapping("/v1/books")
    Books books() {
        return new Books("railnet", ledger.total(), ledger.balances(""));
    }

    /** The BIN table: which issuer each range of card numbers belongs to. Public, as networks publish theirs. */
    @GetMapping("/v1/bin-ranges")
    List<Routing.Range> binRanges() {
        return routing.ranges();
    }
}
