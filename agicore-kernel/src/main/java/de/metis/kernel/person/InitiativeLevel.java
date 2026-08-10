package de.metis.kernel.person;

/**
 * Phase 11.5 — Wie stark Metis von sich aus auf eine Person zugehen darf.
 *
 * <p>Abgestuft von komplett still bis voller Gesprächsinitiative:
 * <ul>
 *   <li>{@link #SILENT} — Niemals initiieren, nicht mal auf Heartbeat reagieren</li>
 *   <li>{@link #REACT_ONLY} — Nur antworten wenn direkt angesprochen</li>
 *   <li>{@link #NOTIFY} — Kritische Alarme (Security, Wetter-Warnungen, Server-Ausfälle)</li>
 *   <li>{@link #SUGGEST} — Notifications + Beobachtungen/Vorschläge</li>
 *   <li>{@link #CONVERSE} — Volle Gesprächsinitiative, Smalltalk, proaktive Updates</li>
 * </ul>
 *
 * <p>Default-Mapping von {@link TrustLevel}:
 * <pre>
 *   OWNER    → CONVERSE   (volle Initiative)
 *   TRUSTED  → SUGGEST    (darf Vorschläge machen)
 *   KNOWN    → NOTIFY     (nur wichtige Benachrichtigungen)
 *   GUEST    → REACT_ONLY (nur auf Anfrage)
 *   STRANGER → SILENT     (keine Initiative)
 * </pre>
 */
public enum InitiativeLevel {
    SILENT(0),
    REACT_ONLY(1),
    NOTIFY(2),
    SUGGEST(3),
    CONVERSE(4);

    private final int rank;

    InitiativeLevel(int rank) { this.rank = rank; }

    public int rank() { return rank; }

    public boolean atLeast(InitiativeLevel other) {
        return this.rank >= other.rank;
    }

    /**
     * Default-Mapping: TrustLevel → InitiativeLevel.
     */
    public static InitiativeLevel fromTrustLevel(TrustLevel trust) {
        return switch (trust) {
            case OWNER    -> CONVERSE;
            case TRUSTED  -> SUGGEST;
            case KNOWN    -> NOTIFY;
            case GUEST    -> REACT_ONLY;
            case STRANGER -> SILENT;
        };
    }

    /**
     * Darf Metis von sich aus eine Nachricht dieser Kategorie senden?
     *
     * @param category Kategorie der Nachricht (alert, suggestion, chat, weather, …)
     * @return true wenn das Level diese Kategorie erlaubt
     */
    public boolean allowsCategory(String category) {
        if (category == null) return false;
        return switch (this) {
            case CONVERSE   -> true; // alles erlaubt
            case SUGGEST    -> !"chat".equals(category); // kein Smalltalk
            case NOTIFY     -> "alert".equals(category)
                            || "weather".equals(category)
                            || "security".equals(category)
                            || "server".equals(category);
            case REACT_ONLY -> false; // nie initiieren
            case SILENT     -> false;
        };
    }
}
