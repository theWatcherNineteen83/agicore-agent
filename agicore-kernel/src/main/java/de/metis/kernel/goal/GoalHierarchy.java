package de.metis.kernel.goal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Phase 9.2 — Speicher + Graph für {@link LongHorizonGoal}.
 *
 * <p>JSONL-Persistenz unter {@code metis.hierarchy.path}
 * (default: {@code /home/prometheus/metis/goal-hierarchy.jsonl}).
 *
 * <p>Append-only auf Disk, in-Memory zusätzlich indiziert nach
 * id, parent, horizon, status. Operationen synchronized;
 * Goal-Erstellung läuft selten genug, dass das billig ist.
 *
 * <p>Dependency-Resolver inline: ein Ziel ist „runnable", wenn alle
 * Vorbedingungen (Parent-Goals niedrigerer Priorität oder explizite
 * Dependencies) DONE sind. Im MVP nutzen wir Parent-Child als
 * impliziten Vorrang („alle Schwester-Goals des Parent müssen
 * begonnen sein, bevor Folgegoal startet" — wird vom Planner
 * berücksichtigt, nicht hier erzwungen).
 */
public class GoalHierarchy {

    private static final Logger LOG = Logger.getLogger(GoalHierarchy.class.getName());
    private static final String DEFAULT_PATH =
            System.getProperty("metis.hierarchy.path",
                    "/home/prometheus/metis/goal-hierarchy.jsonl");

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<UUID, LongHorizonGoal> byId = new LinkedHashMap<>();

    public GoalHierarchy() { this(Path.of(DEFAULT_PATH)); }

    public GoalHierarchy(Path file) {
        this.file = file;
        load();
    }

    private synchronized void load() {
        if (!Files.exists(file)) {
            LOG.info("GoalHierarchy: cold start (" + file + ")");
            return;
        }
        try {
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank()) continue;
                LongHorizonGoal g = parse(line);
                if (g != null) byId.put(g.id(), g);
            }
            LOG.info("GoalHierarchy: loaded " + byId.size() + " goals");
        } catch (Exception e) {
            LOG.warning("GoalHierarchy: load failed " + e.getMessage());
        }
    }

    /**
     * Append-or-update. The full goal record is appended as a new JSONL line;
     * the latest line wins when reloading. Compaction can be added later.
     */
    public synchronized LongHorizonGoal upsert(LongHorizonGoal g) {
        if (g == null) return null;
        byId.put(g.id(), g);
        try {
            Files.createDirectories(file.getParent());
            String line = mapper.writeValueAsString(toNode(g));
            Files.writeString(file, line + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warning("GoalHierarchy: append failed " + e.getMessage());
        }
        return g;
    }

    public synchronized Optional<LongHorizonGoal> get(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    public synchronized List<LongHorizonGoal> all() {
        return List.copyOf(byId.values());
    }

    public synchronized List<LongHorizonGoal> openByHorizon(GoalHorizon h) {
        return byId.values().stream()
                .filter(g -> g.horizon() == h && g.isOpen())
                .sorted(Comparator.comparingInt(LongHorizonGoal::priority).reversed())
                .collect(Collectors.toList());
    }

    public synchronized List<LongHorizonGoal> overdue() {
        return byId.values().stream()
                .filter(LongHorizonGoal::isOverdue)
                .collect(Collectors.toList());
    }

    public synchronized List<LongHorizonGoal> children(UUID parentId) {
        return byId.values().stream()
                .filter(g -> parentId.equals(g.parentId()))
                .collect(Collectors.toList());
    }

    /**
     * Returns {@code true} if all dependencies (parent) are DONE OR there is
     * no parent. Used by HorizonPlanner to decide whether children of a
     * strategic goal may already start.
     */
    public synchronized boolean isRunnable(UUID id) {
        LongHorizonGoal g = byId.get(id);
        if (g == null) return false;
        if (g.parentId() == null) return true;
        LongHorizonGoal parent = byId.get(g.parentId());
        return parent != null && (parent.status() == LongHorizonGoal.Status.ACTIVE
                                  || parent.status() == LongHorizonGoal.Status.DONE);
    }

    /**
     * Recompute parent.progress as the mean of children.progress.
     * Idempotent and cheap; can be called on every tick.
     */
    /**
     * Propagate child progress upward and cascade to grandparent.
     * If a goal becomes DONE, also rolls up its parent (recursive cascade).
     */
    public synchronized void rollupProgress(UUID parentId) {
        LongHorizonGoal parent = byId.get(parentId);
        if (parent == null) return;
        List<LongHorizonGoal> kids = children(parentId);
        if (kids.isEmpty()) return;
        double mean = kids.stream().mapToDouble(LongHorizonGoal::progress).average().orElse(0.0);
        boolean changed = false;
        if (Math.abs(parent.progress() - mean) > 0.005) {
            upsert(parent.withProgress(mean));
            changed = true;
        }
        boolean allDone = kids.stream()
                .allMatch(k -> k.status() == LongHorizonGoal.Status.DONE);
        if (allDone && parent.status() != LongHorizonGoal.Status.DONE) {
            upsert(parent.withStatus(LongHorizonGoal.Status.DONE));
            changed = true;
        }
        // Cascade upward: if this parent changed, update its parent too
        if (changed && parent.parentId() != null) {
            rollupProgress(parent.parentId());
        }
    }

    public synchronized int size() { return byId.size(); }
    public Path file() { return file; }

    // ── JSON ──

    private ObjectNode toNode(LongHorizonGoal g) {
        ObjectNode n = mapper.createObjectNode();
        n.put("id", g.id().toString());
        n.put("title", g.title());
        n.put("rationale", g.rationale());
        n.put("horizon", g.horizon().name());
        n.put("status", g.status().name());
        if (g.parentId() != null) n.put("parentId", g.parentId().toString());
        ArrayNode kids = n.putArray("childIds");
        g.childIds().forEach(c -> kids.add(c.toString()));
        n.put("createdAt", g.createdAt().toString());
        if (g.deadline() != null) n.put("deadline", g.deadline().toString());
        if (g.lastReviewed() != null) n.put("lastReviewed", g.lastReviewed().toString());
        if (g.completedAt() != null) n.put("completedAt", g.completedAt().toString());
        n.put("progress", g.progress());
        n.put("priority", g.priority());
        n.put("owner", g.owner());
        ArrayNode tg = n.putArray("tags");
        g.tags().forEach(tg::add);
        return n;
    }

    private LongHorizonGoal parse(String json) {
        try {
            JsonNode n = mapper.readTree(json);
            List<UUID> kids = new ArrayList<>();
            n.path("childIds").forEach(j -> kids.add(UUID.fromString(j.asText())));
            List<String> tags = new ArrayList<>();
            n.path("tags").forEach(j -> tags.add(j.asText()));
            return new LongHorizonGoal(
                    UUID.fromString(n.path("id").asText()),
                    n.path("title").asText(""),
                    n.path("rationale").asText(""),
                    GoalHorizon.valueOf(n.path("horizon").asText("OPERATIONAL")),
                    LongHorizonGoal.Status.valueOf(n.path("status").asText("PROPOSED")),
                    n.hasNonNull("parentId") ? UUID.fromString(n.get("parentId").asText()) : null,
                    kids,
                    n.hasNonNull("createdAt") ? Instant.parse(n.get("createdAt").asText()) : Instant.now(),
                    n.hasNonNull("deadline") ? Instant.parse(n.get("deadline").asText()) : null,
                    n.hasNonNull("lastReviewed") ? Instant.parse(n.get("lastReviewed").asText()) : null,
                    n.hasNonNull("completedAt") ? Instant.parse(n.get("completedAt").asText()) : null,
                    n.path("progress").asDouble(0.0),
                    n.path("priority").asInt(50),
                    n.path("owner").asText("metis"),
                    tags
            );
        } catch (Exception e) {
            LOG.warning("GoalHierarchy: parse failed " + e.getMessage());
            return null;
        }
    }
}
