package de.metis.kernel.world;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 10 — eine kausale Hypothese, die Metis aktiv überprüfen will.
 *
 * <p>Im Gegensatz zu {@link CausalModel.CausalLink} (nachträglich gelernt
 * aus Beobachtungen) ist eine Hypothese <em>vorgelagert</em>: sie wird
 * generiert (z. B. durch {@code HypothesisGenerator} aus einem Surprise-
 * Signal) und durch eine Intervention bestätigt oder verworfen.
 *
 * <p>Lifecycle: PROPOSED → TESTING → CONFIRMED | REFUTED | ABANDONED.
 *
 * <p>Felder:
 * <ul>
 *   <li><b>cause</b>, <b>condition</b>, <b>effect</b> — wie bei CausalLink</li>
 *   <li><b>predictedDirection</b> — UP / DOWN / FLAT (erwartete Wirkung)</li>
 *   <li><b>predictedMagnitude</b> — 0.0..1.0 wie stark</li>
 *   <li><b>rationale</b> — warum diese Hypothese (Surprise-Trigger etc.)</li>
 *   <li><b>plannedAction</b> — kurzbeschreibung der Intervention</li>
 * </ul>
 *
 * <p>Bewusst Record, unveränderlich; State-Übergänge erzeugen neue Instanz.
 */
public record CausalHypothesis(
        UUID id,
        String cause,
        String condition,
        String effect,
        Direction predictedDirection,
        double predictedMagnitude,
        String rationale,
        String plannedAction,
        Status status,
        Instant createdAt,
        Instant testedAt,
        Direction observedDirection,
        double observedMagnitude,
        String resultNote
) {
    public enum Direction { UP, DOWN, FLAT }
    public enum Status { PROPOSED, TESTING, CONFIRMED, REFUTED, ABANDONED }

    public CausalHypothesis {
        if (id == null) id = UUID.randomUUID();
        if (cause == null || cause.isBlank()) throw new IllegalArgumentException("cause required");
        if (effect == null || effect.isBlank()) throw new IllegalArgumentException("effect required");
        if (condition == null) condition = "";
        if (predictedDirection == null) predictedDirection = Direction.UP;
        if (status == null) status = Status.PROPOSED;
        if (createdAt == null) createdAt = Instant.now();
        if (rationale == null) rationale = "";
        if (plannedAction == null) plannedAction = "";
        if (resultNote == null) resultNote = "";
        if (predictedMagnitude < 0) predictedMagnitude = 0.0;
        if (predictedMagnitude > 1) predictedMagnitude = 1.0;
        if (observedMagnitude < 0) observedMagnitude = 0.0;
        if (observedMagnitude > 1) observedMagnitude = 1.0;
    }

    public CausalHypothesis withStatus(Status s) {
        return new CausalHypothesis(id, cause, condition, effect, predictedDirection,
                predictedMagnitude, rationale, plannedAction, s,
                createdAt,
                s == Status.TESTING || s == Status.CONFIRMED || s == Status.REFUTED ? Instant.now() : testedAt,
                observedDirection, observedMagnitude, resultNote);
    }

    public CausalHypothesis withResult(Direction observedDir, double observedMag, String note) {
        Status next = matches(observedDir) ? Status.CONFIRMED : Status.REFUTED;
        return new CausalHypothesis(id, cause, condition, effect, predictedDirection,
                predictedMagnitude, rationale, plannedAction, next,
                createdAt, Instant.now(),
                observedDir, observedMag,
                note == null ? "" : note);
    }

    public boolean matches(Direction observed) {
        return observed == predictedDirection;
    }

    public boolean isOpen() {
        return status == Status.PROPOSED || status == Status.TESTING;
    }
}
