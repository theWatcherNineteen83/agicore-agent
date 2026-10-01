package de.metis.modules.checkpoint;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * HumanCheckpoint implements the single-goal approval mechanism for user goals
 * currently in the 'ZU_TESTEN' (Pending Test) status.
 *
 * <p>This class ensures that when a goal is approved (freigegeben), only the
 * specific goal identified by the provided ID is transitioned to 'FERTIG'
 * (Done), while all other goals in the 'ZU_TESTEN' status remain unaffected.
 * It also creates a release comment associated with the specific goal ID.</p>
 */
public class HumanCheckpoint {

    private static final Logger LOG = Logger.getLogger(HumanCheckpoint.class.getName());

    /**
     * Represents the status of a goal in the Metis AGI workflow.
     */
    public enum GoalStatus {
        ZU_TESTEN, // Pending Test
        FERTIG,    // Done/Approved
        ABGELEHNT, // Rejected (optional for context)
        IN_ARBEIT  // In Progress (context)
    }

    /**
     * Represents a User Goal in the system.
     */
    public static class Goal {
        private final String id;
        private final String description;
        private GoalStatus status;

        public Goal(String id, String description, GoalStatus status) {
            this.id = Objects.requireNonNull(id, "Goal ID cannot be null");
            this.description = Objects.requireNonNull(description, "Goal description cannot be null");
            this.status = Objects.requireNonNull(status, "Goal status cannot be null");
        }

        public String getId() {
            return id;
        }

        public String getDescription() {
            return description;
        }

        public GoalStatus getStatus() {
            return status;
        }

        public void setStatus(GoalStatus status) {
            if (this.status == status) {
                return;
            }
            // Simulate a transition log
            LOG.fine("Transitioning Goal " + id + " from " + this.status + " to " + status);
            this.status = status;
        }

        @Override
        public String toString() {
            return "Goal{id='" + id + "', description='" + description + "', status=" + status + "}";
        }
    }

    /**
     * Represents a comment attached to a goal, used for audit trails.
     */
    public static class Comment {
        private final String goalId;
        private final String content;
        private final UUID commentId;

        public Comment(String goalId, String content) {
            this.goalId = Objects.requireNonNull(goalId);
            this.content = Objects.requireNonNull(content);
            this.commentId = UUID.randomUUID();
        }

        public String getGoalId() {
            return goalId;
        }

        public String getContent() {
            return content;
        }

        public UUID getCommentId() {
            return commentId;
        }

        @Override
        public String toString() {
            return "Comment{goalId='" + goalId + "', content='" + content + "', id=" + commentId + "}";
        }
    }

    /**
     * Simulated repository/store for goals. In a real implementation, this would
     * connect to a database. Here we use an in-memory list for demonstration.
     */
    private final List<Goal> goalRepository;
    private final List<Comment> commentRepository;

    public HumanCheckpoint(List<Goal> goals) {
        this.goalRepository = Objects.requireNonNull(goals);
        this.commentRepository = new java.util.ArrayList<>();
    }

    /**
     * Approves a single goal.
     *
     * <p>Only the goal with the provided ID is transitioned to 'FERTIG'.
     * All other goals, including those in 'ZU_TESTEN' status, remain unchanged.
     * A release comment is added to the comment repository for the approved goal.</p>
     *
     * @param goalId the unique identifier of the goal to approve
     * @throws IllegalArgumentException if the goalId is null or empty
     * @throws IllegalStateException    if no goal with the given ID is found
     * @throws IllegalStateException    if the goal is not in 'ZU_TESTEN' status
     */
    public void freigegeben(String goalId) {
        if (goalId == null || goalId.isEmpty()) {
            throw new IllegalArgumentException("Goal ID must not be null or empty");
        }

        LOG.info("Processing single-goal approval for Goal ID: " + goalId);

        // Find the specific goal
        Goal targetGoal = null;
        for (Goal goal : goalRepository) {
            if (goalId.equals(goal.getId())) {
                targetGoal = goal;
                break;
            }
        }

        if (targetGoal == null) {
            LOG.severe("Goal with ID " + goalId + " not found in repository.");
            throw new IllegalStateException("Goal with ID " + goalId + " not found");
        }

        // Check status
        if (targetGoal.getStatus() != GoalStatus.ZU_TESTEN) {
            LOG.warning("Goal " + goalId + " is not in ZU_TESTEN status (current: " + targetGoal.getStatus() + "). Approval skipped.");
            throw new IllegalStateException("Goal " + goalId + " is not in ZU_TESTEN status. Cannot approve.");
        }

        // Perform the transition: ONLY this goal
        targetGoal.setStatus(GoalStatus.FERTIG);

        // Write the release comment with the goal ID
        String commentContent = "Freigabe bestätigt für Goal ID: " + goalId;
        Comment releaseComment = new Comment(goalId, commentContent);
        commentRepository.add(releaseComment);

        LOG.info("Goal " + goalId + " successfully approved and transitioned to FERTIG. Comment ID: " + releaseComment.getCommentId());
    }

    /**
     * Returns a copy of the current goal repository for inspection.
     *
     * @return unmodifiable list of goals
     */
    public List<Goal> getGoals() {
        return java.util.Collections.unmodifiableList(goalRepository);
    }

    /**
     * Returns a copy of the comment repository for inspection.
     *
     * @return unmodifiable list of comments
     */
    public List<Comment> getComments() {
        return java.util.Collections.unmodifiableList(commentRepository);
    }
}