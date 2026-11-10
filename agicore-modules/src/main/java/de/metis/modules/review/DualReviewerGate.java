package de.metis.modules.review;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.function.Predicate;

public class DualReviewerGate {

    private static final Logger LOG = Logger.getLogger(DualReviewerGate.class.getName());

    private final Predicate<String> modelAReviewer;
    private final Predicate<String> modelBReviewer;
    private final long timeoutMillis;

    public DualReviewerGate(Predicate<String> modelAReviewer, Predicate<String> modelBReviewer) {
        this(modelAReviewer, modelBReviewer, 5000L);
    }

    public DualReviewerGate(Predicate<String> modelAReviewer, Predicate<String> modelBReviewer, long timeoutMillis) {
        if (modelAReviewer == null) {
            throw new IllegalArgumentException("modelAReviewer cannot be null");
        }
        if (modelBReviewer == null) {
            throw new IllegalArgumentException("modelBReviewer cannot be null");
        }
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
        this.modelAReviewer = modelAReviewer;
        this.modelBReviewer = modelBReviewer;
        this.timeoutMillis = timeoutMillis;
    }

    public ReviewVerdict review(String mutation) {
        if (mutation == null) {
            throw new IllegalArgumentException("mutation cannot be null");
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<Boolean> futureA = null;
        Future<Boolean> futureB = null;
        try {
            futureA = executor.submit(() -> modelAReviewer.test(mutation));
            futureB = executor.submit(() -> modelBReviewer.test(mutation));

            Boolean resultA = futureA.get(timeoutMillis, TimeUnit.MILLISECONDS);
            Boolean resultB = futureB.get(timeoutMillis, TimeUnit.MILLISECONDS);

            if (resultA == null || resultB == null) {
                LOG.log(Level.WARNING, "One or both reviewers returned null result for mutation: {0}", mutation);
                return ReviewVerdict.reject("Null result from reviewer(s)");
            }

            if (resultA && resultB) {
                LOG.log(Level.FINE, "Both reviewers approved mutation: {0}", mutation);
                return ReviewVerdict.approve();
            } else if (!resultA && !resultB) {
                LOG.log(Level.FINE, "Both reviewers rejected mutation: {0}", mutation);
                return ReviewVerdict.reject("Both reviewers rejected");
            } else {
                LOG.log(Level.WARNING, "Reviewer disagreement for mutation: {0}. A={1}, B={2}", 
                    new Object[]{mutation, resultA, resultB});
                return ReviewVerdict.reject("Reviewer disagreement: " + (resultA ? "A approved, B rejected" : "A rejected, B approved"));
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Error during dual review for mutation: " + mutation, e);
            return ReviewVerdict.reject("Review error: " + e.getMessage());
        } finally {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                    LOG.log(Level.WARNING, "Executor did not terminate promptly");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.log(Level.WARNING, "Interrupted while waiting for executor shutdown", e);
            }
        }
    }

    public boolean gate(String mutation) {
        ReviewVerdict verdict = review(mutation);
        return verdict.isApproved();
    }

    public static class ReviewVerdict {
        private final boolean approved;
        private final String reason;

        private ReviewVerdict(boolean approved, String reason) {
            this.approved = approved;
            this.reason = reason;
        }

        public static ReviewVerdict approve() {
            return new ReviewVerdict(true, "Approved by both reviewers");
        }

        public static ReviewVerdict reject(String reason) {
            return new ReviewVerdict(false, reason);
        }

        public boolean isApproved() {
            return approved;
        }

        public String getReason() {
            return reason;
        }

        @Override
        public String toString() {
            return "ReviewVerdict{approved=" + approved + ", reason='" + reason + "'}";
        }
    }
}