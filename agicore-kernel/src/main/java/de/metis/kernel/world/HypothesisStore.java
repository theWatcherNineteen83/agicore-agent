package de.metis.kernel.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Phase 10 — persistente Sammlung aller {@link CausalHypothesis}.
 *
 * <p>Append-only JSONL unter {@code metis.hypotheses.path} (default
 * {@code /home/prometheus/metis/hypotheses.jsonl}). Index nach Status, in-Memory.
 */
public class HypothesisStore {

    private static final Logger LOG = Logger.getLogger(HypothesisStore.class.getName());
    private static final String DEFAULT_PATH =
            System.getProperty("metis.hypotheses.path",
                    "/home/prometheus/metis/hypotheses.jsonl");

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<UUID, CausalHypothesis> byId = new LinkedHashMap<>();

    public HypothesisStore() { this(Path.of(DEFAULT_PATH)); }

    public HypothesisStore(Path file) {
        this.file = file;
        load();
    }

    private synchronized void load() {
        if (!Files.exists(file)) {
            LOG.info("HypothesisStore: cold start (" + file + ")");
            return;
        }
        try {
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank()) continue;
                CausalHypothesis h = parse(line);
                if (h != null) byId.put(h.id(), h);
            }
            LOG.info("HypothesisStore: loaded " + byId.size() + " hypotheses");
        } catch (Exception e) {
            LOG.warning("HypothesisStore: load failed " + e.getMessage());
        }
    }

    public synchronized CausalHypothesis upsert(CausalHypothesis h) {
        if (h == null) return null;
        byId.put(h.id(), h);
        try {
            Files.createDirectories(file.getParent());
            String line = mapper.writeValueAsString(toNode(h));
            Files.writeString(file, line + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warning("HypothesisStore: append failed " + e.getMessage());
        }
        return h;
    }

    public synchronized List<CausalHypothesis> open() {
        return byId.values().stream()
                .filter(CausalHypothesis::isOpen)
                .collect(Collectors.toList());
    }

    public synchronized List<CausalHypothesis> all() {
        return List.copyOf(byId.values());
    }

    public synchronized Optional<CausalHypothesis> get(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    public synchronized int size() { return byId.size(); }

    public synchronized int confirmedCount() {
        return (int) byId.values().stream()
                .filter(h -> h.status() == CausalHypothesis.Status.CONFIRMED).count();
    }

    public synchronized int refutedCount() {
        return (int) byId.values().stream()
                .filter(h -> h.status() == CausalHypothesis.Status.REFUTED).count();
    }

    private ObjectNode toNode(CausalHypothesis h) {
        ObjectNode n = mapper.createObjectNode();
        n.put("id", h.id().toString());
        n.put("cause", h.cause());
        n.put("condition", h.condition());
        n.put("effect", h.effect());
        n.put("predictedDirection", h.predictedDirection().name());
        n.put("predictedMagnitude", h.predictedMagnitude());
        n.put("rationale", h.rationale());
        n.put("plannedAction", h.plannedAction());
        n.put("status", h.status().name());
        n.put("createdAt", h.createdAt().toString());
        if (h.testedAt() != null) n.put("testedAt", h.testedAt().toString());
        if (h.observedDirection() != null) n.put("observedDirection", h.observedDirection().name());
        n.put("observedMagnitude", h.observedMagnitude());
        n.put("resultNote", h.resultNote());
        return n;
    }

    private CausalHypothesis parse(String json) {
        try {
            JsonNode n = mapper.readTree(json);
            return new CausalHypothesis(
                    UUID.fromString(n.path("id").asText()),
                    n.path("cause").asText(""),
                    n.path("condition").asText(""),
                    n.path("effect").asText(""),
                    CausalHypothesis.Direction.valueOf(n.path("predictedDirection").asText("UP")),
                    n.path("predictedMagnitude").asDouble(0.5),
                    n.path("rationale").asText(""),
                    n.path("plannedAction").asText(""),
                    CausalHypothesis.Status.valueOf(n.path("status").asText("PROPOSED")),
                    n.hasNonNull("createdAt") ? Instant.parse(n.get("createdAt").asText()) : Instant.now(),
                    n.hasNonNull("testedAt") ? Instant.parse(n.get("testedAt").asText()) : null,
                    n.hasNonNull("observedDirection") ? CausalHypothesis.Direction.valueOf(n.get("observedDirection").asText()) : null,
                    n.path("observedMagnitude").asDouble(0.0),
                    n.path("resultNote").asText("")
            );
        } catch (Exception e) {
            LOG.warning("HypothesisStore parse failed: " + e.getMessage());
            return null;
        }
    }
}
