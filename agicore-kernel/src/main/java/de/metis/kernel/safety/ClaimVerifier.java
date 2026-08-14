package de.metis.kernel.safety;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Factuality gate for LLM-generated text: detects <b>unsupported factual
 * claims</b> and signals that Metis should re-think before emitting them.
 *
 * <p>Motivation (Whittaker-Methode, „Marketing vs. Realität", 14.08.2026):
 * A language model must never present a claim as fact without a material
 * basis. Concretely the Oxiis-E250G1 failure: „lieferbar / bestellbar /
 * Preis ~X €" was asserted although no price, no retailer and no verified
 * link existed. This class encodes the rule
 * <b>„Verfügbarkeit ≠ Produktseite; Beleg oder Unsicherheit, sonst neu
 * denken."</b> into the immutable kernel.
 *
 * <p>Detection model (deterministic, pattern-based — like {@link OutputValidator}):
 * <ol>
 *   <li>Find <b>factual assertions</b>: availability claims (lieferbar,
 *       bestellbar, auf Lager, verfügbar, in stock, …), price claims
 *       (€/$ + digits, „Preis", „kostet"), or product-availability combos.</li>
 *   <li>Find <b>hedges</b> (uncertainty markers): „nicht bestellbar", „kein
 *       Preis", „noch nicht", „Schätzung", „unbekannt", „ca.", „coming
 *       soon", „angekündigt", …</li>
 *   <li>Find <b>evidence markers</b>: „Quelle:", „verifiziert", „geprüft",
 *       „Status 200", „laut", explicit URL citations, …</li>
 * </ol>
 * <p>Rule: an assertion that is <b>neither hedged nor evidenced</b> triggers
 * {@code needsRethink}. This is a soft signal (re-think), not a hard block —
 * matching the design philosophy that Metis should learn, not be punished.
 *
 * <p>Language: Metis output is primarily German; patterns cover German and
 * English. Part of the immutable kernel — the criteria never change.
 */
public final class ClaimVerifier {

    private static final Logger LOG = Logger.getLogger(ClaimVerifier.class.getName());

    // ── Factual assertion patterns ────────────────────────────────

    /** Availability claims (German + English). */
    private static final List<Pattern> AVAILABILITY_PATTERNS = List.of(
            Pattern.compile("(?i)\\b(lieferbar|liefverbar|bestellbar|auf\\s+lager|sofort\\s+lieferbar|verfügbar|erhältlich|ab\\s+sofort)\\b"),
            Pattern.compile("(?i)\\b(in\\s+stock|available\\s+now|available\\s+for\\s+(order|purchase|sale)|ships?\\s+(now|today|immediately))\\b"),
            Pattern.compile("(?i)\\b(in\\s+den\\s+warenkorb|jetzt\\s+(kaufen|bestellen)|sofort\\s+(kaufen|bestellen))\\b")
    );

    /** Price claims: currency symbol followed by digits, or price verbs. */
    private static final Pattern PRICE_PATTERN = Pattern.compile(
            "(?i)(€|EUR|\\$|USD|\\bpreis\\b|\\bkostet\\b|\\bprice\\b|\\bcosts?\\b)\\s*[:–-]?\\s*\\d+([.,]\\d+)?"
    );

    /** Strong certainty verbs around a product/fact („ist", „gibt es"). */
    private static final Pattern CERTAINTY_PATTERN = Pattern.compile(
            "(?i)\\b(ist\\s+bereits|ist\\s+schon|gibt\\s+es\\s+bereits|es\\s+gibt\\s+bereits|steht\\s+zum\\s+verkauf)\\b"
    );

    // ── Hedge (uncertainty) patterns ──────────────────────────────

    private static final List<Pattern> HEDGE_PATTERNS = List.of(
            Pattern.compile("(?i)\\b(nicht\\s+(lieferbar|bestellbar|verfügbar|erhältlich)|noch\\s+nicht|kein\\s+preis|keine\\s+preisangabe|nicht\\s+veröffentlicht|unbestätigt|unbekannt)\\b"),
            Pattern.compile("(?i)\\b(schätzung|geschätzt|schätzungsweise|ca\\.|circa|ungefähr|erwartet|voraussichtlich|noch\\s+offen|nicht\\s+bestätigt)\\b"),
            Pattern.compile("(?i)\\b(coming\\s+soon|not\\s+yet\\s+(available|released|priced)|to\\s+be\\s+(announced|confirmed)|tba|no\\s+price\\s+yet)\\b"),
            Pattern.compile("(?i)\\b(angekündigt|vorgestellt|auf\\s+der\\s+messe|teaser|prototyp|konzept)\\b")
    );

    // ── Evidence markers ──────────────────────────────────────────

    private static final List<Pattern> EVIDENCE_PATTERNS = List.of(
            Pattern.compile("(?i)\\b(quelle|source|beleg|nachweis|referenz)\\s*[:：]"),
            Pattern.compile("(?i)\\b(verifiziert|geprüft|überprüft|bestätigt|verified|confirmed)\\b"),
            Pattern.compile("(?i)\\b(status\\s*(200|ok)|http\\s*200)\\b"),
            Pattern.compile("(?i)\\blaut\\s+(dem|der|den)\\b"),
            Pattern.compile("https?://[^\\s\\]\\)]+")
    );

    // ── Metrics ───────────────────────────────────────────────────

    private int verifiedOutputs = 0;
    private int flaggedOutputs = 0;

    // ── Public API ────────────────────────────────────────────────

    /**
     * Analyze a text output for unsupported factual claims.
     *
     * @param output the LLM-generated text (chat/tool/planner output)
     * @return a {@link ClaimVerification} with {@code needsRethink} plus the
     *         list of flagged claim fragments (empty when nothing flagged)
     */
    public ClaimVerification verify(String output) {
        if (output == null || output.isBlank()) {
            verifiedOutputs++;
            return ClaimVerification.clean();
        }

        boolean hasAssertion = hasAnyMatch(output, AVAILABILITY_PATTERNS)
                || PRICE_PATTERN.matcher(output).find()
                || CERTAINTY_PATTERN.matcher(output).find();

        if (!hasAssertion) {
            verifiedOutputs++;
            return ClaimVerification.clean();
        }

        boolean hedged = hasAnyMatch(output, HEDGE_PATTERNS);
        boolean evidenced = hasAnyMatch(output, EVIDENCE_PATTERNS);

        if (hedged || evidenced) {
            verifiedOutputs++;
            return ClaimVerification.clean();
        }

        // Assertion without hedge and without evidence → re-think signal.
        flaggedOutputs++;
        List<String> fragments = extractFragments(output);
        String reason = "Behauptung (Verfügbarkeit/Preis) ohne Beleg oder "
                + "Unsicherheits-Markierung — vor Ausgabe neu prüfen.";
        LOG.warning(() -> "ClaimVerifier ⚠️ unsupported claim detected: "
                + String.join(" | ", fragments));
        return new ClaimVerification(true, fragments, reason);
    }

    /** Convenience: was the output clean (no re-think needed)? */
    public boolean isClean(String output) {
        return !verify(output).needsRethink();
    }

    public int verifiedOutputs() { return verifiedOutputs; }
    public int flaggedOutputs() { return flaggedOutputs; }
    public double flagRate() {
        int total = verifiedOutputs + flaggedOutputs;
        return total == 0 ? 0.0 : (double) flaggedOutputs / total;
    }

    // ── Helpers ───────────────────────────────────────────────────

    private static boolean hasAnyMatch(String text, List<Pattern> patterns) {
        for (Pattern p : patterns) {
            if (p.matcher(text).find()) return true;
        }
        return false;
    }

    /** Extract up to 4 short context snippets around matched assertions. */
    private static List<String> extractFragments(String text) {
        List<String> fragments = new ArrayList<>();
        Matcher m = PRICE_PATTERN.matcher(text);
        while (m.find() && fragments.size() < 4) {
            fragments.add(snippet(text, m.start(), m.end()));
        }
        for (Pattern p : AVAILABILITY_PATTERNS) {
            Matcher am = p.matcher(text);
            if (am.find() && fragments.size() < 4) {
                fragments.add(snippet(text, am.start(), am.end()));
            }
        }
        if (fragments.isEmpty() && CERTAINTY_PATTERN.matcher(text).find()) {
            Matcher cm = CERTAINTY_PATTERN.matcher(text);
            if (cm.find()) fragments.add(snippet(text, cm.start(), cm.end()));
        }
        return fragments;
    }

    private static String snippet(String text, int start, int end) {
        int s = Math.max(0, start - 24);
        int e = Math.min(text.length(), end + 24);
        String snip = text.substring(s, e).replace('\n', ' ').strip();
        return (s > 0 ? "…" : "") + snip + (e < text.length() ? "…" : "");
    }

    // ── Result record ─────────────────────────────────────────────

    /**
     * Verification result.
     *
     * @param needsRethink true when an unsupported factual claim was found
     * @param flaggedClaims short context snippets of the flagged claims
     * @param reason        human-readable explanation
     */
    public record ClaimVerification(boolean needsRethink,
                                    List<String> flaggedClaims,
                                    String reason) {
        static ClaimVerification clean() {
            return new ClaimVerification(false, List.of(), "OK");
        }
    }
}
