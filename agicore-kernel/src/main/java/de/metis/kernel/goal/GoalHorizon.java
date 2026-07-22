package de.metis.kernel.goal;

/**
 * Phase 9 — Zeit-Horizont eines Ziels.
 *
 * <p>Der bestehende {@link Goal}-Record bleibt unverändert. {@code GoalHorizon}
 * wird auf einer höheren Ebene ({@link LongHorizonGoal}) angeflanscht.
 *
 * <ul>
 *   <li>{@link #TICK} — passt in einen Cognitive-Cycle-Tick (~5s)</li>
 *   <li>{@link #OPERATIONAL} — Stunden, mehrere zusammenhängende Ticks</li>
 *   <li>{@link #TACTICAL} — Tag, typische TODO-Granularität</li>
 *   <li>{@link #STRATEGIC} — Woche oder länger, Phasen-Granularität</li>
 *   <li>{@link #LIFETIME} — Dauerziel, wird nie als "fertig" markiert (z. B. "ehrlich bleiben")</li>
 * </ul>
 *
 * <p>Decomposition geht <em>nur</em> Top-Down:
 * STRATEGIC → TACTICAL → OPERATIONAL → TICK. LIFETIME ist parallel und
 * beeinflusst Priorisierung, wird aber nicht direkt zerlegt.
 */
public enum GoalHorizon {
    TICK,
    OPERATIONAL,
    TACTICAL,
    STRATEGIC,
    LIFETIME;

    /** True, wenn dieser Horizont aus einem höheren zerlegt werden kann. */
    public boolean canBeDecomposed() {
        return this == LIFETIME || this == STRATEGIC || this == TACTICAL || this == OPERATIONAL;
    }

    /** Der nächst-engere Horizont bei Decomposition (oder null für TICK). */
    public GoalHorizon nextDown() {
        return switch (this) {
            case LIFETIME    -> STRATEGIC;
            case STRATEGIC   -> TACTICAL;
            case TACTICAL    -> OPERATIONAL;
            case OPERATIONAL -> TICK;
            default          -> null;
        };
    }
}
