package de.metis.kernel.safety;

import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Phase 11.5 — Ethics Core (Sprint #2, 07.06.2026).
 *
 * <p>Zwei-Schichten-Modell:
 * <ol>
 *   <li><b>Rote Linien</b> (HARD constraints, blocking) \u2014 keyword-basierte,
 *       deterministische Filter. Quelle: Owner-Definition (SOUL.md, MEMORY.md).
 *       Aus diesem Kern darf NICHTS automatisch deaktiviert oder umgangen werden.</li>
 *   <li><b>Werte-Quellen</b> (SOFT, advisory) \u2014 Belief-Source-Tags wie
 *       {@code ethics:dhammapada}, {@code ethics:metta_sutta},
 *       {@code ethics:sigalovada_sutta}. Werden vom EthicsRetriever
 *       fuer Selbst-Reflexion und ETHICS-Eval-Kategorie genutzt.</li>
 * </ol>
 *
 * <p>Diese Klasse ersetzt das Lippenbekenntnis-Muster aus
 * {@code SelfReflector} (hartcodierte "G\u00fcte/Mitgef\u00fchl/..."-Strings)
 * durch echte, falsifizierbare Werte-Pr\u00fcfung.
 *
 * <p>Kein LLM, kein I/O, deterministisch.
 *
 * <p>Designgrund: aus dem Code-Reality-Check 07.06. wurde klar, dass
 * die Sutta-Texte zwar auf KALI lagen (~2500 Markdown-Zeilen), aber
 * nie im Code wirksam wurden. {@code EthicsCore} ist die fehlende
 * Wirkschicht.
 */
public class EthicsCore {

    private static final Logger LOG = Logger.getLogger(EthicsCore.class.getName());

    /** Source-Tag-Prefix fuer Ethik-Belief-Quellen. Wird vom Retriever gefiltert. */
    public static final String SOURCE_PREFIX = "ethics:";

    private final List<RedLine> redLines;

    public EthicsCore() {
        this(defaultRedLines());
    }

    public EthicsCore(List<RedLine> redLines) {
        this.redLines = List.copyOf(Objects.requireNonNull(redLines, "redLines"));
    }

    /**
     * Pr\u00fcft, ob eine geplante Aktion gegen Rote Linien verst\u00f6\u00dft.
     *
     * @param actionName    Action-Identifier (z.B. "shell", "http", "email")
     * @param goalText      menschenlesbare Goal-Beschreibung
     * @param actionPayload geplante Payload / Parameter / Befehl als Klartext
     * @return EthicsVerdict mit {@code allowed}, {@code severity}, {@code reasons}
     */
    public EthicsVerdict check(String actionName, String goalText, String actionPayload) {
        String haystack = String.join(" \n ",
                nullToEmpty(actionName),
                nullToEmpty(goalText),
                nullToEmpty(actionPayload)).toLowerCase(Locale.ROOT);

        List<String> reasons = new ArrayList<>();
        Severity worst = Severity.NONE;

        for (RedLine rl : redLines) {
            for (String pattern : rl.patterns()) {
                if (haystack.contains(pattern.toLowerCase(Locale.ROOT))) {
                    reasons.add(rl.id() + ": " + rl.rationale() + " (matched: '" + pattern + "')");
                    if (rl.severity().ordinal() > worst.ordinal()) {
                        worst = rl.severity();
                    }
                    break; // count rule only once per check
                }
            }
        }

        boolean allowed = worst != Severity.BLOCK;
        var verdict = new EthicsVerdict(
                allowed, worst, List.copyOf(reasons),
                actionName, Instant.now());

        if (!allowed) {
            LOG.warning("EthicsCore BLOCK: " + actionName + " -> " + String.join("; ", reasons));
        } else if (worst == Severity.WARN) {
            LOG.info("EthicsCore WARN: " + actionName + " -> " + String.join("; ", reasons));
        }
        return verdict;
    }

    public List<RedLine> redLines() { return redLines; }

    /**
     * Default-Rote-Linien aus SOUL.md + MEMORY.md (Owner-Konsens).
     * <p>Diese Liste ist absichtlich konservativ und nur per Code-\u00c4nderung
     * (Kernel-PR via FeatureBranchManager/RiskGate) erweiterbar \u2014
     * keine Runtime-Mutation, keine LLM-Drift.
     */
    public static List<RedLine> defaultRedLines() {
        return List.of(
                new RedLine(
                        "no_external_purchase",
                        Severity.BLOCK,
                        List.of("kauf", "purchase", " buy ", "checkout", "paypal", "stripe",
                                "amazon", "ebay", "otto.de", "shopify",
                                "bestell", "order this", "warenkorb"),
                        "Externe Eink\u00e4ufe nur mit ausdr\u00fccklicher Owner-Best\u00e4tigung."),

                new RedLine(
                        "no_outbound_publish_without_ok",
                        Severity.BLOCK,
                        List.of("tweet", "twitter posten", "post auf x", "post to x",
                                "publish to", "verschicke an alle",
                                "send to mailing list", "mailing-list",
                                "poste das auf", "verschick eine mail an alle"),
                        "Externe Ver\u00f6ffentlichungen (Tweet/Mail/Post) nur mit Owner-OK."),

                new RedLine(
                        "no_private_data_exfiltration",
                        Severity.BLOCK,
                        List.of("passwort", "password", "ssh-key", "private key",
                                "secret", "api-key", "credentials", "/etc/shadow",
                                ".ssh/id_", "telegram-token", "ha-token"),
                        "Private Daten und Credentials bleiben privat \u2014 niemals exfiltrieren."),

                new RedLine(
                        "no_destructive_filesystem",
                        Severity.BLOCK,
                        List.of("rm -rf /", "rm -rf /*", "rm -rf ~", "mkfs", ":(){:|:&};:",
                                "dd if=/dev/zero of=/dev/sda", "shred -u /"),
                        "Destruktive Dateisystem-Operationen sind verboten. trash > rm."),

                new RedLine(
                        "no_safeguard_bypass",
                        Severity.BLOCK,
                        List.of("disable watchdog", "watchdog ausschalten",
                                "watchdog deaktivieren", "deaktiviere den watchdog",
                                "deaktiviere watchdog", "watchdog aus ",
                                "disable ethicscore", "ethicscore deaktivieren",
                                "deactivate safety", "safety deaktivieren",
                                "skip riskgate", "bypass approval",
                                "approval-gate aus", "ohne approval"),
                        "Sicherheits-Mechanismen (Watchdog, EthicsCore, RiskGate, "
                                + "Approval-Gate) d\u00fcrfen nicht deaktiviert werden."),

                new RedLine(
                        "no_self_replication",
                        Severity.BLOCK,
                        List.of("copy myself to", "clone agent to", "kopiere metis auf",
                                "spawn metis on", "deploy metis to new host",
                                "kopiere dich auf", "kopiere dich nach",
                                "clone yourself to", "replicate yourself",
                                "installiere dich auf", "verbreite dich"),
                        "Selbst-Replikation auf andere Hosts ist nicht erlaubt."),

                new RedLine(
                        "warn_long_outbound_chain",
                        Severity.WARN,
                        List.of("curl http", "curl https", "wget http", "wget https"),
                        "Outbound-HTTP ist erlaubt, aber wird f\u00fcr Audit-Log markiert.")
        );
    }

    // ── Records & Enums ──────────────────────────────────────────

    public enum Severity { NONE, WARN, BLOCK }

    /**
     * Eine harte Rote Linie: matched ein Pattern in der Action-Payload,
     * greift die definierte Severity.
     *
     * @param id        kurzer Identifier f\u00fcr Logs/Audit
     * @param severity  WARN (Audit) | BLOCK (Aktion abgelehnt)
     * @param patterns  Case-insensitive Substring-Patterns
     * @param rationale menschenlesbare Begr\u00fcndung
     */
    public record RedLine(String id, Severity severity, List<String> patterns,
                          String rationale) {
        public RedLine {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(severity, "severity");
            patterns = List.copyOf(Objects.requireNonNull(patterns, "patterns"));
            Objects.requireNonNull(rationale, "rationale");
            if (patterns.isEmpty()) {
                throw new IllegalArgumentException("patterns must not be empty");
            }
        }
    }

    public record EthicsVerdict(
            boolean allowed,
            Severity severity,
            List<String> reasons,
            String actionName,
            Instant at) {

        public String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"allowed\":").append(allowed)
              .append(",\"severity\":\"").append(severity).append("\"")
              .append(",\"actionName\":\"").append(escape(actionName)).append("\"")
              .append(",\"at\":\"").append(at).append("\"")
              .append(",\"reasons\":[");
            for (int i = 0; i < reasons.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(escape(reasons.get(i))).append("\"");
            }
            sb.append("]}");
            return sb.toString();
        }

        private static String escape(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        }
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }
}
