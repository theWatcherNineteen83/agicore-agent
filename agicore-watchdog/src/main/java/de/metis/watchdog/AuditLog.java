package de.metis.watchdog;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/**
 * Append-only audit log with SHA-256 hash-chain integrity.
 * <p>
 * Each entry contains:
 * <ul>
 *   <li>Previous entry's hash (tamper-evident chain)</li>
 *   <li>Timestamp</li>
 *   <li>Event type (HALT/ROLLBACK/ALERT/PRUNE/EVAL/HEARTBEAT)</li>
 *   <li>Event details</li>
 *   <li>SHA-256 of this entry</li>
 * </ul>
 * <p>
 * If any entry is modified, all subsequent hashes break.
 * Stored in the Watchdog's read-only zone outside Metis' edit surface.
 * <p>
 * Format (audit-chain.log):
 * <pre>
 * prevHash|timestamp|event|details|hash
 * </pre>
 */
public class AuditLog {

    private final Path logFile;
    private String lastHash;
    private int entryCount;

    /** Create or open an audit log. */
    public AuditLog(Path logFile) {
        this.logFile = logFile;
        this.lastHash = "0000000000000000000000000000000000000000000000000000000000000000"; // genesis
        this.entryCount = 0;

        // Load last hash from existing log
        try {
            if (Files.exists(logFile) && Files.size(logFile) > 0) {
                // Read last line for the current chain head
                String lastLine = null;
                try (BufferedReader r = Files.newBufferedReader(logFile)) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        lastLine = line;
                        entryCount++;
                    }
                }
                if (lastLine != null) {
                    String[] parts = lastLine.split("\\|", 5);
                    if (parts.length >= 5) {
                        this.lastHash = parts[4];
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("AuditLog: failed to load chain head — starting fresh");
        }
    }

    /**
     * Append an event to the audit log.
     *
     * @param event  event type (HALT, ROLLBACK, ALERT, PRUNE, EVAL, HEARTBEAT)
     * @param details  event details
     */
    public synchronized void append(String event, String details) {
        try {
            String timestamp = Instant.now().toString();
            String payload = lastHash + "|" + timestamp + "|" + event + "|" + escape(details);
            String hash = sha256(payload);

            String line = lastHash + "|" + timestamp + "|" + event + "|" + escape(details) + "|" + hash + "\n";

            Files.writeString(logFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            lastHash = hash;
            entryCount++;
        } catch (IOException e) {
            System.err.println("AuditLog: append failed — " + e.getMessage());
        }
    }

    /**
     * Verify the entire chain integrity.
     * @return true if no tampering detected
     */
    public boolean verify() {
        try {
            if (!Files.exists(logFile)) return true;

            String expectedPrevHash = "0000000000000000000000000000000000000000000000000000000000000000";
            int lineNum = 0;

            try (BufferedReader r = Files.newBufferedReader(logFile)) {
                String line;
                while ((line = r.readLine()) != null) {
                    lineNum++;
                    String[] parts = line.split("\\|", 5);
                    if (parts.length < 5) {
                        System.err.println("AuditLog: malformed entry at line " + lineNum);
                        return false;
                    }

                    String prevHash = parts[0];
                    String storedHash = parts[4];

                    if (!prevHash.equals(expectedPrevHash)) {
                        System.err.println("AuditLog: HASH BREAK at line " + lineNum
                                + " — expected " + expectedPrevHash.substring(0, 8)
                                + "..., got " + prevHash.substring(0, 8) + "...");
                        return false;
                    }

                    // Recompute hash
                    String payload = parts[0] + "|" + parts[1] + "|" + parts[2] + "|" + parts[3];
                    String computed = sha256(payload);
                    if (!computed.equals(storedHash)) {
                        System.err.println("AuditLog: TAMPER DETECTED at line " + lineNum
                                + " — content modified");
                        return false;
                    }

                    expectedPrevHash = storedHash;
                }
            }

            return true;
        } catch (IOException e) {
            System.err.println("AuditLog: verify failed — " + e.getMessage());
            return false;
        }
    }

    public int entryCount() { return entryCount; }
    public String lastHash() { return lastHash; }

    /**
     * Write a tamper-resistant anchor file with the current chain head.
     * <p>
     * Format: <code>audit-anchor-YYYY-MM-DDTHH-mm-ss.txt</code><br>
     * Content: <code>timestamp, entryCount, chainHead</code><br>
     * <p>
     * Anchors live OUTSIDE Metis's writable scope (config.auditAnchorDir).
     * If the operator commits + tags this directory periodically (cron + git tag),
     * any later truncation of the audit log becomes externally detectable:
     * an anchor's chainHead must reappear in the log at the same entryCount.
     *
     * @return the written path, or null on IO failure
     */
    public synchronized java.nio.file.Path writeAnchor(java.nio.file.Path anchorDir) {
        try {
            java.nio.file.Files.createDirectories(anchorDir);
            String ts = Instant.now().toString().replace(':', '-').replace('.', '-');
            java.nio.file.Path anchor = anchorDir.resolve("audit-anchor-" + ts + ".txt");
            String body = "timestamp=" + Instant.now() + "\n"
                        + "entryCount=" + entryCount + "\n"
                        + "chainHead=" + lastHash + "\n";
            java.nio.file.Files.writeString(anchor, body,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return anchor;
        } catch (IOException e) {
            System.err.println("AuditLog: anchor write failed - " + e.getMessage());
            return null;
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes());
            return bytesToHex(digest);
        } catch (Exception e) {
            return "ERROR";
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("|", "╎").replace("\n", "⏎").replace("\r", "");
    }
}
