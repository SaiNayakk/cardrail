package app.cardrail.network;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
class NetworkController {

    private final Routing routing;

    NetworkController(Routing routing) {
        this.routing = routing;
    }

    /** The BIN table: which issuer each range of card numbers belongs to. Public, as networks publish theirs. */
    @GetMapping("/v1/bin-ranges")
    List<Routing.Range> binRanges() {
        return routing.ranges();
    }
}
