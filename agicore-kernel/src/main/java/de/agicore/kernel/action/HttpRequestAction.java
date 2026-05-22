package de.agicore.kernel.action;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Issues an HTTP request and returns status, headers, and body.
 * <p>
 * Uses the JDK {@link HttpClient} (no external dependency).
 * Supports GET, POST, PUT, DELETE with optional JSON body and headers.
 * <p>
 * Extension point: authentication, retry logic, and circuit-breaking
 * belong in a decorator around this action.
 */
public class HttpRequestAction implements Action {

    private static final Logger LOG = Logger.getLogger(HttpRequestAction.class.getName());
    public static final String NAME = "http";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final String method;           // GET, POST, PUT, DELETE
    private final URI uri;
    private final Map<String, String> headers;
    private final Optional<String> body;   // JSON payload for POST/PUT

    /**
     * @param method  HTTP method (uppercase, e.g. "GET")
     * @param uri     target URL
     * @param headers optional request headers
     * @param body    optional JSON body (POST/PUT)
     */
    public HttpRequestAction(String method, URI uri,
                             Map<String, String> headers,
                             Optional<String> body) {
        this.method = method.toUpperCase();
        this.uri = uri;
        this.headers = Map.copyOf(headers);
        this.body = body;
    }

    /** Convenience: GET without body. */
    public HttpRequestAction(URI uri) {
        this("GET", uri, Map.of(), Optional.empty());
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ActionResult execute() {
        Instant start = Instant.now();
        try {
            var builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30));

            headers.forEach(builder::header);

            if (body.isPresent() && ("POST".equals(method) || "PUT".equals(method))) {
                builder.method(method, HttpRequest.BodyPublishers.ofString(body.get()));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }

            HttpResponse<String> response = CLIENT.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());

            String resultBody = response.body();
            if (resultBody != null && resultBody.length() > 4_096) {
                resultBody = resultBody.substring(0, 4_096) + "... [truncated]";
            }

            String summary = "HTTP " + response.statusCode() + " " + method + " " + uri + "\n" + resultBody;

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                LOG.fine(() -> "HTTP " + method + " " + uri + " → " + response.statusCode());
                return ActionResult.ok(NAME, summary, start);
            } else {
                return ActionResult.fail(NAME,
                        "HTTP " + response.statusCode() + ": " + resultBody, start);
            }
        } catch (Exception e) {
            LOG.warning(() -> "HTTP " + method + " " + uri + " failed: " + e.getMessage());
            return ActionResult.fail(NAME,
                    "HTTP error: " + e.getClass().getSimpleName() + " - " + e.getMessage(), start);
        }
    }

    @Override
    public String toString() {
        return "HttpRequestAction[" + method + " " + uri + "]";
    }
}
