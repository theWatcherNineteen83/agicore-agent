package de.metis.kernel.person;

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
 * Phase 11.3 — gemeinsame Episoden pro Person.
 *
 * <p>Speichert Markdown-Notizen über "was war zwischen mir und Person X
 * wichtig". Append-only JSONL mit Feldern (personId, title, body, when).
 *
 * <p>Wird vom SystemPromptBuilder genutzt: Metis hat dann den Kontext
 * "Mit Georg habe ich gestern XYZ besprochen".
 */
public class RelationshipMemory {

    private static final Logger LOG = Logger.getLogger(RelationshipMemory.class.getName());
    private static final String DEFAULT_PATH =
            System.getProperty("metis.relationship.path",
                    "/home/prometheus/metis/relationship-memory.jsonl");

    public record SharedNote(String personId, String title, String body, Instant when) {}

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<SharedNote> notes = new ArrayList<>();

    public RelationshipMemory() { this(Path.of(DEFAULT_PATH)); }

    public RelationshipMemory(Path file) {
        this.file = file;
        load();
    }

    private synchronized void load() {
        if (!Files.exists(file)) {
            LOG.info("RelationshipMemory: cold start (" + file + ")");
            return;
        }
        try {
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank()) continue;
                SharedNote n = parse(line);
                if (n != null) notes.add(n);
            }
            LOG.info("RelationshipMemory: loaded " + notes.size() + " shared notes");
        } catch (Exception e) {
            LOG.warning("RelationshipMemory load failed: " + e.getMessage());
        }
    }

    public synchronized SharedNote append(String personId, String title, String body) {
        if (personId == null || personId.isBlank()) return null;
        SharedNote n = new SharedNote(personId,
                title == null ? "(no title)" : title,
                body == null ? "" : body,
                Instant.now());
        notes.add(n);
        try {
            Files.createDirectories(file.getParent());
            String line = mapper.writeValueAsString(toNode(n));
            Files.writeString(file, line + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warning("RelationshipMemory append failed: " + e.getMessage());
        }
        return n;
    }

    public synchronized List<SharedNote> recentFor(String personId, int max) {
        List<SharedNote> out = new ArrayList<>();
        for (int i = notes.size() - 1; i >= 0 && out.size() < max; i--) {
            if (Objects.equals(notes.get(i).personId(), personId)) out.add(notes.get(i));
        }
        Collections.reverse(out);
        return out;
    }

    public synchronized int size() { return notes.size(); }

    private ObjectNode toNode(SharedNote n) {
        ObjectNode o = mapper.createObjectNode();
        o.put("personId", n.personId());
        o.put("title", n.title());
        o.put("body", n.body());
        o.put("when", n.when().toString());
        return o;
    }

    private SharedNote parse(String json) {
        try {
            JsonNode n = mapper.readTree(json);
            return new SharedNote(
                    n.path("personId").asText(""),
                    n.path("title").asText(""),
                    n.path("body").asText(""),
                    n.hasNonNull("when") ? Instant.parse(n.get("when").asText()) : Instant.now()
            );
        } catch (Exception e) { return null; }
    }
}
