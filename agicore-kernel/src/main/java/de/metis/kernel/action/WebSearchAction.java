package de.metis.kernel.action;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Web search via DuckDuckGo (HTML endpoint — no API key required).
 * <p>
 * Two-phase: Instant Answer API for structured facts → HTML search for links.
 * Category: read. Approval: AUTO.
 * <p>
 * Evolvable: switch to SearchXNG self-hosted, add Ecosia fallback,
 * integrate with Apache Nutch for deep crawl.
 */
public class WebSearchAction implements Action {

    private static final Logger LOG = Logger.getLogger(WebSearchAction.class.getName());
    public static final String NAME = "websearch";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Pattern LINK_PATTERN = Pattern.compile(
            "<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>([^<]+)</a>",
            Pattern.DOTALL);
    private static final Pattern SNIPPET_PATTERN = Pattern.compile(
            "<a[^>]*class=\"result__snippet\"[^>]*>([^<]+(?:<[^>]+>[^<]*</[^>]+>)?[^<]*)</a>",
            Pattern.DOTALL);
    private static final Pattern ABSTRACT_PATTERN = Pattern.compile(
            "\"Abstract\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ABSTRACT_URL_PATTERN = Pattern.compile(
            "\"AbstractURL\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern RELATED_PATTERN = Pattern.compile(
            "\"Text\"\\s*:\\s*\"([^\"]+)\"");

    private final String query;
    private final int maxResults;

    public WebSearchAction(String query) {
        this(query, 5);
    }

    public WebSearchAction(String query, int maxResults) {
        this.query = query;
        this.maxResults = Math.min(maxResults, 10);
    }

    @Override public String name() { return NAME; }
    @Override public String category() { return "read"; }

    @Override
    public ActionResult execute() {
        var now = Instant.now();
        try {
            var results = new ArrayList<SearchResult>();

            // Phase 1: DuckDuckGo Instant Answer API (structured facts)
            try {
                String iaUrl = "https://api.duckduckgo.com/?q="
                        + URLEncoder.encode(query, StandardCharsets.UTF_8)
                        + "&format=json&no_html=1&skip_disambig=1";
                var req = HttpRequest.newBuilder(URI.create(iaUrl))
                        .timeout(Duration.ofSeconds(8))
                        .GET()
                        .header("User-Agent", "Metis AGI/0.6 (Java; websearch-action)")
                        .build();
                String iaBody = HTTP.send(req, HttpResponse.BodyHandlers.ofString()).body();

                // Extract Abstract (knowledge graph answer)
                Matcher absMatcher = ABSTRACT_PATTERN.matcher(iaBody);
                if (absMatcher.find() && !absMatcher.group(1).isEmpty()) {
                    String abs = absMatcher.group(1).replace("\\n", "\n").replace("\\\"", "\"");
                    Matcher urlMatcher = ABSTRACT_URL_PATTERN.matcher(iaBody);
                    String url = urlMatcher.find() ? urlMatcher.group(1) : "";
                    results.add(new SearchResult("DuckDuckGo Instant Answer", url, abs, true));
                }

                // Extract RelatedTopics as additional results
                Matcher relMatcher = RELATED_PATTERN.matcher(iaBody);
                int relCount = 0;
                while (relMatcher.find() && results.size() < maxResults) {
                    String text = relMatcher.group(1).replace("\\n", " ").replace("\\\"", "\"");
                    if (!text.isEmpty() && text.length() > 10) {
                        results.add(new SearchResult(
                                "Related: " + extractTitle(text),
                                "",
                                text,
                                false));
                    }
                    relCount++;
                    if (relCount > 15) break; // safety limit
                }
            } catch (Exception e) {
                LOG.fine("Instant Answer API failed: " + e.getMessage());
            }

            // Phase 2: HTML search for web links (if not enough results)
            if (results.size() < maxResults) {
                try {
                    String htmlUrl = "https://html.duckduckgo.com/html/?q="
                            + URLEncoder.encode(query, StandardCharsets.UTF_8);
                    var req = HttpRequest.newBuilder(URI.create(htmlUrl))
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                            .build();
                    String html = HTTP.send(req, HttpResponse.BodyHandlers.ofString()).body();

                    // Extract links
                    Matcher linkMatcher = LINK_PATTERN.matcher(html);
                    List<String[]> links = new ArrayList<>();
                    while (linkMatcher.find() && links.size() < maxResults * 2) {
                        String href = linkMatcher.group(1);
                        String title = linkMatcher.group(2).replaceAll("<[^>]+>", "").trim();
                        if (!href.isEmpty() && !title.isEmpty() && !href.contains("duckduckgo.com")) {
                            links.add(new String[]{title, href});
                        }
                    }

                    // Extract snippets
                    Matcher snippetMatcher = SNIPPET_PATTERN.matcher(html);
                    List<String> snippets = new ArrayList<>();
                    while (snippetMatcher.find() && snippets.size() < maxResults * 2) {
                        String s = snippetMatcher.group(1).replaceAll("<[^>]+>", "").trim();
                        if (!s.isEmpty()) snippets.add(s);
                    }

                    // Match links with snippets
                    for (int i = 0; i < Math.min(links.size(), maxResults); i++) {
                        String snippet = i < snippets.size() ? snippets.get(i) : "";
                        String cleanUrl = cleanUrl(links.get(i)[1]);
                        results.add(new SearchResult(links.get(i)[0], cleanUrl, snippet, false));
                    }
                } catch (Exception e) {
                    LOG.fine("HTML search failed: " + e.getMessage());
                }
            }

            if (results.isEmpty()) {
                return ActionResult.fail(NAME, "No results for query: " + query, now);
            }

            String summary = buildSummary(query, results);
            LOG.info(() -> "WebSearch '" + query + "': " + results.size() + " results, "
                    + results.stream().filter(SearchResult::instantAnswer).count() + " IA");

            return ActionResult.ok(NAME, summary, now);

        } catch (Exception e) {
            LOG.warning("WebSearch error: " + e.getMessage());
            return ActionResult.fail(NAME, e.getMessage(), now);
        }
    }

    private String cleanUrl(String url) {
        if (url.startsWith("//")) url = "https:" + url;
        // DuckDuckGo wraps external links through their redirector
        if (url.contains("duckduckgo.com/l/?uddg=")) {
            String decoded = url.replaceAll(".*uddg=([^&]+).*", "$1");
            try {
                decoded = java.net.URLDecoder.decode(decoded, StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
            return decoded;
        }
        return url;
    }

    private String extractTitle(String text) {
        // Extract first meaningful part as title (max 80 chars)
        String clean = text.replaceAll("\\s*-\\s*.*", "").trim();
        if (clean.length() > 80) clean = clean.substring(0, 77) + "...";
        return clean;
    }

    private String buildSummary(String query, List<SearchResult> results) {
        var sb = new StringBuilder();
        sb.append("Web search: \"").append(query).append("\"\n");
        sb.append("=".repeat(Math.min(40, query.length() + 15))).append("\n");

        int idx = 1;
        for (var r : results) {
            sb.append(idx).append(". ");
            if (r.instantAnswer()) sb.append("[IA] ");
            sb.append(r.title());
            if (!r.url().isEmpty()) sb.append("\n   ").append(r.url());
            if (!r.snippet().isEmpty()) sb.append("\n   ").append(r.snippet());
            sb.append("\n");
            if (idx++ >= maxResults) break;
        }

        return sb.toString();
    }

    /**
     * Immutable search result.
     */
    public record SearchResult(
            String title,
            String url,
            String snippet,
            boolean instantAnswer
    ) {
        public java.util.Map<String, Object> toDict() {
            var m = new java.util.LinkedHashMap<String, Object>();
            m.put("title", title);
            m.put("url", url);
            m.put("snippet", snippet);
            m.put("instantAnswer", instantAnswer);
            return m;
        }
    }
}
