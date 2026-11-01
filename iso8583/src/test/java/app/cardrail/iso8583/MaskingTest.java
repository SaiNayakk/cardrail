package app.cardrail.iso8583;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaskingTest {

    @Test
    void keepsTheBinAndLastFourOnly() {
        assertThat(Masking.pan("4111111111111111")).isEqualTo("411111******1111");
        assertThat(Masking.pan("123")).isEqualTo("***");
    }

    @Test
    void neverShowsSensitiveFields() {
        IsoMessage message = new IsoMessage("0100")
                .set(2, "4111111111111111")
                .set(14, "2812")
                .set(35, "4111111111111111=28121010000012345")
                .set(52, "0123456789ABCDEF")
                .set(4, "000000001999");

        String logged = message.toString();

        assertThat(logged).doesNotContain("4111111111111111", "2812", "0123456789ABCDEF", "1010000012345");
        assertThat(logged).contains("411111******1111", "000000001999", "[8 bytes hidden]");
    }
}
