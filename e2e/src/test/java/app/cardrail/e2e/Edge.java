package app.cardrail.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** A visitor's view: only the public edge, as a browser would use it. */
final class Edge {

    record Response(int status, JsonNode body) {}

    static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final String base;

    Edge(String base) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    static String baseUrl() {
        String url = System.getProperty("e2e.baseUrl");
        return url == null || url.isBlank() || url.startsWith("${") ? null : url;
    }

    Response get(String path) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET(), Map.of());
    }

    Response post(String path, Object body) {
        return post(path, body, Map.of());
    }

    Response post(String path, Object body, Map<String, String> headers) {
        try {
            return send(HttpRequest.newBuilder(URI.create(base + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))), headers);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    String raw(String path) {
        try {
            return HTTP.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private Response send(HttpRequest.Builder request, Map<String, String> headers) {
        headers.forEach(request::header);
        try {
            HttpResponse<String> response = HTTP.send(request.timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
            JsonNode body;
            try {
                body = response.body().isEmpty() ? JSON.nullNode() : JSON.readTree(response.body());
            } catch (IOException notJson) {
                body = JSON.getNodeFactory().textNode(response.body());
            }
            return new Response(response.statusCode(), body);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Pays the demo shop for a product with a card, the way the web app does: hosted form, then checkout. */
    JsonNode pay(String product, int quantity, JsonNode card) {
        return pay(product, quantity, card, UUID.randomUUID().toString());
    }

    JsonNode pay(String product, int quantity, JsonNode card, String orderId) {
        return checkout(product, quantity, token(card), orderId);
    }

    /** What the hosted form does: the card to the vault, a token back. */
    String token(JsonNode card) {
        String key = get("/api/shop/v1/config").body().get("publishableKey").asText();
        String[] expiry = card.get("expiry").asText().split("/");
        Response token = post("/hosted/tokens", Map.of("publishableKey", key, "number", card.get("number").asText(),
                "expMonth", Integer.parseInt(expiry[0]), "expYear", Integer.parseInt(expiry[1]), "cvc", card.get("cvv2").asText()));
        if (token.status() != 201) {
            throw new AssertionError("tokenising failed: " + token.body());
        }
        return token.body().get("id").asText();
    }

    /** What the shop's page does with a token: asks the shop's server to take the payment. */
    JsonNode checkout(String product, int quantity, String token, String orderId) {
        Response payment = post("/api/shop/v1/checkout", Map.of("product", product, "quantity", quantity,
                "token", token, "orderId", orderId));
        if (payment.status() != 200) {
            throw new AssertionError("checkout failed: " + payment.status() + " " + payment.body());
        }
        return payment.body();
    }

    JsonNode testCard(String outcomeStartsWith) {
        for (JsonNode card : get("/api/test-cards").body()) {
            if (card.get("outcome").asText().startsWith(outcomeStartsWith)) {
                return card;
            }
        }
        throw new AssertionError("no test card for " + outcomeStartsWith);
    }
}
