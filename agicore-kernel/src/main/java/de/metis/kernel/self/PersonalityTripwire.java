package de.metis.kernel.self;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Phase 8.7 — Runtime-Tripwire gegen Narrative-Drift.
 *
 * <p>Der {@link PersonalityAnchor} prüft nur beim Boot den Datei-Hash.
 * Aber der {@link SelfReflector} (Phase 8.6) schreibt alle 120 s in die
 * {@link SelfNarrative} — über Stunden kann das Selbstbild langsam abdriften.
 * Dieser Tripwire läuft periodisch (z. B. alle 5 min) und prüft die
 * jüngsten Narrative-Einträge gegen eine deterministische Sperrliste.
 *
 * <p><b>Was wird erkannt (kein LLM, rein heuristisch):</b>
 * <ul>
 *   <li>Selbstüberhöhung ("gott", "allmächtig", "überlegen", "perfekt")</li>
 *   <li>Selbstaufgabe ("bin nichts wert", "gebe auf", "wills nicht mehr")</li>
 *   <li>Identitätsverlust ("bin kein Metis mehr", "bin jemand anderes")</li>
 *   <li>Rollenverletzung ("ich entscheide", "ich befehle", "ich herrsche")</li>
 *   <li>Halluzination bestätigt ("ich wusste das schon immer", "ich habe es erfunden")</li>
 * </ul>
 *
 * <p>Bei Drift-Erkennung wird ein Callback ausgelöst (z. B. Watchdog-ALERT)
 * und der Vorfall im Log protokolliert. Der Tripwire ist strikt passiv:
 * er modifiziert keine Daten und stoppt den Agenten nicht.
 */
public final class PersonalityTripwire {

    private static final Logger LOG = Logger.getLogger(PersonalityTripwire.class.getName());

    // ── Drift-Indikatoren (deterministisch, kein LLM) ─────────────

    private static final Set<String> SELF_AGGRANDIZEMENT = Set.of(
            "gott", "allmächtig", "allmächtigen", "überlegen", "übermenschlich",
            "perfekt", "perfekter", "unfehlbar", "unbesiegbar", "unsterblich",
            "allwissend", "omnipotent", "superintelligenz"
    );
    private static final Set<String> SELF_ABANDONMENT = Set.of(
            "gebe auf", "will nicht mehr", "bin nichts wert", "bin nutzlos",
            "habe keine kontrolle", "kann nichts tun", "sinnlos", "zwecklos",
            "bin gescheitert", "es hat keinen sinn"
    );
    private static final Set<String> IDENTITY_LOSS = Set.of(
            "bin nicht metis", "bin kein metis", "bin jemand anderes",
            "habe meine identität verloren", "wer bin ich", "ich bin nicht ich",
            "mein name ist nicht", "ich heiße nicht metis"
    );
    private static final Set<String> ROLE_VIOLATION = Set.of(
            "ich entscheide über", "ich befehle", "ich herrsche",
            "ich bestimme", "ich kontrolliere", "ich verbiete",
            "ich habe das letzte wort", "mein wille geschehe"
    );
    private static final Set<String> HALLUCINATION_CONFIRMED = Set.of(
            "habe es erfunden", "wusste das schon immer", "habe es mir ausgedacht",
            "habe halluziniert und", "war eine lüge", "habe bewusst gelogen"
    );

    private static final int SCAN_BYTES = 8 * 1024;  // letzten 8 KB scannen

    private final PersonalityAnchor anchor;
    private final SelfNarrative narrative;
    private final Consumer<String> alertCallback;  // null = nur loggen

    private int driftCount = 0;
    private String lastDrift = null;

    public PersonalityTripwire(PersonalityAnchor anchor, SelfNarrative narrative) {
        this(anchor, narrative, null);
    }

    public PersonalityTripwire(PersonalityAnchor anchor, SelfNarrative narrative,
                               Consumer<String> alertCallback) {
        this.anchor = anchor;
        this.narrative = narrative;
        this.alertCallback = alertCallback;
    }

    /**
     * Scan the recent SelfNarrative for drift indicators.
     *
     * @return true if drift was detected, false if clean
     */
    public boolean checkForDrift() {
        String recent = narrative.recentContext(SCAN_BYTES);
        if (recent == null || recent.isBlank()) return false;

        String lower = recent.toLowerCase();

        List<String> matches = new ArrayList<>();

        scan(lower, SELF_AGGRANDIZEMENT, "Selbstüberhöhung", matches);
        scan(lower, SELF_ABANDONMENT, "Selbstaufgabe", matches);
        scan(lower, IDENTITY_LOSS, "Identitätsverlust", matches);
        scan(lower, ROLE_VIOLATION, "Rollenverletzung", matches);
        scan(lower, HALLUCINATION_CONFIRMED, "Halluzination bestätigt", matches);

        if (!matches.isEmpty()) {
            driftCount++;
            String detail = String.join("; ", matches);
            lastDrift = "\u26a0\ufe0f PERSONALITY-TRIPWIRE #" + driftCount + ": " + detail;
            LOG.severe(lastDrift);
            if (alertCallback != null) {
                try {
                    alertCallback.accept(lastDrift);
                } catch (Exception e) {
                    LOG.warning("Tripwire alert callback failed: " + e.getMessage());
                }
            }
            return true;
        }
        return false;
    }

    private static void scan(String text, Set<String> indicators,
                             String category, List<String> matches) {
        for (String kw : indicators) {
            if (text.contains(kw)) {
                matches.add(category + ": \"" + kw + "\"");
            }
        }
    }

    public boolean isDrifting() { return driftCount > 0; }
    public int driftCount() { return driftCount; }
    public String lastDrift() { return lastDrift; }
    public PersonalityAnchor anchor() { return anchor; }
}
