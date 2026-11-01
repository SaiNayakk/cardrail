package app.cardrail.trace;

import app.cardrail.common.trace.TraceReporter;
import app.cardrail.iso8583.IsoMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.name=trace",
        "cardrail.internal-key=test-internal-key-0123456789"})
class TraceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&currentSchema=trace");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;

    @Test
    void partiesReportAndAnyoneCanReadTheMaskedTrace() throws Exception {
        TraceReporter acquirer = new TraceReporter("railpay-acquiring", "http://127.0.0.1:" + port,
                "test-internal-key-0123456789", json);
        IsoMessage request = new IsoMessage("0100").set(2, "2860010000000012").set(4, "000000001999").set(11, "000001")
                .set(14, "3012").set(37, "630612000001");

        acquirer.iso("630612000001", "railpay-acquiring", "railnet", request);
        acquirer.note("630612000001", "railpay-acquiring", "railpay", "Approved", Map.of("status", "APPROVED"));

        JsonNode trace = null;
        for (int i = 0; i < 50; i++) {
            var response = http.getForEntity("/v1/traces/630612000001", String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                trace = json.readTree(response.getBody());
                if (trace.get("events").size() == 2) break;
            }
            Thread.sleep(100);
        }
        assertThat(trace).isNotNull();
        JsonNode first = trace.get("events").get(0);
        assertThat(first.get("mti").asText()).isEqualTo("0100");
        assertThat(first.get("summary").asText()).isEqualTo("Authorisation request");
        assertThat(first.get("details").get("2").asText()).isEqualTo("286001******0012");
        assertThat(first.get("details").get("14").asText()).isEqualTo("****");
        assertThat(trace.toString()).doesNotContain("2860010000000012");
        assertThat(trace.get("events").get(1).get("kind").asText()).isEqualTo("note");

        assertThat(http.getForObject("/v1/traces", String.class)).contains("630612000001");
    }

    @Test
    void onlyPartiesCanReport() {
        var response = http.postForEntity("/internal/v1/events", Map.of("traceKey", "x"), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }
}
