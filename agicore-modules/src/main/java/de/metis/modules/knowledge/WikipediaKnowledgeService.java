package de.metis.modules.knowledge;

import de.metis.kernel.world.WorldModel;

import java.net.URI;
import java.net.http.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Proactive knowledge acquisition from German Wikipedia.
 * <p>
 * Fetches random articles via the Wikipedia API, extracts key facts
 * using an LLM, and stores them as beliefs in the WorldModel.
 * <p>
 * Designed to be called periodically by the CuriosityEngine.
 */
public class WikipediaKnowledgeService {

    private static final Logger LOG = Logger.getLogger(WikipediaKnowledgeService.class.getName());
    private static final String WIKI_API = "https://de.wikipedia.org/w/api.php";
    private static final Pattern CLEANUP = Pattern.compile("<[^>]+>|\\{\\{[^}]+\\}\\}|\\[\\[[^|\\]]+\\||\\[\\[|\\]\\]|''+|={2,}[^=]+={2,}");
    private static final Random RANDOM = new Random();

    private final String ollamaUrl;
    private final WorldModel worldModel;
    private final HttpClient http;
    private final Set<String> seenArticles = new HashSet<>();
    private int factsLearned = 0;

    /** Persistent JSON state path; null disables persistence. */
    private Path stateFile = null;
    /** Override via -Dmetis.wiki.knowledge.state=/path/to/state.json */
    private static final String DEFAULT_STATE =
            System.getProperty("metis.wiki.knowledge.state",
                    "/home/prometheus/metis/wiki-knowledge-state.json");

    /** Topics the CuriosityEngine found interesting (will be re-explored). */
    private final List<String> curiosityTopics = new ArrayList<>();

    public WikipediaKnowledgeService(String ollamaUrl, WorldModel worldModel) {
        this(ollamaUrl, worldModel, Path.of(DEFAULT_STATE));
    }

    public WikipediaKnowledgeService(String ollamaUrl, WorldModel worldModel, Path stateFile) {
        this.ollamaUrl = ollamaUrl;
        this.worldModel = worldModel;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.stateFile = stateFile;
        loadState();
    }

    /**
     * Load seenArticles + factsLearned from disk (idempotent, safe on missing file).
     * State file format is plain JSON:
     *   {"factsLearned": N, "seenArticles": ["title1","title2",...]}
     */
    private synchronized void loadState() {
        if (stateFile == null || !Files.exists(stateFile)) {
            LOG.fine("WikiKnowledge: no state file at " + stateFile + " - cold start");
            return;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(stateFile.toFile());
            if (root.has("factsLearned")) factsLearned = root.get("factsLearned").asInt(0);
            JsonNode arr = root.get("seenArticles");
            if (arr != null && arr.isArray()) {
                for (JsonNode n : arr) {
                    String t = n.asText("").trim();
                    if (!t.isEmpty()) seenArticles.add(t);
                }
            }
            LOG.info("WikiKnowledge: loaded state - " + seenArticles.size()
                    + " articles, " + factsLearned + " facts");
        } catch (Exception e) {
            LOG.warning("WikiKnowledge: state load failed (" + e.getMessage() + ") - cold start");
        }
    }

    /**
     * Atomic JSON write: tmp file + move-with-replace. Called after every learn cycle.
     */
    private synchronized void saveState() {
        if (stateFile == null) return;
        try {
            Files.createDirectories(stateFile.getParent());
            ObjectMapper mapper = new ObjectMapper();
            var root = mapper.createObjectNode();
            root.put("factsLearned", factsLearned);
            var arr = root.putArray("seenArticles");
            for (String t : seenArticles) arr.add(t);
            byte[] body = mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(root);
            Path tmp = stateFile.resolveSibling(stateFile.getFileName().toString() + ".tmp");
            Files.write(tmp, body, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, stateFile,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            LOG.warning("WikiKnowledge: state save failed - " + e.getMessage());
        }
    }


    /**
     * Let the CuriosityEngine suggest topics to explore.
     */
    public void addCuriosityTopics(List<String> topics) {
        this.curiosityTopics.addAll(topics);
    }

    /**
     * Main learning cycle: fetch article → extract facts → store beliefs.
     *
     * @return number of new beliefs stored, or -1 if nothing was learned
     */
    public int learnOneArticle() {
        try {
            // 1. Get an article
            Article article;
            if (!curiosityTopics.isEmpty() && RANDOM.nextDouble() < 0.4) {
                // 40% chance: explore a curiosity topic
                String topic = curiosityTopics.remove(RANDOM.nextInt(curiosityTopics.size()));
                article = fetchArticle(topic);
                if (article == null) article = fetchRandomArticle();
            } else {
                article = fetchRandomArticle();
            }
            if (article == null) return -1;

            // 2. Extract key facts via LLM
            List<String> facts = extractFacts(article);
            if (facts.isEmpty()) {
                seenArticles.add(article.title);
                saveState();
                return 0;
            }

            // 3. Store as beliefs
            int stored = 0;
            for (String fact : facts) {
                if (fact.length() < 20 || fact.length() > 500) continue;
                double confidence = 0.7 + (RANDOM.nextDouble() * 0.15); // 0.70-0.85
                worldModel.update(fact, confidence,
                        "wikipedia:" + article.title, true);
                stored++;
            }

            seenArticles.add(article.title);
            factsLearned += stored;
            saveState();
            LOG.info("Wikipedia learned " + stored + " facts from '" + article.title
                    + "' (total: " + factsLearned + " facts from " + seenArticles.size() + " articles)");

            return stored;

        } catch (Exception e) {
            LOG.warning("Wikipedia learning failed: " + e.getMessage());
            return -1;
        }
    }

    // ── Article fetching ───────────────────────────────────────────

    /**
     * Fetch a random German Wikipedia article.
     */
    public Article fetchRandomArticle() throws Exception {
        String url = WIKI_API + "?action=query&list=random&rnnamespace=0&rnlimit=1&format=json";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        // Extract title from JSON
        String title = extractJsonStr(resp.body(), "title");
        if (title == null || title.isBlank()) return null;
        return fetchArticle(title);
    }

    /**
     * Fetch a specific article by title.
     */
    public Article fetchArticle(String title) throws Exception {
        if (seenArticles.contains(title)) return null;

        String encoded = URLEncoder.encode(title, StandardCharsets.UTF_8);
        String url = WIKI_API
                + "?action=query&prop=extracts&exintro=1&explaintext=1"
                + "&titles=" + encoded + "&format=json";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        // Extract page content from JSON
        String extract = extractJsonStr(resp.body(), "extract");
        if (extract == null || extract.length() < 100) return null;

        // Clean up wiki markup remnants
        String cleaned = CLEANUP.matcher(extract).replaceAll(" ");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();

        return new Article(title, cleaned);
    }

    // ── Fact extraction via LLM ─────────────────────────────────────

    /**
     * Use a lightweight LLM to extract 3-5 key facts from an article.
     */
    private List<String> extractFacts(Article article) throws Exception {
        // Truncate to avoid token waste
        String excerpt = article.text.length() > 2000
                ? article.text.substring(0, 2000) + "..."
                : article.text;

        String prompt = "Extract 3-5 key facts from this German Wikipedia article. "
                + "Output each fact as ONE concise sentence. No explanations, no bullet points, "
                + "just one fact per line.\n\n"
                + "Article: " + article.title + "\n"
                + excerpt + "\n\n"
                + "Facts:";

        String jsonBody = String.format("""
                {"model":"granite4.1:3b","messages":[
                  {"role":"user","content":%s}
                ],"stream":false,
                 "options":{"temperature":0.2,"num_predict":256},
                 "keep_alive":0}
                """, escapeJson(prompt));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(ollamaUrl + "/api/chat"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return List.of();

        String content = extractJsonStr(resp.body(), "content");
        if (content == null || content.isBlank()) return List.of();

        return Arrays.stream(content.split("\n"))
                .map(String::strip)
                .filter(s -> s.length() > 20)
                .filter(s -> !s.startsWith("Here") && !s.startsWith("Sure") && !s.startsWith("The"))
                .collect(Collectors.toList());
    }

    // ── Stats ──────────────────────────────────────────────────────

    public int factsLearned() { return factsLearned; }
    public int articlesProcessed() { return seenArticles.size(); }

    // ── Helpers ────────────────────────────────────────────────────

    private static String extractJsonStr(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) {
            search = "\"" + key + "\": \"";
            start = json.indexOf(search);
        }
        if (start < 0) return null;
        start += search.length();

        StringBuilder val = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case 'n' -> { val.append('\n'); i++; }
                    case 't' -> { val.append('\t'); i++; }
                    case '"' -> { val.append('"'); i++; }
                    case '\\' -> { val.append('\\'); i++; }
                    case 'u' -> {
                        if (i + 5 < json.length()) {
                            String hex = json.substring(i + 2, i + 6);
                            val.append((char) Integer.parseInt(hex, 16));
                            i += 5;
                        } else val.append(c);
                    }
                    default -> val.append(c);
                }
            } else if (c == '"') {
                break;
            } else {
                val.append(c);
            }
        }
        return val.toString().replace("\\n", "\n").trim();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    // ── Record ─────────────────────────────────────────────────────

    public record Article(String title, String text) {}
}
