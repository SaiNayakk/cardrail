package app.cardrail.common.clearing;

import app.cardrail.common.clearing.ClearingFile.Header;
import app.cardrail.common.clearing.ClearingFile.Record;
import app.cardrail.common.clearing.ClearingFile.Type;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClearingFileTest {

    static final Header HEADER = new Header("ACQ-20261102-0001", "400101", "900001", LocalDate.of(2026, 11, 2),
            LocalDateTime.of(2026, 11, 2, 18, 30, 5));
    static final Record SALE = new Record(Type.SALE, "400101", "286001", "630612000123", "QWE123", 1999, "840",
            LocalDate.of(2026, 11, 2), "5999", "RPA000000000001", "W0000001", "286001", "0012", 0, null, null, null);

    @Test
    void writesFixedWidthRecordsAtTheirPositions() {
        String file = ClearingFile.write(HEADER, List.of(SALE));
        String[] lines = file.split("\n");

        assertThat(lines).hasSize(3).allSatisfy(line -> assertThat(line).hasSize(160));
        assertThat(lines[0]).startsWith("H0ACQ-20261102-0001   00000400101000009000012026110220261102183005");
        // type, acquirer, issuer, RRN, auth code, amount, currency, date, MCC, merchant, terminal, BIN, last four, interchange
        assertThat(lines[1]).startsWith("05" + "00000400101" + "00000286001" + "630612000123" + "QWE123" + "000000001999" + "840"
                + "20261102" + "5999" + "RPA000000000001" + "W0000001" + "286001" + "0012" + "000000000000");
        assertThat(lines[2]).startsWith("T0" + "00000001" + "000000000001999");
    }

    @Test
    void readsBackWhatItWrote() {
        Record credit = new Record(Type.CREDIT, "400101", "286001", "630612000124", null, 500, "840", LocalDate.of(2026, 11, 2),
                "5999", "RPA000000000001", "W0000001", "286001", "0012", 12, null, "630612000123", null);
        Record chargeback = new Record(Type.CHARGEBACK, "400101", "286001", "630612000123", "QWE123", 1999, "840",
                LocalDate.of(2026, 11, 3), "5999", "RPA000000000001", "W0000001", "286001", "0012", 0, "1310", null, "dsp_abc");

        var parsed = ClearingFile.parse(ClearingFile.write(HEADER, List.of(SALE, credit, chargeback)));

        assertThat(parsed.header()).isEqualTo(HEADER);
        assertThat(parsed.records()).containsExactly(SALE, credit, chargeback);
    }

    @Test
    void noticesATruncatedOrAlteredFile() {
        String file = ClearingFile.write(HEADER, List.of(SALE, SALE.withInterchange(5)));
        String[] lines = file.split("\n");

        String missingRecord = lines[0] + "\n" + lines[1] + "\n" + lines[3] + "\n";
        assertThatThrownBy(() -> ClearingFile.parse(missingRecord)).hasMessageContaining("trailer says 2 records");

        String noTrailer = lines[0] + "\n" + lines[1] + "\n" + lines[2] + "\n";
        assertThatThrownBy(() -> ClearingFile.parse(noTrailer)).hasMessageContaining("isn't a trailer");

        String shortLine = file.replaceFirst("\n05", "\n05X");
        assertThatThrownBy(() -> ClearingFile.parse(shortLine)).hasMessageContaining("characters, not 160");
    }

    @Test
    void refusesWhatWouldBreakTheLayout() {
        assertThatThrownBy(() -> ClearingFile.write(HEADER, List.of(SALE.withInterchange(-1)))).hasMessageContaining("never negative");
        assertThatThrownBy(() -> ClearingFile.write(HEADER, List.of(SALE.withIssuer("12345678901234")))).hasMessageContaining("11 digits");
    }
}
