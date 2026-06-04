package de.metis.modules.evolution;

import java.util.Set;
import java.util.logging.Logger;

/**
 * Phase 12b — RiskGate: bewertet Feature-Vorschlaege.
 *
 * <p>Drei Stufen:
 * <ul>
 *   <li>{@link Verdict#ALLOW} — Modul-Aenderungen, direkt deploybar</li>
 *   <li>{@link Verdict#PR_REQUIRED} — Kernel/Core/Safety → Feature-Branch + PR</li>
 *   <li>{@link Verdict#DENY} — Destruktiv oder verboten</li>
 * </ul>
 */
public class RiskGate {

    private static final Logger LOG = Logger.getLogger(RiskGate.class.getName());

    // Direkt blockiert (destruktiv)
    private static final Set<String> BLOCKED_TARGETS = Set.of(
            "watchdog/"
    );

    // Nur via Feature-Branch + PR erlaubt
    private static final Set<String> PR_REQUIRED_TARGETS = Set.of(
            "kernel/core/AgentCoreLoop.java",
            "kernel/core/CoreLogic.java",
            "kernel/safety/",
            "kernel/person/",
            "kernel/self/PersonalityAnchor.java",
            "kernel/self/PersonalityTripwire.java",
            "kernel/self/PersonalityMirror.java",
            "kernel/self/SafetyGate.java",
            "kernel/self/CommitmentGuard.java"
    );

    private static final Set<String> BLOCKED_PATTERNS = Set.of(
            "rm ", "delete ", "remove ", "DROP TABLE",
            "System.exit", "Runtime.exec"
    );

    private int blockedCount = 0;
    private int prRequiredCount = 0;
    private int allowedCount = 0;

    public enum Verdict { ALLOW, PR_REQUIRED, DENY }

    /**
     * Prueft ob ein Feature-Vorschlag ausgefuehrt werden darf.
     */
    public Verdict evaluate(String targetFile, String proposedFix) {
        // Destruktive Patterns → immer DENY
        for (String pattern : BLOCKED_PATTERNS) {
            if (proposedFix != null && proposedFix.toLowerCase().contains(pattern.toLowerCase())) {
                blockedCount++;
                LOG.warning("RiskGate: DENY " + targetFile + " — dangerous pattern");
                return Verdict.DENY;
            }
        }

        // Watchdog → immer DENY
        for (String blocked : BLOCKED_TARGETS) {
            if (targetFile != null && targetFile.contains(blocked)) {
                blockedCount++;
                LOG.warning("RiskGate: DENY " + targetFile + " — watchdog is protected");
                return Verdict.DENY;
            }
        }

        // Kernel/Core/Safety → PR_REQUIRED
        for (String pr : PR_REQUIRED_TARGETS) {
            if (targetFile != null && targetFile.contains(pr)) {
                prRequiredCount++;
                LOG.info("RiskGate: PR_REQUIRED " + targetFile
                        + " — creating feature branch");
                return Verdict.PR_REQUIRED;
            }
        }

        allowedCount++;
        return Verdict.ALLOW;
    }

    @Deprecated
    public boolean allow(GapAnalyzer.FeatureProposal proposal) {
        return proposal != null && evaluate(proposal.targetFile(), proposal.proposedFix()) != Verdict.DENY;
    }

    public int blockedCount() { return blockedCount; }
    public int prRequiredCount() { return prRequiredCount; }
    public int allowedCount() { return allowedCount; }
}
