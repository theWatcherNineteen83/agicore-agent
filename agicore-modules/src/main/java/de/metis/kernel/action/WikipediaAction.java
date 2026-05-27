package de.metis.kernel.action;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Reads Wikipedia articles from a local directory of extracted text files.
 * Allows Metis to self-serve knowledge from Wikipedia during idle time.
 * <p>
 * Category: read (only retrieves existing knowledge)
 * Requires human approval: no
 * <p>
 * Evolvable: Metis can optimize article selection, add index/search, or
 * switch to direct XML dump streaming for on-demand access.
 */
public class WikipediaAction implements Action {

    private static final Logger LOG = Logger.getLogger(WikipediaAction.class.getName());
    public static final String NAME = "wikipedia";

    private final String topic;   // article title or keyword to search
    private final String mode;    // "first", "random", "search"
    private final Path articleDir;

    public WikipediaAction(String topic) {
        this(topic, "search", Path.of("/data/prometheus/wiki_de"));
    }

    public WikipediaAction(String topic, String mode, Path articleDir) {
        this.topic = topic;
        this.mode = mode;
        this.articleDir = articleDir;
    }

    /**
     * Get a random article — for idle learning.
     */
    public static WikipediaAction random(Path articleDir) {
        return new WikipediaAction("", "random", articleDir);
    }

    @Override public String name() { return NAME; }
    @Override public String category() { return "read"; }
    @Override public boolean requiresApproval() { return false; }

    @Override
    public ActionResult execute() {
        var now = java.time.Instant.now();
        try {
            File[] files = articleDir.toFile().listFiles((d, n) -> n.endsWith(".txt"));
            if (files == null || files.length == 0) {
                return ActionResult.fail(NAME, "No Wikipedia articles in " + articleDir, now);
            }

            File chosen;
            switch (mode) {
                case "random":
                    chosen = files[new java.util.Random().nextInt(files.length)];
                    break;
                case "first":
                    Arrays.sort(files, Comparator.comparing(File::getName));
                    chosen = files[0];
                    break;
                case "search":
                default:
                    chosen = searchArticle(files, topic);
                    break;
            }

            String text = Files.readString(chosen.toPath(), StandardCharsets.UTF_8);
            String title = chosen.getName().replace(".txt", "").replace('_', ' ');

            // Extract key facts (first 2 paragraphs, ~1000 chars)
            String summary = extractSummary(text);

            LOG.info(() -> "Wikipedia '" + title + "': " + text.length() + " chars, summary " +
                    summary.length() + " chars");

            return ActionResult.ok(NAME,
                    "Wikipedia: " + title + " (" + text.length() + " chars)\n" +
                    "Summary: " + summary, now);

        } catch (IOException e) {
            return ActionResult.fail(NAME, "Wikipedia read error: " + e.getMessage(), now);
        }
    }

    private File searchArticle(File[] files, String query) {
        String q = query.toLowerCase();
        // Exact match first
        for (File f : files) {
            if (f.getName().toLowerCase().contains(q)) return f;
        }
        // Partial match
        for (File f : files) {
            String name = f.getName().toLowerCase().replace('_', ' ').replace(".txt", "");
            String[] words = q.split("\\s+");
            int matches = 0;
            for (String w : words) {
                if (name.contains(w)) matches++;
            }
            if (matches >= words.length / 2) return f;
        }
        // Fallback: random
        return files[new java.util.Random().nextInt(files.length)];
    }

    private String extractSummary(String text) {
        // Get first 2 non-empty paragraphs
        String[] paragraphs = text.split("\n\n");
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String p : paragraphs) {
            String clean = p.trim().replaceAll("\\s+", " ");
            if (clean.length() > 30 && !clean.startsWith("Datei:") && !clean.startsWith("Kategorie:")) {
                sb.append(clean);
                count++;
                if (count >= 2) break;
                sb.append("\n");
            }
        }
        String result = sb.toString();
        return result.length() > 1500 ? result.substring(0, 1500) + "…" : result;
    }
}
