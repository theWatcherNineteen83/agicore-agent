package de.metis.kernel.world;

import java.util.Locale;

/**
 * Phase 12e Baustein 1 — Provenienz-Taxonomie.
 *
 * <p>Beantwortet die Frage <b>„woher weiß ich das eigentlich?"</b> für eine
 * Überzeugung ({@link Belief}). Das ist der Engpass für rekursive
 * Selbstverbesserung: Ein System, das nicht unterscheiden kann, was es
 * <i>beobachtet</i> hat und was es <i>erschlossen/ausgedacht</i> hat, würde
 * seine eigenen Halluzinationen als Fakten lernen.
 *
 * <p>Motivation (Whittaker-Methode): Marketing-Sprache ist kein Beleg.
 * Ein Sensorwert, ein HTTP-Status oder ein Messwert ist real — eine
 * LLM-Plausibilität ist es nicht. Beides muss im System unterscheidbar sein.
 *
 * <ul>
 *   <li>{@link #OBSERVED} — direkt gemessen/verifiziert (Sensor, HTTP 200, Datei gelesen, abgeschlossene Ausführung)</li>
 *   <li>{@link #INFERRED} — erschlossen/generiert (LLM-Plausibilität, Hypothese, Traum/Reflexion)</li>
 *   <li>{@link #QUOTED} — zitiert (Buch, Sutta, Wiki, Artikel, Marketing-Copy) — nicht unabhängig verifiziert</li>
 *   <li>{@link #USER} — vom Menschen gesagt (Georg)</li>
 *   <li>{@link #UNKNOWN} — Herkunft unbekannt (Legacy-Beliefs, nicht klassifizierbar)</li>
 * </ul>
 *
 * <p>Bewusst nicht-invasiv: {@link Belief#source()} bleibt ein freier String
 * (SQLite-Schema unverändert). {@link #classify(String)} ordnet die bestehenden
 * Quell-Strings heuristisch zu; {@link Belief#provenance()} kapselt das.
 */
public enum Provenance {

    OBSERVED,
    INFERRED,
    QUOTED,
    USER,
    UNKNOWN;

    /**
     * Heuristische Zuordnung eines bestehenden {@code source}-Strings zur
     * Taxonomie. Konservativ: alles Unerkannte wird {@link #UNKNOWN}, damit
     * keine unbelegte Herkunft behauptet wird.
     *
     * @param source der freie Quell-String (z. B. "observation", "user", …)
     * @return die bestmögliche Zuordnung; {@code null}/{@code blank} → {@link #UNKNOWN}
     */
    public static Provenance classify(String source) {
        if (source == null || source.isBlank()) {
            return UNKNOWN;
        }
        String s = source.toLowerCase(Locale.ROOT);

        // USER zuerst: eindeutig menschlicher Ursprung.
        if (containsAny(s, "user", "georg", "mensch", "human", "owner")) {
            return USER;
        }
        // OBSERVED: real gemessen/ausgeführt/verifiziert.
        if (containsAny(s, "observ", "sensor", "measur", "verified", "confirmed",
                "checked", "camera", "readsb", "adsb", "gps", "completed", "sql:", "java-gen:")) {
            return OBSERVED;
        }
        // QUOTED: aus einer externen Quelle übernommen, nicht selbst geprüft.
        if (containsAny(s, "quote", "quoted", "zitat", "book", "sutta", "wiki",
                "article", "cite", "reference", "marketing", "document", "dhamma", "metta")) {
            return QUOTED;
        }
        // INFERRED: erschlossen/generiert/abgeleitet.
        if (containsAny(s, "infer", "generat", "llm", "hypothes", "derived",
                "model", "dream", "reflect")) {
            return INFERRED;
        }
        return UNKNOWN;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
