package de.metis.kernel.person;

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
}
