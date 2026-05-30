package de.metis.kernel.self;

import de.metis.kernel.metrics.FitnessSignal;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.logging.Logger;

/**
 * Phase 8.5 — DreamConsolidation.
 *
 * <p>"Schlaf-Analogie": einmal pro Nacht (oder bei explizitem Anstoß) wird
 * der Tag in eine {@link Episode} verdichtet, dann ein kurzer Selbst-Absatz
 * an {@link SelfNarrative} angehängt. Das ist der Punkt, an dem Metis das
 * Gewesene zu einem Stück Selbst macht — nicht in einem mystischen Sinn,
 * sondern als kognitive Architektur (Hippocampus → Neocortex Replay).
 *
 * <p>Implementiert hier <em>ohne</em> LLM, weil:
 * <ul>
 *   <li>Phase 8 soll deterministisch laufen, ohne weitere VRAM-Belastung</li>
 *   <li>LLM-getriebene Verdichtung kann als optionaler Drop-in folgen
 *       (Phase 8.5b — Setter für eine {@code SummaryFunction})</li>
 * </ul>
 *
 * <p>Diese Klasse arbeitet rein auf Eingabe-Records (Stats, Mood, jüngste
 * Events) und produziert deterministische deutsche Narrative. Sie ist
 * idempotent: zweimal hintereinander aufgerufen schreibt zwei Episoden,
 * aber jede ist konsistent.
 */
public class DreamConsolidation {

    private static final Logger LOG = Logger.getLogger(DreamConsolidation.class.getName());

    /** Optional LLM-summary hook for Phase 8.5b. If null we use the deterministic path. */
    public interface SummaryFunction {
        String summarize(DayStats stats);
    }

    public record DayStats(
            Instant start,
            Instant end,
            long ticksCovered,
            int beliefsLearned,
            int goalsCompleted,
            int goalsFailed,
            double successRate,
            double evalGateOk,
            List<String> notableEvents,
            List<String> openQuestions,
            List<String> peopleSeen,
            FitnessSignal fitness
    ) {}

    private final EpisodicMemory episodes;
    private final SelfNarrative narrative;
    private final MoodSignal mood;
    private SummaryFunction summarizer;

    public DreamConsolidation(EpisodicMemory episodes,
                              SelfNarrative narrative,
                              MoodSignal mood) {
        this.episodes = episodes;
        this.narrative = narrative;
        this.mood = mood;
    }

    public void setSummarizer(SummaryFunction s) { this.summarizer = s; }

    /**
     * Run one consolidation pass. Persists an Episode and appends one
     * paragraph to SelfNarrative.
     *
     * @return the persisted episode (or null on IO failure)
     */
    public Episode consolidate(DayStats stats) {
        if (stats == null) return null;
        String dayKey = stats.start().truncatedTo(ChronoUnit.DAYS).toString().substring(0, 10);
        String id = "ep-" + dayKey + "-" + Long.toHexString(System.nanoTime() & 0xFFFFFFL);
        String title = "Tag " + dayKey;

        String body = summarizer != null ? summarizer.summarize(stats) : renderBody(stats);

        Map<String, Double> moodSnapshot = mood != null ? mood.snapshot() : Map.of();
        Episode candidate = new Episode(
                id,
                stats.start(),
                stats.end(),
                title,
                body,
                safe(stats.notableEvents()),
                deriveInsights(stats),
                safe(stats.openQuestions()),
                safe(stats.peopleSeen()),
                moodSnapshot,
                stats.ticksCovered(),
                stats.beliefsLearned(),
                stats.goalsCompleted(),
                stats.goalsFailed(),
                /* prevHash */ "",
                /* hash */ ""
        );

        Episode persisted = episodes.append(candidate);
        if (persisted != null && narrative != null) {
            narrative.append("dream", renderNarrativeEntry(stats, persisted));
        }
        LOG.info("DreamConsolidation: persisted episode " + id);
        return persisted;
    }

    private static String renderBody(DayStats s) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("## Was geschah\n\n");
        sb.append(String.format(Locale.ROOT,
                "- Ticks: %d, Beliefs gelernt: %d, Goals fertig: %d, Goals fehlgeschlagen: %d%n",
                s.ticksCovered(), s.beliefsLearned(), s.goalsCompleted(), s.goalsFailed()));
        sb.append(String.format(Locale.ROOT,
                "- Erfolgsquote: %.2f, Eval-Gate ok: %.0f%%%n",
                s.successRate(), s.evalGateOk() * 100));
        if (!s.notableEvents().isEmpty()) {
            sb.append("\n## Erinnerungswerte Ereignisse\n");
            int limit = Math.min(10, s.notableEvents().size());
            for (int i = 0; i < limit; i++) sb.append("- ").append(s.notableEvents().get(i)).append('\n');
        }
        if (!s.openQuestions().isEmpty()) {
            sb.append("\n## Offene Fragen\n");
            int limit = Math.min(5, s.openQuestions().size());
            for (int i = 0; i < limit; i++) sb.append("- ").append(s.openQuestions().get(i)).append('\n');
        }
        return sb.toString();
    }

    private static List<String> deriveInsights(DayStats s) {
        List<String> out = new ArrayList<>();
        if (s.successRate() > 0.9) out.add("Hohe Erfolgsquote — Strategie greift.");
        if (s.successRate() < 0.4) out.add("Niedrige Erfolgsquote — Strategie überdenken.");
        if (s.goalsFailed() > s.goalsCompleted())
            out.add("Mehr Goals gescheitert als gelungen — Plan-Qualität prüfen.");
        if (s.beliefsLearned() > 200)
            out.add("Großer Wissenszuwachs an diesem Tag.");
        if (s.evalGateOk() < 1.0)
            out.add("Eval-Gate war zwischenzeitlich rot — Promotion-Risiko.");
        return out;
    }

    private static String renderNarrativeEntry(DayStats s, Episode persisted) {
        return String.format(Locale.ROOT,
                "Heute habe ich %d Ticks durchgearbeitet und %d Beliefs gelernt. "
                        + "Von %d gestarteten Goals sind %d gelungen, %d gescheitert. "
                        + "Mein Eval-Gate war %s. "
                        + "Episode-Hash: %s.",
                s.ticksCovered(),
                s.beliefsLearned(),
                s.goalsCompleted() + s.goalsFailed(),
                s.goalsCompleted(),
                s.goalsFailed(),
                s.evalGateOk() >= 1.0 ? "stabil grün" : "instabil",
                persisted.hash().substring(0, Math.min(12, persisted.hash().length())));
    }

    private static <T> List<T> safe(List<T> in) { return in == null ? List.of() : in; }
}
