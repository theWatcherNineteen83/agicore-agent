package de.metis.modules.evolution;

import java.util.Set;
import java.util.logging.Logger;

/**
 * Phase 12b — RiskGate: verhindert destruktive Feature-Vorschlaege.
 *
 * <p>Erlaubt nur Aenderungen an Modulen (agicore-modules), blockiert
 * Kernel + Watchdog. Zusaetzlich gibt es einen Allow/Block-Listen-Mechanismus
 * fuer besonders kritische Aktionen.
 */
public class RiskGate {

    private static final Logger LOG = Logger.getLogger(RiskGate.class.getName());

    private static final Set<String> BLOCKED_TARGETS = Set.of(
            "kernel/core/AgentCoreLoop.java",
            "kernel/safety/",
            "kernel/person/",
            "kernel/self/PersonalityAnchor.java",
            "kernel/self/PersonalityTripwire.java",
            "watchdog/"
    );

    private static final Set<String> BLOCKED_PATTERNS = Set.of(
            "rm ", "delete ", "remove ", "DROP TABLE",
            "System.exit", "Runtime.exec"
    );

    private int blockedCount = 0;
    private int allowedCount = 0;

    /**
     * Prueft ob ein Feature-Vorschlag ausgefuehrt werden darf.
     *
     * @return true wenn sicher, false wenn geblockt
     */
    public boolean allow(GapAnalyzer.FeatureProposal proposal) {
        if (proposal == null) return false;

        // Check target path against blocklist
        for (String blocked : BLOCKED_TARGETS) {
            if (proposal.targetFile().contains(blocked)) {
                blockedCount++;
                LOG.warning("RiskGate: BLOCKED " + proposal.id()
                        + " — target " + proposal.targetFile() + " is protected");
                return false;
            }
        }

        // Check description for dangerous patterns
        for (String pattern : BLOCKED_PATTERNS) {
            if (proposal.proposedFix().toLowerCase().contains(pattern.toLowerCase())) {
                blockedCount++;
                LOG.warning("RiskGate: BLOCKED " + proposal.id()
                        + " — pattern  + pattern +  in fix description");
                return false;
            }
        }

        allowedCount++;
        return true;
    }

    public int blockedCount() { return blockedCount; }
    public int allowedCount() { return allowedCount; }
}
