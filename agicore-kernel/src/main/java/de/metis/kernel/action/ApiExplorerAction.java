package de.metis.kernel.action;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * API-Explorer — erkundet HTTP-Endpunkte durch Probing.
 * <p>
 * Probiert eine Reihe üblicher API-Pfade an einem Basis-Host aus
 * und dokumentiert, welche antworten (Status, Content-Type, erste Bytes).
 * <p>
 * Zwei Modi:
 * <ul>
 *   <li>{@code probe} — testet Standard-Pfade (/api, /health, /status, /metrics, /)</li>
 *   <li>{@code discover} — probiert auch REST-konforme Pfade (/api/v1, /api/v2, /docs)</li>
 * </ul>
 * <p>
 * Ergebnisse werden strukturiert zurückgegeben — Metis kann daraus
 * Beliefs über die API-Landschaft bilden.
 */
public class ApiExplorerAction implements Action {

    private static final Logger LOG = Logger.getLogger(ApiExplorerAction.class.getName());

    public static final String NAME = "api-explore";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    // ── Standard-Pfade für Probing ─────────────────────────────────

    private static final List<String> PROBE_PATHS = List.of(
            "/", "/api", "/health", "/status", "/metrics", "/info", "/ping",
            "/api/status", "/api/health", "/api/v1", "/api/v2",
            "/docs", "/swagger", "/openapi.json", "/.well-known"
    );

    private static final List<String> DISCOVER_PATHS = List.of(
            "/", "/api", "/health", "/status", "/metrics", "/info", "/ping",
            "/api/status", "/api/health", "/api/v1", "/api/v2",
            "/api/v1/status", "/api/v2/status", "/api/v1/health",
            "/docs", "/swagger", "/openapi.json", "/swagger-ui.html",
            "/graphql", "/.well-known", "/robots.txt", "/sitemap.xml",
            "/api/users", "/api/tags", "/api/version", "/api/config",
            "/api/system", "/api/info", "/api/ping", "/api/time",
            "/admin", "/login", "/register", "/actuator/health"
    );

    private final URI baseUri;
    private final boolean discoverMode;

    /**
     * @param baseUrl     Basis-URL des zu erkundenden Hosts (z.B. "http://192.168.22.204:8080")
     * @param discoverMode true = intensivere Erkundung mit mehr Pfaden
     */
    public ApiExplorerAction(String baseUrl, boolean discoverMode) {
        this.baseUri = URI.create(baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        this.discoverMode = discoverMode;
    }

    /** Convenience: probe mode. */
    public ApiExplorerAction(String baseUrl) {
        this(baseUrl, false);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ActionResult execute() {
        Instant start = Instant.now();
        List<String> paths = discoverMode ? DISCOVER_PATHS : PROBE_PATHS;

        StringBuilder report = new StringBuilder();
        report.append("API-Erkundung: ").append(baseUri).append("\n");
        report.append("=" .repeat(60)).append("\n\n");

        int found = 0;
        int errors = 0;
        List<EndpointInfo> endpoints = new ArrayList<>();

        for (String path : paths) {
            try {
                URI uri = URI.create(baseUri + path);
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(REQUEST_TIMEOUT)
                        .header("User-Agent", "Metis-AGI/0.2 API-Explorer")
                        .header("Accept", "application/json, text/html, */*")
                        .GET()
                        .build();

                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

                int status = response.statusCode();
                String contentType = response.headers().firstValue("Content-Type").orElse("-");
                int bodyLen = response.body().length();

                String symbol;
                if (status >= 200 && status < 300) { symbol = "✓"; found++; }
                else if (status >= 300 && status < 400) symbol = "→";
                else if (status == 404) { symbol = "·"; errors++; }
                else { symbol = "✗"; errors++; }

                if (status != 404 || discoverMode) { // Zeige 404 nur im Discover-Mode
                    report.append(String.format("  %s %-30s → HTTP %d  %s  (%d bytes)\n",
                            symbol, path, status, contentType, bodyLen));

                    if (status >= 200 && status < 300 && bodyLen > 0 && bodyLen < 500) {
                        String preview = response.body().replace("\n", " ").trim();
                        if (preview.length() > 100) preview = preview.substring(0, 97) + "...";
                        report.append("      ").append(preview).append("\n");
                    }

                    endpoints.add(new EndpointInfo(path, status, contentType, bodyLen));
                }

            } catch (Exception e) {
                String errorType = e.getClass().getSimpleName();
                report.append(String.format("  ✗ %-30s → %s\n", path, errorType));
                errors++;
            }
        }

        report.append("\n");
        report.append("Ergebnis: ").append(found).append(" Endpunkte gefunden");
        if (errors > 0) report.append(", ").append(errors).append(" Fehler/Nicht-gefunden");
        report.append("\n\n");

        // Zusammenfassung der wichtigsten Funde
        if (!endpoints.isEmpty()) {
            report.append("Wichtigste Endpunkte:\n");
            endpoints.stream()
                    .filter(e -> e.status >= 200 && e.status < 300)
                    .sorted(Comparator.comparingInt(e -> e.bodyLen > 0 ? 0 : 1))
                    .limit(5)
                    .forEach(e -> report.append("  • ").append(baseUri).append(e.path)
                            .append(" → ").append(e.contentType)
                            .append(" (").append(e.bodyLen).append(" bytes)\n"));
        }

        final int foundEndpoints = found;
        LOG.fine(() -> "API-Explore: " + foundEndpoints + " endpoints on " + baseUri);
        return ActionResult.ok(NAME, report.toString(), start);
    }

    private record EndpointInfo(String path, int status, String contentType, int bodyLen) {}

    @Override
    public String toString() {
        return "ApiExplorerAction[" + baseUri + (discoverMode ? " discover" : " probe") + "]";
    }
}
