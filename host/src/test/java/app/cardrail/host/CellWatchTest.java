package app.cardrail.host;

import app.cardrail.host.CellWatch.Action;
import app.cardrail.host.CellWatch.View;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static app.cardrail.host.CellWatch.HOLDING;
import static app.cardrail.host.CellWatch.NORMAL;
import static app.cardrail.host.CellWatch.TAKEN_OVER;
import static app.cardrail.host.CellWatch.decide;
import static org.assertj.core.api.Assertions.assertThat;

class CellWatchTest {

    static final Duration AFTER = Duration.ofSeconds(120);
    static final Duration LONG = Duration.ofSeconds(300);

    @Test
    void takesOverOnlyAPeerSilentHereAndDownAtTheWitness() {
        assertThat(decide(NORMAL, new View(null, false, false), true, LONG, AFTER)).isEqualTo(Action.TAKE_OVER);
        // the home network alone failing, both devices up: nobody takes anybody over
        assertThat(decide(NORMAL, new View(null, true, false), true, LONG, AFTER)).isEqualTo(Action.NOTHING);
        // not silent for long enough
        assertThat(decide(NORMAL, new View(null, false, false), true, Duration.ofSeconds(60), AFTER)).isEqualTo(Action.NOTHING);
        // a peer never seen isn't lost, it isn't set up yet
        assertThat(decide(NORMAL, new View(null, false, false), false, LONG, AFTER)).isEqualTo(Action.NOTHING);
        // a peer that answers isn't lost
        assertThat(decide(NORMAL, new View(NORMAL, false, false), true, LONG, AFTER)).isEqualTo(Action.NOTHING);
    }

    @Test
    void waitsForSproutsOwnTakeoverOnTheSameDevice() {
        assertThat(decide(NORMAL, new View(null, false, true), true, LONG, AFTER)).isEqualTo(Action.WAIT_FOR_SPROUT);
    }

    @Test
    void stopsServingWhenThePeerHoldsItsHalf() {
        assertThat(decide(NORMAL, new View(HOLDING, true, false), true, Duration.ZERO, AFTER)).isEqualTo(Action.FENCE);
    }

    @Test
    void doesNothingOnceHoldingOrTakenOver() {
        assertThat(decide(HOLDING, new View(null, false, false), true, LONG, AFTER)).isEqualTo(Action.NOTHING);
        assertThat(decide(TAKEN_OVER, new View(HOLDING, false, false), true, LONG, AFTER)).isEqualTo(Action.NOTHING);
    }

    @Test
    void holdingTheOtherHalfEveryAddressOfItPointsHere() {
        var env = Map.of("CARDRAIL_PEER_LAN", "192.168.0.7", "NETWORK_KESTREL_HOST", "192.168.0.7",
                "NETWORK_KESTREL_ACS_URL", "http://192.168.0.7:8205", "GATEWAY_BIND", "0.0.0.0");
        assertThat(Host.localAddresses(env)).containsExactly("--NETWORK_KESTREL_ACS_URL=http://127.0.0.1:8205",
                "--NETWORK_KESTREL_HOST=127.0.0.1");
    }
}
