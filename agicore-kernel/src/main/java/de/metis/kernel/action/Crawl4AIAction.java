package de.metis.kernel.action;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Logger;

/**
 * Web-Scraping-Action via Crawl4AI REST-API.
 * <p>
 * Sendet eine URL an Crawl4AI (Port 11235 auf kali oder anderem Host) und
 * erhält den extrahierten Markdown-Text der Webseite zurück. Nutzt den
 * Crawl4AI-Docker-Container, der bereits auf kali läuft.
 * <p>
 * Erweiterbar: POST-Body anpassen, CSS-Selektoren, JavaScript-Rendering,
 * Screenshot-Optionen etc. — alles von Crawl4AI unterstützt.
 */
public class Crawl4AIAction implements Action {

    private static final Logger LOG = Logger.getLogger(Crawl4AIAction.class.getName());

    public static final String NAME = "webscrape";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String crawl4aiUrl;
    private final URI targetUri;
    private final Duration timeout;

    /**
     * @param crawl4aiUrl Crawl4AI REST-Endpunkt (z.B. "http://192.168.22.200:11235")
     * @param targetUri   zu scrapende Webseite
     * @param timeout     Timeout für den gesamten Vorgang
     */
    public Crawl4AIAction(String crawl4aiUrl, URI targetUri, Duration timeout) {
        this.crawl4aiUrl = crawl4aiUrl.endsWith("/") ? crawl4aiUrl : crawl4aiUrl;
        this.targetUri = targetUri;
        this.timeout = timeout;
    }

    /** Convenience: kali's Crawl4AI mit 30s Timeout. */
    public Crawl4AIAction(String targetUrl) {
        this("http://192.168.22.200:11235", URI.create(targetUrl), Duration.ofSeconds(30));
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ActionResult execute() {
        Instant start = Instant.now();
        try {
            // Crawl4AI REST API: POST /crawl with JSON body
            String jsonBody = String.format("""
                    {
                      "urls": ["%s"],
                      "extract_mode": "markdown",
                      "max_chars": 5000
                    }
                    """, targetUri.toString());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(crawl4aiUrl + "/crawl"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.warning("Crawl4AI returned " + response.statusCode());
                return ActionResult.fail(NAME,
                        "Crawl4AI-Fehler: HTTP " + response.statusCode(), start);
            }

            // Extrahiere Markdown aus der Antwort
            String markdown = extractMarkdown(response.body());
            if (markdown == null || markdown.isBlank()) {
                return ActionResult.fail(NAME, "Kein Inhalt extrahiert", start);
            }

            // Auf sinnvolle Länge kürzen
            final String result = markdown.length() > 5000
                    ? markdown.substring(0, 5000) + "\n... [gekürzt]" : markdown;

            LOG.fine(() -> "Webscrape: " + targetUri + " → " + result.length() + " Zeichen");
            return ActionResult.ok(NAME,
                    "Webseite: " + targetUri + "\n\n" + result, start);

        } catch (Exception e) {
            LOG.warning("Crawl4AI-Fehler: " + e.getMessage());
            return ActionResult.fail(NAME,
                    "Crawl4AI-Fehler: " + e.getClass().getSimpleName() + " - " + e.getMessage(), start);
        }
    }

    /** Extrahiere Markdown-Content aus der Crawl4AI-JSON-Antwort. */
    private String extractMarkdown(String json) {
        // Crawl4AI Antwort: {"results":[{"markdown":"..."}]} oder {"markdown":"..."}
        String[] keys = {"\"markdown\":\"", "\"content\":\"", "\"text\":\""};

        for (String key : keys) {
            int start = json.indexOf(key);
            if (start >= 0) {
                start += key.length();
                StringBuilder val = new StringBuilder();
                for (int i = start; i < json.length(); i++) {
                    char c = json.charAt(i);
                    if (c == '\\' && i + 1 < json.length()) {
                        char next = json.charAt(i + 1);
                        switch (next) {
                            case 'n' -> { val.append('\n'); i++; }
                            case 't' -> { val.append('\t'); i++; }
                            case 'r' -> { val.append('\r'); i++; }
                            case '"' -> { val.append('"'); i++; }
                            case '\\' -> { val.append('\\'); i++; }
                            default -> val.append(c);
                        }
                    } else if (c == '"') {
                        break;
                    } else {
                        val.append(c);
                    }
                }
                String result = val.toString();
                if (!result.isBlank()) return result;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "Crawl4AIAction[" + targetUri + "]";
    }
}
