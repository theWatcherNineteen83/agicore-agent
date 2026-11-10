package de.metis.modules.checkpoint;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Enforces explicit human approval from a designated authority (Georg) via Telegram for any
 * kernel or safety-related modifications. Operates in a fail-closed manner: if approval is
 * not explicitly granted within the configured timeout, the operation is denied.
 */
public class HumanCheckpointGate {

    private static final Logger LOG = Logger.getLogger(HumanCheckpointGate.class.getName());

    /**
     * The unique identifier of the authorized approver (Georg).
     */
    private static final String AUTHORIZED_USER_ID = "georg";

    /**
     * The timeout duration in seconds for which an approval request remains valid.
     */
    private final long approvalTimeoutSeconds;

    /**
     * Storage for pending approval requests. Maps checkpoint IDs to their corresponding state.
     */
    private final ConcurrentHashMap<String, CheckpointState> pendingRequests = new ConcurrentHashMap<>();

    /**
     * Constructs a new HumanCheckpointGate with the specified timeout.
     *
     * @param approvalTimeoutSeconds the duration in seconds an approval request remains valid
     * @throws IllegalArgumentException if timeout is less than or equal to zero
     */
    public HumanCheckpointGate(long approvalTimeoutSeconds) {
        if (approvalTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("Approval timeout must be positive");
        }
        this.approvalTimeoutSeconds = approvalTimeoutSeconds;
    }

    /**
     * Requests approval for a specific checkpoint. This simulates sending a request via Telegram.
     * In a production environment, this would integrate with the Telegram Bot API.
     *
     * @param checkpointId the unique identifier for the checkpoint
     * @param description  a human-readable description of the change
     * @return a string token representing the pending request ID
     */
    public String requestApproval(String checkpointId, String description) {
        if (checkpointId == null || checkpointId.isBlank()) {
            throw new IllegalArgumentException("Checkpoint ID cannot be null or blank");
        }

        // Simulate sending a message to Telegram (in real implementation, this would call the API)
        LOG.info("Simulating Telegram approval request to Georg for checkpoint: " + checkpointId);
        LOG.info("Description: " + description);

        CheckpointState state = new CheckpointState(checkpointId, System.currentTimeMillis());
        pendingRequests.put(checkpointId, state);
        
        // Return a token that could be used for verification
        return checkpointId;
    }

    /**
     * Simulates the receipt of an approval response from the authorized user (Georg).
     *
     * @param checkpointId the ID of the checkpoint being approved
     * @param approverId   the ID of the user providing the approval
     * @param approved     whether the user approved the change
     */
    public void receiveTelegramResponse(String checkpointId, String approverId, boolean approved) {
        CheckpointState state = pendingRequests.get(checkpointId);
        if (state == null) {
            LOG.warning("Received response for unknown or already processed checkpoint: " + checkpointId);
            return;
        }

        // Verify the approver is authorized
        if (!AUTHORIZED_USER_ID.equalsIgnoreCase(approverId)) {
            LOG.warning("Unauthorized approval attempt for checkpoint " + checkpointId + " by user: " + approverId);
            state.markDenied();
            return;
        }

        // Check if the request is still valid (not expired)
        if (state.isExpired(approvalTimeoutSeconds)) {
            LOG.warning("Approval response for checkpoint " + checkpointId + " is too late. Request expired.");
            state.markDenied();
            return;
        }

        if (approved) {
            state.markApproved();
            LOG.info("Checkpoint " + checkpointId + " explicitly approved by Georg.");
        } else {
            state.markDenied();
            LOG.info("Checkpoint " + checkpointId + " explicitly denied by Georg.");
        }
    }

    /**
     * Checks if a checkpoint has been approved. This method is fail-closed:
     * it returns false if the checkpoint is pending, expired, denied, or unknown.
     *
     * @param checkpointId the ID of the checkpoint to check
     * @return true only if the checkpoint was explicitly approved by the authorized user within the timeout
     */
    public boolean isApproved(String checkpointId) {
        CheckpointState state = pendingRequests.get(checkpointId);
        if (state == null) {
            LOG.fine("No checkpoint state found for: " + checkpointId);
            return false;
        }

        // Fail closed: if expired, treat as denied
        if (state.isExpired(approvalTimeoutSeconds)) {
            state.markExpired();
            LOG.warning("Checkpoint " + checkpointId + " approval request expired. Failing closed.");
            return false;
        }

        return state.isApproved();
    }

    /**
     * Cleans up expired checkpoint states to prevent memory leaks.
     */
    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        long timeoutMillis = approvalTimeoutSeconds * 1000;
        
        pendingRequests.entrySet().removeIf(entry -> {
            CheckpointState state = entry.getValue();
            if (state.isExpired(approvalTimeoutSeconds)) {
                state.markExpired();
                LOG.fine("Cleaning up expired checkpoint state: " + entry.getKey());
                return true;
            }
            return false;
        });
    }

    /**
     * Internal state holder for a checkpoint request.
     */
    private static class CheckpointState {
        private final String checkpointId;
        private final long timestamp;
        private volatile boolean approved;
        private volatile boolean denied;
        private volatile boolean expired;

        CheckpointState(String checkpointId, long timestamp) {
            this.checkpointId = checkpointId;
            this.timestamp = timestamp;
            this.approved = false;
            this.denied = false;
            this.expired = false;
        }

        boolean isExpired(long timeoutSeconds) {
            long elapsed = System.currentTimeMillis() - timestamp;
            return elapsed > (timeoutSeconds * 1000);
        }

        boolean isApproved() {
            return approved && !denied && !expired;
        }

        void markApproved() {
            this.approved = true;
        }

        void markDenied() {
            this.denied = true;
        }

        void markExpired() {
            this.expired = true;
        }
    }
}