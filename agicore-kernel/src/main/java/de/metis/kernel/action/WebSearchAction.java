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
public class WebSearchAction implements Action, GoalAwareAction {

    private volatile de.metis.kernel.goal.Goal currentGoal = null;

    @Override public void setCurrentGoal(de.metis.kernel.goal.Goal g) { this.currentGoal = g; }

    /** Suchbestr aus dem aktuellen Goal ableiten; sonst Konstruktor-Query. */
    private String effectiveQuery() {
        de.metis.kernel.goal.Goal g = currentGoal;
        if (g == null || g.description() == null || g.description().isBlank()) return query;
        String d = g.description()
                .replaceAll("(?i)^(STRATEGIC|TAKTISCH|OPERATIV|EXPEDITE):?\\s*", "").trim();
        String q = "";
        for (String part : d.split("[?.!]+")) {
            String cleaned = part.trim()
                    .replaceAll("(?i)\\b(was|wie|wer|wo|wieso|warum|welche[rns]?|ist|sind|bist|"
                            + "hat|haben|kannst|kann|beschreibe|erklaere|erklaerre|bestehen|"
                            + "aus|der|die|das|ein|eine|einem|einen|du)\\b", " ")
                    .replaceAll("\\s+", " ").trim();
            if (cleaned.length() >= 3) { q = cleaned; break; }
        }
        if (q.length() > 90) q = q.substring(0, 90).trim();
        return q.length() >= 3 ? q : query;
    }

    /** Zusatz-Fragen des Goals als Such-Stems (z.B. "Bestandteilen" -> "bestandte"). */
    private List<String> extraKeywords() {
        List<String> kws = new ArrayList<>();
        de.metis.kernel.goal.Goal g = currentGoal;
        if (g == null || g.description() == null) return kws;
        String[] parts = g.description().split("[?.!]+");
        for (int pi = 1; pi < parts.length && pi <= 3; pi++) {
            for (String w : parts[pi].toLowerCase().replaceAll("[^a-z\u00e4\u00f6\u00fc\u00df]+", " ").split(" ")) {
                if (w.length() >= 6 && !STOPWORDS.contains(w)) {
                    kws.add(w.length() > 9 ? w.substring(0, 9) : w);
                }
            }
        }
        return kws;
    }

    private static final List<String> STOPWORDS = List.of(
            "welche", "welchen", "welcher", "welches", "besteht", "bestehen",
            "beschreib", "erkläre", "erklaere", "funktion", "kurz");

    /** opensearch: bester Titel für eine Query (ohne Regex, reines Scannen). */
    private static String wikiSearchTitle(String q) throws Exception {
        String url = "https://de.wikipedia.org/w/api.php?action=opensearch&search="
                + URLEncoder.encode(q, StandardCharsets.UTF_8) + "&format=json&limit=1";
        var req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET()
                .header("User-Agent", "Metis AGI/0.11 (Java; websearch-action)").build();
        String body = HTTP.send(req, HttpResponse.BodyHandlers.ofString()).body();
        int marker = body.indexOf(",[\"");
        if (marker < 0) return null;
        int tStart = marker + 2;
        int tEnd = body.indexOf('"', tStart + 1);
        return tEnd > tStart ? body.substring(tStart + 1, tEnd) : null;
    }

    /** Volltext-Extrakt des Artikels (explaintext), JSON-Manuell unescaped. */
    private static String wikiFullExtract(String title) throws Exception {
        String url = "https://de.wikipedia.org/w/api.php?action=query&prop=extracts"
                + "&explaintext&format=json&redirects=1&titles="
                + URLEncoder.encode(title, StandardCharsets.UTF_8);
        var req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET()
                .header("User-Agent", "Metis AGI/0.11 (Java; websearch-action)").build();
        String body = HTTP.send(req, HttpResponse.BodyHandlers.ofString()).body();
        int eKey = body.indexOf("\"extract\"");
        if (eKey < 0) return null;
        int colon = body.indexOf(':', eKey);
        int q1 = body.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        StringBuilder sb = new StringBuilder();
        int i = q1 + 1;
        while (i < body.length()) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                char n = body.charAt(i + 1);
                if (n == 'n') sb.append('\n');
                else if (n == 't') sb.append('\t');
                else if (n == 'u' && i + 5 < body.length()) {
                    sb.append((char) Integer.parseInt(body.substring(i + 2, i + 6), 16));
                    i += 4;
                } else sb.append(n);
                i += 2;
                continue;
            }
            if (c == '"') break;
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

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
        final String query = effectiveQuery();
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

            // Phase 3: de.wikipedia Volltext (Fix 01.10.2026: das kurze
            // DDG-Abstract beantwortete Zusatzfragen wie "Bestandteile" nicht;
            // jetzt ganzer Artikel + passende Absaetze pro Zusatzfrage).
            List<String> kws = extraKeywords();
            if (results.isEmpty() || !kws.isEmpty()) {
                try {
                    String title = wikiSearchTitle(query);
                    if (title != null && !title.isBlank()) {
                        String full = wikiFullExtract(title);
                        if (full != null && full.length() > 80) {
                            StringBuilder ans = new StringBuilder();
                            String lead = full.length() > 900
                                    ? full.substring(0, 900).trim() : full.trim();
                            ans.append(lead);
                            int matched = 0;
                            for (String kw : kws) {
                                for (String para : full.split("\\n\\n")) {
                                    String pl = para.toLowerCase().replace('\u00a0', ' ');
                                    if (pl.contains(kw) && para.trim().length() > 40) {
                                        String pp = para.trim().replaceAll("\\\\s+", " ");
                                        ans.append("\\n\\n").append(pp.length() > 700
                                                ? pp.substring(0, 700).trim() + "\u2026" : pp);
                                        matched++;
                                        break;
                                    }
                                }
                            }
                            // Kein Absatz traf die Zusatzfrage -> ganzen Artikel
                            // (gekuerzt) liefern, damit Aufbau/Komponenten sichtbar sind.
                            if (matched == 0) {
                                String body = full.length() > 2600
                                        ? full.substring(0, 2600) + "\u2026" : full;
                                ans = new StringBuilder(body.replaceAll("\\n{2,}", "\n"));
                            }
                            String out = ans.length() > 2800
                                    ? ans.substring(0, 2800) + "\u2026" : ans.toString();
                            results.add(0, new SearchResult("Wikipedia (de): " + title,
                                    "https://de.wikipedia.org/wiki/" + title, out, true));
                            LOG.info(() -> "Wikipedia-Volltext traf: " + title + " ("
                                    + out.length() + " chars, " + kws.size() + " Zusatzfragen)");
                        }
                    }
                } catch (Exception e) {
                    LOG.fine("Wikipedia-Volltext failed: " + e.getMessage());
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
