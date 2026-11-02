package app.cardrail.issuer;

import app.cardrail.iso8583.IsoMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MerchantNameTest {

    @Test
    void readsField43AsNameAndCity() {
        IsoMessage m = new IsoMessage("0100").set(43, "Railpay demo shop        San Francisco US");
        assertThat(Authoriser.merchant(m)).isEqualTo("Railpay demo shop, San Francisco");
        assertThat(Authoriser.merchant(new IsoMessage("0100").set(43, "Corner Books" + " ".repeat(28)))).isEqualTo("Corner Books");
        assertThat(Authoriser.merchant(new IsoMessage("0100").set(2, "1"))).isNull();
    }
}
