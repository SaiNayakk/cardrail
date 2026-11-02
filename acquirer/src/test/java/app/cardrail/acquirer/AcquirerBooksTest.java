package app.cardrail.acquirer;

import app.cardrail.common.clearing.ClearingFile;
import app.cardrail.common.clearing.ClearingFile.Record;
import app.cardrail.common.clearing.ClearingFile.Type;
import app.cardrail.common.iso.IsoServer;
import app.cardrail.iso8583.Iso8583Codec;
import app.cardrail.iso8583.MessageSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.name=acquirer",
        "acquirer.network-timeout=1s",
        "acquirer.reversal-every=1h",
        "acquirer.echo-every=1h",
        "acquirer.clearing-every=1h",
        "acquirer.notify-every=1h",
        "cardrail.internal-key=test-internal-key-0123456789"})
class AcquirerBooksTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static final List<String> filesReceived = new CopyOnWriteArrayList<>();
    static final List<String> chargebackFiles = new CopyOnWriteArrayList<>();
    static final IsoServer iso;
    static final HttpServer http;

    static {
        try {
            iso = new IsoServer("fake-railnet", "127.0.0.1", 0, new Iso8583Codec(MessageSpec.RAILNET),
                    request -> request.response("00").set(38, "OK1234"), 4);
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/internal/v1/clearing/files", ex -> {
                if (ex.getRequestMethod().equals("POST")) {
                    String file = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.US_ASCII);
                    filesReceived.add(file);
                    var parsed = ClearingFile.parse(file);
                    reply(ex, Map.of("fileId", parsed.header().fileId(), "accepted", parsed.records().size(), "rejected", List.of()));
                } else {
                    List<Map<String, Object>> out = new java.util.ArrayList<>();
                    for (int i = 0; i < chargebackFiles.size(); i++) {
                        out.add(Map.of("seq", i + 1, "fileId", "CB" + i, "content", chargebackFiles.get(i)));
                    }
                    long after = Long.parseLong(ex.getRequestURI().getQuery().replaceAll(".*after=", ""));
                    reply(ex, out.stream().filter(f -> ((Integer) f.get("seq")) > after).toList());
                }
            });
            http.createContext("/internal/v1/settlements", ex -> reply(ex, List.of()));
            http.createContext("/internal/v1/notifications", ex -> {
                ex.sendResponseHeaders(204, -1);
                ex.close();
            });
            http.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void reply(HttpExchange ex, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&currentSchema=acquirer");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("acquirer.network-port", iso::port);
        String fake = "http://127.0.0.1:" + http.getAddress().getPort();
        registry.add("acquirer.network-url", () -> fake);
        registry.add("acquirer.gateway-url", () -> fake);
    }

    @AfterAll
    static void stop() {
        iso.close();
        http.stop(0);
    }

    static final AtomicInteger N = new AtomicInteger();

    @Autowired AcquirerController controller;
    @Autowired Authorising authorising;
    @Autowired Reversing reversing;
    @Autowired AcquirerBooks books;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;

    @Test
    void presentsWhatWasCapturedAndPaysTheMerchantWhatSettledLessItsFee() {
        String mid = merchant();
        String sale = capturedSale(mid, 10_000);
        String uncaptured = authorising.authorise(request(mid, 2_000, null)).rrn();
        long mdrBefore = books.ledger().balance("income:mdr");

        books.present();

        var file = ClearingFile.parse(filesReceived.getLast());
        assertThat(file.records()).extracting(Record::rrn).contains(sale).doesNotContain(uncaptured);
        // MDR 2.50% of $100: the merchant is owed $97.50, Railnet owes us $100
        assertThat(books.ledger().balance("merchant:" + mid)).isEqualTo(-9_750);
        assertThat(books.ledger().balance("income:mdr") - mdrBefore).isEqualTo(-250);

        int filesBefore = filesReceived.size();
        books.present();
        // nothing new to present from this merchant: either no file at all, or one without this sale
        assertThat(filesReceived.subList(filesBefore, filesReceived.size()))
                .allSatisfy(f -> assertThat(ClearingFile.parse(f).records()).extracting(Record::rrn).doesNotContain(sale));

        // Railnet settles: keeps interchange 26c and its fee 13c, pays the rest
        long dueBefore = books.ledger().balance(AcquirerBooks.DUE_RAILNET);
        tx.executeWithoutResult(s -> books.book(new AcquirerBooks.Report("stl_a_" + N.incrementAndGet(), LocalDate.now(), "400101",
                -(dueBefore - 26 - 13), List.of(new AcquirerBooks.Item("SALE", "400101", "286001", sale, 10_000, 26, 13)))));
        assertThat(books.ledger().balance(AcquirerBooks.DUE_RAILNET)).isZero();

        var payouts = books.payOut();
        var payout = payouts.stream().filter(p -> p.mid().equals(mid)).findFirst().orElseThrow();
        assertThat(payout.amountCents()).isEqualTo(9_750);
        assertThat(books.ledger().balance("merchant:" + mid)).isZero();
        assertThat(books.ledger().total()).isZero();
        assertThat(books.payOut()).noneMatch(p -> p.mid().equals(mid));   // paid once
    }

    @Test
    void aChargebackIsTakenFromTheMerchantAndARepresentmentGivesItBack() {
        String mid = merchant();
        String sale = capturedSale(mid, 5_000);
        books.present();

        chargebackFiles.add(ClearingFile.write(new ClearingFile.Header("CB" + N.incrementAndGet(), "900001", "400101",
                LocalDate.now(), LocalDateTime.now()), List.of(new Record(Type.CHARGEBACK, "400101", "286001", sale, "OK1234", 5_000,
                "840", LocalDate.now(), "5999", mid, "W0000001", "286001", "0012", 0, "1310", null, "dsp_test" + N.get()))));
        books.collectFiles();

        String disputeId = "dsp_test" + N.get();
        assertThat(books.ledger().balance("merchant:" + mid)).isEqualTo(-(5_000 - 125) + 5_000);
        assertThat(jdbc.sql("select status from disputes where id = ?").param(disputeId).query(String.class).single())
                .isEqualTo("NEEDS_RESPONSE");

        books.answer(disputeId, "Delivered on 2 November, signed for by the cardholder.");
        books.present();

        assertThat(ClearingFile.parse(filesReceived.getLast()).records()).anyMatch(r -> r.type() == Type.REPRESENTMENT && r.rrn().equals(sale));
        assertThat(jdbc.sql("select status from disputes where id = ?").param(disputeId).query(String.class).single()).isEqualTo("WON");
        assertThat(books.ledger().balance("merchant:" + mid)).isEqualTo(-(5_000 - 125));
        assertThat(books.ledger().total()).isZero();
    }

    private String merchant() {
        return controller.onboard(new AcquirerController.OnboardRequest("Books " + N.incrementAndGet(), "5999", "Austin", "US", null)).mid();
    }

    private String capturedSale(String mid, long cents) {
        String rrn = authorising.authorise(request(mid, cents, null)).rrn();
        reversing.capture(rrn, cents);
        return rrn;
    }

    private static Authorising.Request request(String mid, long cents, String original) {
        String rrn = "K" + String.format("%011d", N.incrementAndGet());
        return new Authorising.Request(mid, null, "2860010000000012", "3012", null, cents, "USD", rrn, original);
    }
}
