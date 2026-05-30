package de.metis.kernel.self;

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
    public String wrap(String basePrompt) {
        String header = buildPromptHeader();
        if (header.isEmpty()) return basePrompt == null ? "" : basePrompt;
        if (basePrompt == null || basePrompt.isBlank()) return header;
        return basePrompt + "\n\n" + header;
    }
}
