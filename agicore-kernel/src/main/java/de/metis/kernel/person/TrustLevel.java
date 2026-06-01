package de.metis.kernel.person;

import de.metis.kernel.action.Action;

/**
 * Phase 11 — abgestufter Vertrauenslevel für eine Person.
 *
 * <p>Beeinflusst das Approval-Gate ({@code AUTO/NOTIFY/CONFIRM/FORBIDDEN}):
 * <ul>
 *   <li>{@link #OWNER} (4) — Voller Auto-Approval außer FORBIDDEN-Actions</li>
 *   <li>{@link #TRUSTED} (3) — Auto-Approval für alle non-destructive Actions</li>
 *   <li>{@link #KNOWN} (2) — NOTIFY für alle sensiblen Actions</li>
 *   <li>{@link #GUEST} (1) — CONFIRM-Default für alles non-trivial</li>
 *   <li>{@link #STRANGER} (0) — Strenger Allow-List-Modus, vieles FORBIDDEN</li>
 * </ul>
 */
public enum TrustLevel {
    STRANGER(0),
    GUEST(1),
    KNOWN(2),
    TRUSTED(3),
    OWNER(4);

    private final int rank;

    TrustLevel(int rank) { this.rank = rank; }

    public int rank() { return rank; }

    public boolean atLeast(TrustLevel other) { return this.rank >= other.rank; }

    /**
     * Maximale Auto-Approval-Stufe, die für diese Vertrauensstufe gilt.
     * <p>
     * Mappt den Personen-TrustLevel auf die höchste {@link Action.ApprovalLevel},
     * die der {@code AgentCoreLoop} ohne menschliche Bestätigung ausführen darf.
     * Aktionen oberhalb dieser Stufe erfordern Bestätigung. FORBIDDEN-Actions
     * bleiben immer gesperrt (das erzwingt der CoreLoop unabhängig hiervon).
     * <ul>
     *   <li>{@link #OWNER}    → {@link Action.ApprovalLevel#CONFIRM} (alles außer FORBIDDEN auto)</li>
     *   <li>{@link #TRUSTED}  → {@link Action.ApprovalLevel#NOTIFY}  (non-destructive auto)</li>
     *   <li>{@link #KNOWN}    → {@link Action.ApprovalLevel#NOTIFY}  (sensible Actions geloggt-auto)</li>
     *   <li>{@link #GUEST}    → {@link Action.ApprovalLevel#AUTO}    (nur read-only auto)</li>
     *   <li>{@link #STRANGER} → {@link Action.ApprovalLevel#AUTO}    (strikt, nur read-only)</li>
     * </ul>
     */
    public Action.ApprovalLevel maxAutoApproval() {
        return switch (this) {
            case OWNER            -> Action.ApprovalLevel.CONFIRM;
            case TRUSTED, KNOWN   -> Action.ApprovalLevel.NOTIFY;
            case GUEST, STRANGER  -> Action.ApprovalLevel.AUTO;
        };
    }
}
