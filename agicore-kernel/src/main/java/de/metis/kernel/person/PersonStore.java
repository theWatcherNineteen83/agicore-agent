package de.metis.kernel.person;

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
 * Phase 11.1/11.2 — persistenter Speicher für {@link Person}.
 *
 * <p>JSONL-Format unter {@code metis.people.path} (default
 * {@code /home/prometheus/metis/people.jsonl}). Latest-line-wins beim Reload.
 * In-Memory-Index by id.
 *
 * <p>Bootstrap: Georg wird beim ersten Start automatisch als {@code OWNER}
 * angelegt, abgeleitet aus PersonalityAnchor + bekannten Telegram-Ids.
 */
public class PersonStore {

    private static final Logger LOG = Logger.getLogger(PersonStore.class.getName());
    private static final String DEFAULT_PATH =
            System.getProperty("metis.people.path",
                    "/home/prometheus/metis/people.jsonl");

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Person> byId = new LinkedHashMap<>();

    public PersonStore() { this(Path.of(DEFAULT_PATH)); }

    public PersonStore(Path file) {
        this.file = file;
        load();
    }

    private synchronized void load() {
        if (!Files.exists(file)) {
            LOG.info("PersonStore: cold start (" + file + ")");
            return;
        }
        try {
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank()) continue;
                Person p = parse(line);
                if (p != null) byId.put(p.id(), p);
            }
            LOG.info("PersonStore: loaded " + byId.size() + " people");
        } catch (Exception e) {
            LOG.warning("PersonStore: load failed " + e.getMessage());
        }
    }

    public synchronized Person upsert(Person p) {
        if (p == null) return null;
        byId.put(p.id(), p);
        try {
            Files.createDirectories(file.getParent());
            String line = mapper.writeValueAsString(toNode(p));
            Files.writeString(file, line + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warning("PersonStore: append failed " + e.getMessage());
        }
        return p;
    }

    public synchronized Optional<Person> get(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public synchronized List<Person> all() { return List.copyOf(byId.values()); }
    public synchronized int size() { return byId.size(); }

    public synchronized List<Person> trustedAtLeast(TrustLevel min) {
        return byId.values().stream()
                .filter(p -> p.trustLevel().atLeast(min))
                .collect(Collectors.toList());
    }

    /**
     * Phase 11.2 — Automatische TrustLevel-Evaluation.
     *
     * <p>Regeln (nicht-OWNER-Personen):
     * <ul>
     *   <li>STRANGER mit ≥5 Interaktionen → GUEST (erste Hürde)</li>
     *   <li>GUEST mit ≥25 Interaktionen → KNOWN (wiederkehrender Nutzer)</li>
     *   <li>KNOWN mit ≥50 Interaktionen + ≥7 Tage seit firstSeen → TRUSTED</li>
     *   <li>OWNER wird NIE automatisch hoch- oder runtergestuft</li>
     * </ul>
     *
     * @return der (ggf. neue) TrustLevel
     */
    public synchronized TrustLevel evaluateTrustLevel(Person p) {
        if (p == null || p.trustLevel() == TrustLevel.OWNER) {
            return p != null ? p.trustLevel() : TrustLevel.STRANGER;
        }
        long interactions = p.interactionCount();
        TrustLevel current = p.trustLevel();
        long daysSinceFirst = java.time.Duration.between(
                p.firstSeenAt(), java.time.Instant.now()).toDays();

        TrustLevel proposed = current;
        if (current.rank() < TrustLevel.GUEST.rank() && interactions >= 5) {
            proposed = TrustLevel.GUEST;
        }
        if (current.rank() < TrustLevel.KNOWN.rank() && interactions >= 25) {
            proposed = TrustLevel.KNOWN;
        }
        if (current.rank() < TrustLevel.TRUSTED.rank()
                && interactions >= 50 && daysSinceFirst >= 7) {
            proposed = TrustLevel.TRUSTED;
        }
        return proposed;
    }

    /**
     * Phase 11.2 — Person nach Interaktion aktualisieren (inkl. Trust-Automation).
     * Convenience-Methode: withInteraction() + evaluateTrustLevel() + upsert().
     */
    public synchronized Person recordInteraction(String personId) {
        return get(personId).map(p -> {
            Person updated = p.withInteraction();
            TrustLevel newTrust = evaluateTrustLevel(updated);
            if (newTrust != updated.trustLevel()) {
                updated = updated.withTrust(newTrust);
                LOG.info("PersonStore: " + updated.name() + " (" + personId
                        + ") upgraded " + p.trustLevel() + " → " + newTrust
                        + " (" + updated.interactionCount() + " interactions, "
                        + java.time.Duration.between(updated.firstSeenAt(),
                            java.time.Instant.now()).toDays() + " days)");
            }
            return upsert(updated);
        }).orElse(null);
    }

    /**
     * Bootstrap Georg (Owner) wenn noch nicht vorhanden.
     */
    public synchronized Person ensureOwner(String id, String name) {
        return get(id).orElseGet(() -> {
            Person g = new Person(
                    id, name,
                    java.util.List.of("owner", "creator"),
                    TrustLevel.OWNER,
                    java.util.Map.of("language", "Deutsch", "style", "direct"),
                    java.util.List.of(),
                    java.util.List.of("Erbauer von Metis"),
                    Instant.now(), null, 0, java.util.List.of()
            );
            return upsert(g);
        });
    }

    private ObjectNode toNode(Person p) {
        ObjectNode n = mapper.createObjectNode();
        n.put("id", p.id());
        n.put("name", p.name());
        ArrayNode roles = n.putArray("roles");
        p.roles().forEach(roles::add);
        n.put("trustLevel", p.trustLevel().name());
        ObjectNode prefs = n.putObject("preferences");
        p.preferences().forEach(prefs::put);
        ArrayNode bt = n.putArray("bannedTopics");
        p.bannedTopics().forEach(bt::add);
        ArrayNode kf = n.putArray("knownFacts");
        p.knownFacts().forEach(kf::add);
        n.put("firstSeenAt", p.firstSeenAt().toString());
        if (p.lastSeenAt() != null) n.put("lastSeenAt", p.lastSeenAt().toString());
        n.put("interactionCount", p.interactionCount());
        ArrayNode sh = n.putArray("sentimentHistory");
        for (Person.SentimentSample s : p.sentimentHistory()) {
            ObjectNode sn = mapper.createObjectNode();
            sn.put("label", s.label());
            sn.put("score", s.score());
            sn.put("at", s.at().toString());
            sh.add(sn);
        }
        return n;
    }

    private Person parse(String json) {
        try {
            JsonNode n = mapper.readTree(json);
            List<String> roles = new ArrayList<>();
            n.path("roles").forEach(j -> roles.add(j.asText()));
            Map<String, String> prefs = new LinkedHashMap<>();
            n.path("preferences").fields().forEachRemaining(e -> prefs.put(e.getKey(), e.getValue().asText()));
            List<String> bt = new ArrayList<>();
            n.path("bannedTopics").forEach(j -> bt.add(j.asText()));
            List<String> kf = new ArrayList<>();
            n.path("knownFacts").forEach(j -> kf.add(j.asText()));
            List<Person.SentimentSample> sh = new ArrayList<>();
            for (JsonNode sn : n.path("sentimentHistory")) {
                sh.add(new Person.SentimentSample(
                        sn.path("label").asText(""),
                        sn.path("score").asDouble(0.0),
                        sn.hasNonNull("at") ? Instant.parse(sn.get("at").asText()) : Instant.now()
                ));
            }
            return new Person(
                    n.path("id").asText(""),
                    n.path("name").asText(""),
                    roles,
                    TrustLevel.valueOf(n.path("trustLevel").asText("GUEST")),
                    prefs, bt, kf,
                    n.hasNonNull("firstSeenAt") ? Instant.parse(n.get("firstSeenAt").asText()) : Instant.now(),
                    n.hasNonNull("lastSeenAt") ? Instant.parse(n.get("lastSeenAt").asText()) : null,
                    n.path("interactionCount").asLong(0),
                    sh
            );
        } catch (Exception e) {
            LOG.warning("PersonStore parse failed: " + e.getMessage());
            return null;
        }
    }
}
