package de.metis.modules.selfrefactor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Represents a concrete proposal for a self-mutation of the Metis AGI codebase.
 * Each proposal encapsulates the structural change (diff), the architectural rationale,
 * the calculated risk profile, and the exact set of modules affected by the change.
 */
public final class MutationProposal {

    private static final Logger LOG = Logger.getLogger(MutationProposal.class.getName());

    private final String proposalId;
    private final String changeDescription;
    private final String diff;
    private final String architecturalRationale;
    private final RiskProfile riskProfile;
    private final List<String> affectedModules;
    private final Instant timestamp;
    private final Status status;

    /**
     * Risk classification for a mutation proposal.
     */
    public enum RiskLevel {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    /**
     * Lifecycle status of the mutation proposal.
     */
    public enum Status {
        PROPOSED,
        VALIDATED,
        REJECTED,
        APPLIED,
        ROLLED_BACK
    }

    /**
     * Structured risk assessment detailing the probability and impact of a mutation.
     *
     * @param level           The overall risk classification.
     * @param probability     The estimated probability (0.0 to 1.0) that the mutation introduces a regression.
     * @param impact          The estimated impact (0.0 to 1.0) on system stability if a regression occurs.
     * @param mitigationNotes Descriptive strategies to mitigate the identified risks.
     */
    public record RiskProfile(RiskLevel level, double probability, double impact, String mitigationNotes) {
        public RiskProfile {
            if (probability < 0.0 || probability > 1.0) {
                throw new IllegalArgumentException("Probability must be between 0.0 and 1.0");
            }
            if (impact < 0.0 || impact > 1.0) {
                throw new IllegalArgumentException("Impact must be between 0.0 and 1.0");
            }
            if (level == null) {
                throw new NullPointerException("Risk level cannot be null");
            }
        }

        /**
         * Calculates a normalized risk score from 0.0 (no risk) to 1.0 (maximum risk).
         *
         * @return The weighted risk score.
         */
        public double getRiskScore() {
            return probability * impact;
        }
    }

    /**
     * Constructs a new MutationProposal.
     *
     * @param proposalId              Unique identifier for the proposal.
     * @param changeDescription       Human-readable summary of the proposed change.
     * @param diff                    The unified diff representing the code change.
     * @param architecturalRationale  The architectural reasoning or specification reference for the change.
     * @param riskProfile             The calculated risk assessment.
     * @param affectedModules         List of module identifiers impacted by this mutation.
     */
    public MutationProposal(
            String proposalId,
            String changeDescription,
            String diff,
            String architecturalRationale,
            RiskProfile riskProfile,
            List<String> affectedModules) {

        if (proposalId == null || proposalId.isBlank()) {
            throw new IllegalArgumentException("Proposal ID cannot be null or blank");
        }
        if (changeDescription == null || changeDescription.isBlank()) {
            throw new IllegalArgumentException("Change description cannot be null or blank");
        }
        if (diff == null || diff.isBlank()) {
            throw new IllegalArgumentException("Diff content cannot be null or blank");
        }
        if (architecturalRationale == null || architecturalRationale.isBlank()) {
            throw new IllegalArgumentException("Architectural rationale cannot be null or blank");
        }
        if (riskProfile == null) {
            throw new IllegalArgumentException("Risk profile cannot be null");
        }
        if (affectedModules == null || affectedModules.isEmpty()) {
            throw new IllegalArgumentException("Affected modules list cannot be null or empty");
        }

        this.proposalId = proposalId;
        this.changeDescription = changeDescription;
        this.diff = diff;
        this.architecturalRationale = architecturalRationale;
        this.riskProfile = riskProfile;
        this.affectedModules = List.copyOf(affectedModules);
        this.timestamp = Instant.now();
        this.status = Status.PROPOSED;

        LOG.info("Created mutation proposal [" + proposalId + "] affecting " + affectedModules.size() + " modules with risk level " + riskProfile.level());
    }

    public String getProposalId() {
        return proposalId;
    }

    public String getChangeDescription() {
        return changeDescription;
    }

    public String getDiff() {
        return diff;
    }

    public String getArchitecturalRationale() {
        return architecturalRationale;
    }

    public RiskProfile getRiskProfile() {
        return riskProfile;
    }

    public List<String> getAffectedModules() {
        return affectedModules;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public Status getStatus() {
        return status;
    }

    /**
     * Returns a summary map of the proposal's key metadata.
     *
     * @return An unmodifiable map containing the proposal's summary information.
     */
    public Map<String, String> getSummary() {
        return Map.of(
                "proposalId", proposalId,
                "description", changeDescription,
                "riskLevel", riskProfile.level().name(),
                "affectedModules", String.join(", ", affectedModules),
                "status", status.name()
        );
    }

    @Override
    public String toString() {
        return "MutationProposal{" +
                "proposalId='" + proposalId + '\'' +
                ", changeDescription='" + changeDescription + '\'' +
                ", diff='" + diff + '\'' +
                ", architecturalRationale='" + architecturalRationale + '\'' +
                ", riskProfile=" + riskProfile +
                ", affectedModules=" + affectedModules +
                ", timestamp=" + timestamp +
                ", status=" + status +
                '}';
    }
}