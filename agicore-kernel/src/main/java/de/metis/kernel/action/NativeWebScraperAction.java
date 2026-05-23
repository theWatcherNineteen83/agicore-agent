package de.metis.kernel.action;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Native Web-Scraper — holt Webseiten und extrahiert lesbaren Text.
 * <p>
 * Ersetzt Crawl4AI. Nutzt ausschließlich JDK-Bordmittel:
 * <ul>
 *   <li>{@link HttpClient} für HTTP-Requests</li>
 *   <li>Regex-basiertes HTML-Stripping für Text-Extraktion</li>
 *   <li>Keine externen Abhängigkeiten</li>
 * </ul>
 * <p>
 * Extrahiert: Titel, Meta-Description, Body-Text (von HTML befreit).
 * Limitiert auf 5000 Zeichen pro Seite.
 */
public class NativeWebScraperAction implements Action {

    private static final Logger LOG = Logger.getLogger(NativeWebScraperAction.class.getName());

    public static final String NAME = "webscrape";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final int MAX_CONTENT_CHARS = 5000;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

    private static final Pattern SCRIPT_STYLE = Pattern.compile(
            "<(script|style|noscript|iframe|svg)[^>]*>.*?</\\1>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern HTML_ENTITY = Pattern.compile("&[a-z]+;|&#\\d+;");
    private static final Pattern WHITESPACE = Pattern.compile("\\s{3,}");
    private static final Pattern TITLE = Pattern.compile(
            "<title[^>]*>(.*?)</title>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern META_DESC = Pattern.compile(
            "<meta[^>]+name=[\"']description[\"'][^>]+content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);

    private final URI targetUri;
    private final Duration timeout;

    public NativeWebScraperAction(URI targetUri) {
        this(targetUri, DEFAULT_TIMEOUT);
    }

    public NativeWebScraperAction(URI targetUri, Duration timeout) {
        this.targetUri = targetUri;
        this.timeout = timeout;
    }

    /** Convenience: from URL string. */
    public NativeWebScraperAction(String url) {
        this(URI.create(url));
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ActionResult execute() {
        Instant start = Instant.now();
        try {
            HttpRequest request = HttpRequest.newBuilder(targetUri)
                    .timeout(timeout)
                    .header("User-Agent", "Metis-AGI/0.2 (Self-Evolving Agent)")
                    .header("Accept", "text/html, text/plain, */*")
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 400) {
                return ActionResult.fail(NAME,
                        "HTTP " + response.statusCode() + " für " + targetUri, start);
            }

            String contentType = response.headers().firstValue("Content-Type").orElse("");
            String body = response.body();

            String extracted;
            if (contentType.contains("text/html") || body.contains("<html")) {
                extracted = extractFromHtml(body);
            } else if (contentType.contains("application/json")) {
                extracted = extractFromJson(body);
            } else {
                extracted = extractPlainText(body);
            }

            if (extracted.isBlank()) {
                return ActionResult.fail(NAME, "Kein lesbarer Inhalt auf " + targetUri, start);
            }

            LOG.fine(() -> "Webscrape: " + targetUri + " → " + extracted.length() + " Zeichen");
            return ActionResult.ok(NAME,
                    "Webseite: " + targetUri + "\n" + extracted, start);

        } catch (Exception e) {
            return ActionResult.fail(NAME,
                    "Webscrape-Fehler: " + e.getClass().getSimpleName() + " - " + e.getMessage(), start);
        }
    }

    // ── HTML-Extraktion ────────────────────────────────────────────

    private String extractFromHtml(String html) {
        StringBuilder result = new StringBuilder();

        // Titel
        var titleMatcher = TITLE.matcher(html);
        if (titleMatcher.find()) {
            String title = titleMatcher.group(1).trim();
            result.append("Titel: ").append(title).append("\n\n");
        }

        // Meta-Description
        var descMatcher = META_DESC.matcher(html);
        if (descMatcher.find()) {
            result.append("Beschreibung: ").append(descMatcher.group(1).trim()).append("\n\n");
        }

        // Body-Text extrahieren
        String cleaned = SCRIPT_STYLE.matcher(html).replaceAll(" ");
        cleaned = HTML_TAG.matcher(cleaned).replaceAll(" ");
        cleaned = HTML_ENTITY.matcher(cleaned).replaceAll(" ");
        cleaned = WHITESPACE.matcher(cleaned).replaceAll("\n");
        cleaned = cleaned.replaceAll("&nbsp;", " ").replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<").replaceAll("&gt;", ">")
                .replaceAll("&quot;", "\"").replaceAll("&apos;", "'");

        // Zeilen bereinigen
        StringBuilder text = new StringBuilder();
        for (String line : cleaned.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.length() > 20 && !trimmed.matches("^[\\s{}().,;:!?\\[\\]@#$%^&*+=|\\\\/~`'_-]+$")) {
                text.append(trimmed).append("\n");
            }
        }

        String body = text.toString().trim();
        if (body.length() > MAX_CONTENT_CHARS) {
            body = body.substring(0, MAX_CONTENT_CHARS) + "\n... [gekürzt]";
        }
        result.append(body);

        return result.toString().trim();
    }

    // ── JSON-Extraktion ────────────────────────────────────────────

    private String extractFromJson(String json) {
        if (json.length() > MAX_CONTENT_CHARS) {
            json = json.substring(0, MAX_CONTENT_CHARS) + "\n... [gekürzt]";
        }
        return "JSON-Antwort von " + targetUri + ":\n" + json;
    }

    // ── Plain-Text ─────────────────────────────────────────────────

    private String extractPlainText(String text) {
        if (text.length() > MAX_CONTENT_CHARS) {
            text = text.substring(0, MAX_CONTENT_CHARS) + "\n... [gekürzt]";
        }
        return text.trim();
    }

    @Override
    public String toString() {
        return "NativeWebScraperAction[" + targetUri + "]";
    }
}
