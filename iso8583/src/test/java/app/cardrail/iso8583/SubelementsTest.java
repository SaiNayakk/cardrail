package app.cardrail.iso8583;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubelementsTest {

    @Test
    void roundTripsTagLengthValue() {
        Map<String, String> in = new LinkedHashMap<>();
        in.put(Subelements.ECOMMERCE_SECURITY, "212");
        in.put(Subelements.CVV2, "123");

        String encoded = Subelements.encode(in);

        assertThat(encoded).isEqualTo("4203212" + "9203123");
        assertThat(Subelements.decode(encoded)).isEqualTo(in);
    }

    @Test
    void refusesBrokenSubelements() {
        assertThatThrownBy(() -> Subelements.decode("920")).hasMessageContaining("header");
        assertThatThrownBy(() -> Subelements.decode("920512")).hasMessageContaining("inside subelement 92");
    }

    @Test
    void hidesTheCardSecurityCodeInTraces() {
        String masked = Masking.mask(48, "4203212" + "9203123");
        assertThat(masked).isEqualTo("4203212" + "9203***");
        assertThat(Masking.mask(48, "garbage")).doesNotContain("garbage");
    }

    @Test
    void checksLuhn() {
        assertThat(Luhn.valid("4111111111111111")).isTrue();
        assertThat(Luhn.valid("4111111111111112")).isFalse();
        assertThat(Luhn.checkDigit("411111111111111")).isEqualTo(1);
        assertThat(Luhn.valid("41111")).isFalse();
    }
}
