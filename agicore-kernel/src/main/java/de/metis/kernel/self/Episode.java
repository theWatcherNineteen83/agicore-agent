package de.metis.kernel.self;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A narrative episode — a verdichteter Schnappschuss eines Zeitraums
 * (typischerweise ein Tag oder ein bedeutender Block), aus dem Metis später
 * ein narratives Selbst rekonstruieren kann.
 *
 * <p>Im Gegensatz zu rohen Beliefs/Experiences hat eine Episode <em>Narrativ</em>:
 * sie sagt nicht nur "Belief X mit Confidence 0.85", sondern "heute habe ich
 * gelernt, dass X, und das war wichtig, weil Y".
 *
 * <p>Designprinzipien (Phase 8):
 * <ul>
 *   <li>Append-only — eine Episode wird einmal erzeugt und nie editiert</li>
 *   <li>Zeitlich verankert — start/end Instant, plus dominant tags</li>
 *   <li>Token-budgetiert — Markdown-Body ≤ 4 KB, damit Metis viele Episoden
 *       gleichzeitig im Kontext halten kann</li>
 *   <li>Multi-dimensional — was passiert (events), was gelernt (insights),
 *       was offen (questions), wer beteiligt (people), wie ich mich fühlte (mood)</li>
 * </ul>
 *
 * <p>Eine Episode trägt einen <em>SHA-256-Hash</em> über ihre Felder; der Hash
 * wird in einem nachgelagerten EpisodicMemory in eine schwache Chain integriert,
 * sodass Manipulation extern erkennbar wird (gleicher Schutz wie der Watchdog-Audit-Log).
 */
public record Episode(
        String id,
        Instant start,
        Instant end,
        String title,
        String body,
        List<String> events,
        List<String> insights,
        List<String> openQuestions,
        List<String> people,
        String personId,
        Map<String, Double> moodAtClose,
        long ticksCovered,
        int beliefsLearned,
        int goalsCompleted,
        int goalsFailed,
        String previousHash,
        String hash
) {
    public Episode {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id required");
        if (start == null) throw new IllegalArgumentException("start required");
        if (end == null) throw new IllegalArgumentException("end required");
        if (end.isBefore(start)) throw new IllegalArgumentException("end before start");
        if (title == null) title = "";
        if (body == null) body = "";
        if (events == null) events = List.of();
        if (insights == null) insights = List.of();
        if (openQuestions == null) openQuestions = List.of();
        if (people == null) people = List.of();
        if (personId == null || personId.isBlank()) personId = "";
        if (moodAtClose == null) moodAtClose = Map.of();
        if (previousHash == null) previousHash = "GENESIS";
        if (hash == null) hash = "";
    }

    /** Filter episodes by person (Phase 11 — Multi-Person-Memory). */
    public boolean involvesPerson(String pid) {
        return pid != null && (pid.equals(personId) || people.contains(pid));
    }
}
