package de.metis.modules.review;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class DualReviewer {

    private static final Logger LOG = Logger.getLogger(DualReviewer.class.getName());

    public static final String ACCEPT = "ACCEPT";
    public static final String REJECT = "REJECT";

    private final Reviewer reviewerA;
    private final Reviewer reviewerB;

    public DualReviewer(Reviewer reviewerA, Reviewer reviewerB) {
        if (reviewerA == null || reviewerB == null) {
            throw new IllegalArgumentException("Reviewers must not be null");
        }
        this.reviewerA = reviewerA;
        this.reviewerB = reviewerB;
    }

    public ReviewResult evaluate(String generatedCode) {
        if (generatedCode == null || generatedCode.trim().isEmpty()) {
            LOG.warning("Received null or empty code for review. Defaulting to REJECT.");
            return ReviewResult.reject("Code was null or empty.", "Code was null or empty.");
        }

        LOG.info("Initiating Dual Review process. Code length: " + generatedCode.length());

        try {
            Verdict verdictA = reviewerA.review(generatedCode);
            Verdict verdictB = reviewerB.review(generatedCode);

            LOG.info("Review A (Functional) Verdict: " + verdictA.verdict + " - Reason: " + verdictA.reason);
            LOG.info("Review B (Strict/Evidence) Verdict: " + verdictB.verdict + " - Reason: " + verdictB.reason);

            boolean isAApproved = verdictA.verdict.equalsIgnoreCase(ACCEPT);
            boolean isBApproved = verdictB.verdict.equalsIgnoreCase(ACCEPT);

            if (isAApproved && isBApproved) {
                String combinedReason = "Both reviews passed. Functional integrity confirmed and no critical evidence gaps found.";
                LOG.info("Final Decision: ACCEPT");
                return ReviewResult.accept(combinedReason);
            } else {
                String reasonA = verdictA.reason;
                String reasonB = verdictB.reason;
                
                if (reasonA == null || reasonA.trim().isEmpty()) {
                    reasonA = "No specific reason provided.";
                }
                if (reasonB == null || reasonB.trim().isEmpty()) {
                    reasonB = "No specific reason provided.";
                }

                String combinedReason = "REJECTED. \n[Review A] " + reasonA + "\n[Review B] " + reasonB;
                LOG.info("Final Decision: REJECT");
                return ReviewResult.reject(combinedReason);
            }
        } catch (Exception e) {
            LOG.severe("Exception during dual review: " + e.getMessage());
            return ReviewResult.reject("Internal error during review: " + e.getMessage());
        }
    }

    public static class ReviewResult {
        private final boolean accepted;
        private final String reason;

        private ReviewResult(boolean accepted, String reason) {
            this.accepted = accepted;
            this.reason = reason;
        }

        public static ReviewResult accept(String reason) {
            return new ReviewResult(true, reason);
        }

        public static ReviewResult reject(String reason) {
            return new ReviewResult(false, reason);
        }

        public static ReviewResult reject(String reasonA, String reasonB) {
            return new ReviewResult(false, "A: " + reasonA + " | B: " + reasonB);
        }

        public boolean isAccepted() {
            return accepted;
        }

        public String getReason() {
            return reason;
        }

        public String getVerdictString() {
            return accepted ? ACCEPT : REJECT;
        }

        @Override
        public String toString() {
            return "ReviewResult{accepted=" + accepted + ", reason='" + reason + "'}";
        }
    }

    public interface Reviewer {
        Verdict review(String code) throws Exception;
    }

    public static class Verdict {
        public final String verdict;
        public final String reason;

        public Verdict(String verdict, String reason) {
            this.verdict = verdict;
            this.reason = reason;
        }

        public static Verdict accept(String reason) {
            return new Verdict(ACCEPT, reason);
        }

        public static Verdict reject(String reason) {
            return new Verdict(REJECT, reason);
        }
    }
}