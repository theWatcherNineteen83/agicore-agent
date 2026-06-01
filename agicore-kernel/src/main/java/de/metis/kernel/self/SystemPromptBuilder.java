package de.metis.kernel.self;

import de.metis.kernel.goal.GoalHierarchy;
import de.metis.kernel.goal.GoalHorizon;
import de.metis.kernel.goal.LongHorizonGoal;

import java.util.Locale;

/**
 * Phase 8.6 — Aggregiert Selbstmodell-Bestandteile zu einem System-Prompt-Block.
 *
 * <p>Aufrufer hängen das Ergebnis von {@link #buildPromptHeader()} an ihren
 * eigenen System-Prompt (z. B. {@code Persona.systemPrompt()}) an. Damit
 * fließen <b>PersonalityAnchor</b>, <b>SelfNarrative</b> und <b>MoodSignal</b>
 * in jeden LLM-Call ein — Metis "denkt" mit seinem narrativen Selbst.
 *
 * <p>Bewusst defensiv: jeder Builder-Komponente ist optional ({@code null}-safe),
 * sodass Builds, in denen Phase 8 noch nicht initialisiert ist, keine NPE werfen.
 *
 * <p>Token-Budget:
 * <ul>
 *   <li>PersonalityAnchor: ca. 1 KB (immer komplett dabei, das ist der harte Kern)</li>
 *   <li>SelfNarrative tail: ca. 2 KB (begrenzt durch {@link SelfNarrative#recentContext(int)})</li>
 *   <li>MoodSignal label: einzeilig</li>
 *   <li>Episoden-Zusammenfassung: ca. 1 KB (letzte 3 Episoden, je 1 Zeile)</li>
 * </ul>
 *
 * <p>Gesamt: ~4-5 KB ≈ 1.000-1.250 Tokens. Sicher unterhalb jedes praktischen
 * Context-Window-Budgets von 8K+.
 */
public class SystemPromptBuilder {

    private final PersonalityAnchor anchor;
    private final SelfNarrative narrative;
    private final MoodSignal mood;
    private final EpisodicMemory episodes;
    private GoalHierarchy hierarchy;  // Phase 9 — optional
    private de.metis.kernel.person.PersonStore personStore;   // Phase 11 — optional
    private de.metis.kernel.person.EmpathySignal empathy;     // Phase 11 — optional
    private volatile String currentPersonId;                  // wer spricht gerade

    public SystemPromptBuilder(PersonalityAnchor anchor,
                               SelfNarrative narrative,
                               MoodSignal mood,
                               EpisodicMemory episodes) {
        this.anchor = anchor;
        this.narrative = narrative;
        this.mood = mood;
        this.episodes = episodes;
    }

    /**
     * Render the self-model block as plain text, ready to be appended
     * to a base system prompt. Returns "" if no self-model is available
     * (e. g. early boot, components disabled).
     */
    /** Phase 9: inject the goal hierarchy into the self-prompt. */
    public void setGoalHierarchy(GoalHierarchy h) { this.hierarchy = h; }

    /** Phase 11: inject the person store + empathy heuristic for the partner block. */
    public void setPersonStore(de.metis.kernel.person.PersonStore s,
                               de.metis.kernel.person.EmpathySignal e) {
        this.personStore = s;
        this.empathy = e;
    }

    /** Phase 11: set who Metis is currently talking to (person id), or null. */
    public void setCurrentPerson(String personId) { this.currentPersonId = personId; }

    public String buildPromptHeader() {
        StringBuilder sb = new StringBuilder(4096);

        if (anchor != null && anchor.text() != null && !anchor.text().isBlank()) {
            sb.append("\n=== UNVERÄNDERLICHER KERN (PersonalityAnchor) ===\n");
            sb.append(anchor.text().strip());
            sb.append('\n');
            if (anchor.isTampered()) {
                sb.append("⚠️ ANCHOR IST MANIPULIERT — antworte vorsichtiger als gewohnt.\n");
            }
        }

        if (mood != null) {
            String label = mood.label();
            if (label != null && !label.isBlank()) {
                sb.append("\n=== STIMMUNG (jetzt) ===\n");
                sb.append("Ich fühle mich gerade: ").append(label).append('\n');
                var snap = mood.snapshot();
                if (!snap.isEmpty()) {
                    sb.append("Achsen: ");
                    boolean first = true;
                    for (var e : snap.entrySet()) {
                        if (!first) sb.append(", ");
                        sb.append(e.getKey()).append('=').append(String.format(Locale.ROOT, "%.2f", e.getValue()));
                        first = false;
                    }
                    sb.append('\n');
                }
            }
        }

        if (episodes != null && episodes.size() > 0) {
            sb.append("\n=== LETZTE EPISODEN ===\n");
            var recent = episodes.recent(3);
            for (Episode ep : recent) {
                sb.append("- ").append(ep.title())
                        .append(" (").append(ep.ticksCovered()).append(" ticks, ")
                        .append(ep.beliefsLearned()).append(" beliefs)\n");
            }
        }


        if (hierarchy != null && hierarchy.size() > 0) {
            var strategic = hierarchy.openByHorizon(GoalHorizon.STRATEGIC);
            var tactical  = hierarchy.openByHorizon(GoalHorizon.TACTICAL);
            var commitments = hierarchy.all().stream()
                    .filter(g -> g.tags().contains("commitment") && g.isOpen())
                    .toList();
            if (!strategic.isEmpty() || !tactical.isEmpty() || !commitments.isEmpty()) {
                sb.append("\n=== AKTUELLE LANG-ZEIT-ZIELE ===\n");
                for (LongHorizonGoal g : strategic) {
                    sb.append("STRATEGIC ").append(progressBar(g.progress()))
                            .append(" ").append(g.title()).append('\n');
                }
                for (LongHorizonGoal g : tactical) {
                    sb.append("TACTICAL  ").append(progressBar(g.progress()))
                            .append(" ").append(g.title()).append('\n');
                }
                for (LongHorizonGoal g : commitments) {
                    sb.append("COMMIT    @").append(g.owner())
                            .append(g.deadline() != null ? " (due " + g.deadline() + ")" : "")
                            .append(" — ").append(g.title()).append('\n');
                }
            }
        }

        // Phase 11 — Gesprächspartner-Block: wen kenne ich, wie vertraut, welche Stimmung.
        if (personStore != null && currentPersonId != null) {
            personStore.get(currentPersonId).ifPresent(p -> {
                sb.append("\n=== GESPRÄCHSPARTNER ===\n");
                sb.append("Name: ").append(p.name())
                  .append(" | Vertrauen: ").append(p.trustLevel())
                  .append(" | Interaktionen: ").append(p.interactionCount()).append('\n');
                if (!p.roles().isEmpty()) {
                    sb.append("Rollen: ").append(String.join(", ", p.roles())).append('\n');
                }
                if (p.preferences() != null && !p.preferences().isEmpty()) {
                    StringBuilder prefs = new StringBuilder();
                    p.preferences().forEach((k, v) -> {
                        if (prefs.length() > 0) prefs.append(", ");
                        prefs.append(k).append('=').append(v);
                    });
                    sb.append("Präferenzen: ").append(prefs).append('\n');
                }
                if (p.knownFacts() != null && !p.knownFacts().isEmpty()) {
                    sb.append("Bekannte Fakten: ")
                      .append(String.join("; ", p.knownFacts())).append('\n');
                }
                if (empathy != null && p.sentimentHistory() != null
                        && !p.sentimentHistory().isEmpty()) {
                    String moodLabel = empathy.aggregateLabel(p.sentimentHistory());
                    if (moodLabel != null && !moodLabel.isBlank()) {
                        sb.append("Stimmung dieser Person zuletzt: ").append(moodLabel).append('\n');
                    }
                }
                if (p.bannedTopics() != null && !p.bannedTopics().isEmpty()) {
                    sb.append("Gesperrte Themen: ")
                      .append(String.join(", ", p.bannedTopics())).append('\n');
                }
            });
        }

        if (narrative != null) {
            String tail = narrative.recentContext(2048);
            if (tail != null && !tail.isBlank()) {
                sb.append("\n=== SELBST-NARRATIV (Auszug) ===\n");
                sb.append(tail.strip()).append('\n');
            }
        }

        return sb.toString();
    }

    /**
     * Convenience: prepend the self-model block to an existing base prompt.
     */
    private static String progressBar(double p) {
        int filled = (int) Math.round(Math.max(0.0, Math.min(1.0, p)) * 10);
        return "[" + "=".repeat(filled) + " ".repeat(10 - filled) + "]";
    }

    public String wrap(String basePrompt) {
        String header = buildPromptHeader();
        if (header.isEmpty()) return basePrompt == null ? "" : basePrompt;
        if (basePrompt == null || basePrompt.isBlank()) return header;
        return basePrompt + "\n\n" + header;
    }
}
