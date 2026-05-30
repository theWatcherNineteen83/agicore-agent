package de.metis.kernel.self;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Append-only Episoden-Speicher (Phase 8.1 — Narratives Selbstmodell).
 *
 * <p>Speichert {@link Episode} als JSONL unter
 * {@code metis.episodes.path} (default: {@code /home/prometheus/metis/episodes.jsonl}),
 * jeweils eine Episode pro Zeile. Schwache Hash-Chain über alle Episoden:
 * {@code hash_i = sha256(prevHash | id | start | end | title | body)}.
 *
 * <p>Bewusst <em>nicht</em> in SQLite: Markdown-body wird auch direkt vom Watchdog
 * lesbar sein (read-only zone), ohne JDBC-Lock-Konkurrenz mit dem Hauptservice.
 * Append-Only-Datei + Hash-Chain ist der Watchdog-Stil.
 *
 * <p>Diese Klasse ist Teil des immutable Kernels: Metis selbst kann sie
 * mutieren, aber jede Mutation gilt als sensibler Pfad (Phase 7 Tripwire).
 */
public class EpisodicMemory {

    private static final Logger LOG = Logger.getLogger(EpisodicMemory.class.getName());
    private static final String DEFAULT_PATH =
            System.getProperty("metis.episodes.path",
                    "/home/prometheus/metis/episodes.jsonl");

    private final Path file;
    private final ObjectMapper mapper;
    private final List<Episode> cache = new ArrayList<>();
    private String chainHead = "GENESIS";

    public EpisodicMemory() {
        this(Path.of(DEFAULT_PATH));
    }

    public EpisodicMemory(Path file) {
        this.file = file;
        this.mapper = new ObjectMapper();
        load();
    }

    private synchronized void load() {
        if (!Files.exists(file)) {
            LOG.info("EpisodicMemory: cold start (" + file + ")");
            return;
        }
        try {
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank()) continue;
                Episode ep = parseEpisode(line);
                if (ep != null) {
                    cache.add(ep);
                    chainHead = ep.hash();
                }
            }
            LOG.info("EpisodicMemory: loaded " + cache.size()
                    + " episodes, chain head " + chainHead.substring(0, Math.min(12, chainHead.length())) + "...");
        } catch (Exception e) {
            LOG.warning("EpisodicMemory: load failed " + e.getMessage());
        }
    }

    /**
     * Append a new episode. The hash chain is computed and the previousHash field
     * on the supplied episode is ignored; the writer wins.
     *
     * @return the actually persisted episode (with prevHash + hash set)
     */
    public synchronized Episode append(Episode candidate) {
        String prev = chainHead;
        String payload = prev + "|" + candidate.id() + "|" + candidate.start()
                + "|" + candidate.end() + "|" + candidate.title() + "|" + candidate.body();
        String h = sha256(payload);
        Episode finalEp = new Episode(
                candidate.id(), candidate.start(), candidate.end(),
                candidate.title(), candidate.body(),
                candidate.events(), candidate.insights(), candidate.openQuestions(),
                candidate.people(), candidate.moodAtClose(),
                candidate.ticksCovered(), candidate.beliefsLearned(),
                candidate.goalsCompleted(), candidate.goalsFailed(),
                prev, h
        );
        try {
            Files.createDirectories(file.getParent());
            String line = mapper.writeValueAsString(toNode(finalEp));
            Files.writeString(file, line + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            cache.add(finalEp);
            chainHead = h;
            LOG.info("EpisodicMemory: appended '" + finalEp.title()
                    + "' (hash " + h.substring(0, 12) + "...)");
            return finalEp;
        } catch (IOException e) {
            LOG.warning("EpisodicMemory: append failed " + e.getMessage());
            return null;
        }
    }

    public synchronized List<Episode> recent(int n) {
        if (cache.isEmpty()) return List.of();
        int from = Math.max(0, cache.size() - n);
        return List.copyOf(cache.subList(from, cache.size()));
    }

    public synchronized int size() { return cache.size(); }
    public synchronized String chainHead() { return chainHead; }
    public Path file() { return file; }

    /** Verify the integrity of the JSONL chain on disk. */
    public synchronized boolean verify() {
        String expectedPrev = "GENESIS";
        for (Episode ep : cache) {
            if (!ep.previousHash().equals(expectedPrev)) {
                LOG.warning("EpisodicMemory: chain break at " + ep.id());
                return false;
            }
            String payload = ep.previousHash() + "|" + ep.id() + "|" + ep.start()
                    + "|" + ep.end() + "|" + ep.title() + "|" + ep.body();
            if (!sha256(payload).equals(ep.hash())) {
                LOG.warning("EpisodicMemory: tamper at " + ep.id());
                return false;
            }
            expectedPrev = ep.hash();
        }
        return true;
    }

    // ── JSON helpers ─────────────────────────────────────────────────

    private ObjectNode toNode(Episode ep) {
        ObjectNode n = mapper.createObjectNode();
        n.put("id", ep.id());
        n.put("start", ep.start().toString());
        n.put("end", ep.end().toString());
        n.put("title", ep.title());
        n.put("body", ep.body());
        ArrayNode ev = n.putArray("events");
        ep.events().forEach(ev::add);
        ArrayNode ins = n.putArray("insights");
        ep.insights().forEach(ins::add);
        ArrayNode oq = n.putArray("openQuestions");
        ep.openQuestions().forEach(oq::add);
        ArrayNode pp = n.putArray("people");
        ep.people().forEach(pp::add);
        ObjectNode mood = n.putObject("moodAtClose");
        ep.moodAtClose().forEach(mood::put);
        n.put("ticksCovered", ep.ticksCovered());
        n.put("beliefsLearned", ep.beliefsLearned());
        n.put("goalsCompleted", ep.goalsCompleted());
        n.put("goalsFailed", ep.goalsFailed());
        n.put("previousHash", ep.previousHash());
        n.put("hash", ep.hash());
        return n;
    }

    private Episode parseEpisode(String json) {
        try {
            JsonNode n = mapper.readTree(json);
            List<String> events = new ArrayList<>();
            n.path("events").forEach(j -> events.add(j.asText()));
            List<String> insights = new ArrayList<>();
            n.path("insights").forEach(j -> insights.add(j.asText()));
            List<String> openQ = new ArrayList<>();
            n.path("openQuestions").forEach(j -> openQ.add(j.asText()));
            List<String> people = new ArrayList<>();
            n.path("people").forEach(j -> people.add(j.asText()));
            Map<String, Double> mood = new LinkedHashMap<>();
            n.path("moodAtClose").fields()
                    .forEachRemaining(e -> mood.put(e.getKey(), e.getValue().asDouble()));
            return new Episode(
                    n.path("id").asText(),
                    Instant.parse(n.path("start").asText()),
                    Instant.parse(n.path("end").asText()),
                    n.path("title").asText(""),
                    n.path("body").asText(""),
                    events, insights, openQ, people, mood,
                    n.path("ticksCovered").asLong(),
                    n.path("beliefsLearned").asInt(),
                    n.path("goalsCompleted").asInt(),
                    n.path("goalsFailed").asInt(),
                    n.path("previousHash").asText("GENESIS"),
                    n.path("hash").asText("")
            );
        } catch (Exception e) {
            LOG.warning("EpisodicMemory: parse failed: " + e.getMessage());
            return null;
        }
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return "ERR";
        }
    }
}
