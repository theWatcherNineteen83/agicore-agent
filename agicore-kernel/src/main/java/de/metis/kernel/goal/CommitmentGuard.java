package de.metis.kernel.goal;

import java.util.logging.Logger;

/**
 * Phase 9.5 — Hot-Path-Wächter gegen leichtfertigen Commitment-Bruch.
 *
 * <p>Konvergente Forderung der externen KI-Reviews (ChatGPT, Bronxe, miniMax,
 * 2026-05-31): „Menschen wirken kohärent, weil sie Commitments besitzen. Ein
 * Planner darf Ziele nicht einfach verwerfen — er muss erklären <em>warum</em>."
 *
 * <p>Das bestehende {@link CommitmentRegister} legt Versprechen als
 * {@link LongHorizonGoal} mit hoher Priorität (≥ {@link #HARD_PRIORITY}) und Tag
 * {@code "commitment"} an. Dieser Guard sitzt vor jedem Statuswechsel nach
 * {@link LongHorizonGoal.Status#ABANDONED} und erzwingt:
 *
 * <ul>
 *   <li><b>HARD-Commitment</b> (offen, priority ≥ 85, Tag {@code commitment}):
 *       Abbruch nur mit nicht-leerer Begründung — sonst {@link Decision#denied}.</li>
 *   <li><b>SOFT-Commitment</b>: Abbruch erlaubt, aber als {@link Decision#flagged}
 *       protokolliert (Begründung empfohlen).</li>
 *   <li>Alles andere: {@link Decision#allowed}.</li>
 * </ul>
 *
 * <p>Bewusst rein deterministisch, kein LLM, kein I/O — daher im Kernel.
 * Der aufrufende Pfad (Planner/Revision) entscheidet, wie er auf {@code denied}
 * reagiert (Aktion ablehnen, Begründung nachfordern, Goal aktiv lassen).
 */
public final class CommitmentGuard {

    private static final Logger LOG = Logger.getLogger(CommitmentGuard.class.getName());

    /** Ab dieser Priorität gilt ein offenes Commitment als HARD. */
    public static final int HARD_PRIORITY = 85;

    /** Tag, mit dem {@link CommitmentRegister} Versprechen markiert. */
    public static final String COMMITMENT_TAG = "commitment";

    private long deniedCount = 0;
    private long flaggedCount = 0;
    private long allowedCount = 0;

    /** Outcome einer Abbruch-Anfrage. */
    public enum Verdict { ALLOWED, FLAGGED, DENIED }

    /** Ergebnis-Record inkl. menschenlesbarer Begründung. */
    public record Decision(Verdict verdict, String reason) {
        public static Decision allowed(String r) { return new Decision(Verdict.ALLOWED, r); }
        public static Decision flagged(String r)  { return new Decision(Verdict.FLAGGED, r); }
        public static Decision denied(String r)   { return new Decision(Verdict.DENIED, r); }
        public boolean isAllowed() { return verdict == Verdict.ALLOWED || verdict == Verdict.FLAGGED; }
        public boolean isDenied()  { return verdict == Verdict.DENIED; }
    }

    /** True, wenn das Goal ein HARD-Commitment ist (offen, hohe Priorität, Tag). */
    public static boolean isHardCommitment(LongHorizonGoal g) {
        return g != null
                && g.isOpen()
                && g.priority() >= HARD_PRIORITY
                && g.tags() != null
                && g.tags().contains(COMMITMENT_TAG);
    }

    /** True, wenn das Goal ein (nicht-hartes) Commitment ist. */
    public static boolean isSoftCommitment(LongHorizonGoal g) {
        return g != null
                && g.isOpen()
                && g.priority() < HARD_PRIORITY
                && g.tags() != null
                && g.tags().contains(COMMITMENT_TAG);
    }

    /**
     * Prüft, ob ein Commitment-Goal abgebrochen (ABANDONED) werden darf.
     *
     * @param goal      das betroffene Goal (darf null sein → ALLOWED)
     * @param rationale Begründung des Abbruchs (kann null/blank sein)
     * @return Entscheidung; bei {@link Verdict#DENIED} sollte der Abbruch unterbleiben
     */
    public Decision evaluateAbandon(LongHorizonGoal goal, String rationale) {
        boolean hasReason = rationale != null && !rationale.isBlank();

        if (isHardCommitment(goal)) {
            if (!hasReason) {
                deniedCount++;
                String msg = "HARD-Commitment '" + safeTitle(goal)
                        + "' kann nicht ohne Begründung abgebrochen werden";
                LOG.warning("CommitmentGuard DENIED: " + msg);
                return Decision.denied(msg);
            }
            flaggedCount++;
            String msg = "HARD-Commitment '" + safeTitle(goal)
                    + "' abgebrochen mit Begründung: " + rationale.strip();
            LOG.info("CommitmentGuard FLAGGED (hard w/ reason): " + msg);
            return Decision.flagged(msg);
        }

        if (isSoftCommitment(goal)) {
            flaggedCount++;
            String msg = "SOFT-Commitment '" + safeTitle(goal) + "' abgebrochen"
                    + (hasReason ? " (" + rationale.strip() + ")" : " (ohne Begründung)");
            LOG.fine("CommitmentGuard FLAGGED (soft): " + msg);
            return Decision.flagged(msg);
        }

        allowedCount++;
        return Decision.allowed("kein Commitment — Abbruch erlaubt");
    }

    /**
     * Bequemer Boolean-Check für Hot-Path-Aufrufer.
     *
     * @return true, wenn der Abbruch erfolgen darf (ALLOWED/FLAGGED)
     */
    public boolean mayAbandon(LongHorizonGoal goal, String rationale) {
        return evaluateAbandon(goal, rationale).isAllowed();
    }

    private static String safeTitle(LongHorizonGoal g) {
        if (g == null) return "?";
        String t = g.title();
        if (t == null) return g.id() == null ? "?" : g.id().toString();
        return t.length() <= 60 ? t : t.substring(0, 59) + "…";
    }

    public long deniedCount()  { return deniedCount; }
    public long flaggedCount() { return flaggedCount; }
    public long allowedCount() { return allowedCount; }
}
