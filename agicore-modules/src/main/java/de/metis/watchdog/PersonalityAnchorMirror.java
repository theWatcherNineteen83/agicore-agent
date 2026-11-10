package de.metis.watchdog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Mirrors the {@code PersonalityAnchor} file read-only using SHA-256 pinning.
 * <p>
 * The watchdog periodically recomputes the hash of the anchor file and compares
 * it against the expected value. If a mismatch is detected (tampering), a
 * tripwire alarm is raised and an entry is written to the audit log via
 * {@link java.util.logging.Logger}.
 * <p>
 * This class is thread-safe and designed for use within a scheduled watchdog loop.
 */
public class PersonalityAnchorMirror {

    private static final Logger LOG = Logger.getLogger(PersonalityAnchorMirror.class.getName());

    private static final String SHA_256 = "SHA-256";
    private static final String AUDIT_CATEGORY = "METIS_PERSONALITY_ANCHOR_TAMPER";

    private final Path anchorPath;
    private final String expectedSha256Hex;
    private volatile boolean tampered;
    private volatile Instant lastCheckInstant;

    /**
     * Constructs a new mirror for the given anchor file with the expected SHA-256 hash.
     *
     * @param anchorPath     the path to the PersonalityAnchor file
     * @param expectedSha256Hex the expected SHA-256 hex string of the anchor file
     */
    public PersonalityAnchorMirror(Path anchorPath, String expectedSha256Hex) {
        this.anchorPath = anchorPath;
        this.expectedSha256Hex = expectedSha256Hex.toLowerCase();
        this.tampered = false;
        this.lastCheckInstant = Instant.EPOCH;
    }

    /**
     * Performs a read-only integrity check of the anchor file.
     * <p>
     * If the file is missing, unreadable, or its hash does not match the expected value,
     * the mirror is marked as tampered and an audit log entry is emitted.
     *
     * @return {@code true} if the anchor file matches the expected hash, {@code false} otherwise
     */
    public boolean verify() {
        lastCheckInstant = Instant.now();

        if (!Files.exists(anchorPath)) {
            LOG.log(Level.SEVERE, "PersonalityAnchor file not found: {0}", anchorPath.toAbsolutePath());
            markTampered("FILE_MISSING");
            return false;
        }

        if (!Files.isReadable(anchorPath)) {
            LOG.log(Level.SEVERE, "PersonalityAnchor file is not readable: {0}", anchorPath.toAbsolutePath());
            markTampered("FILE_NOT_READABLE");
            return false;
        }

        String actualSha256Hex;
        try {
            byte[] fileBytes = Files.readAllBytes(anchorPath);
            actualSha256Hex = sha256Hex(fileBytes);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to read or hash PersonalityAnchor file: " + anchorPath.toAbsolutePath(), e);
            markTampered("READ_OR_HASH_ERROR");
            return false;
        }

        if (!expectedSha256Hex.equals(actualSha256Hex)) {
            markTampered("HASH_MISMATCH");
            return false;
        }

        // If we were previously tampered, but now match, we could reset, but for
        // security purposes, once tampered, it stays tampered until manual reset.
        // However, for a watchdog, we typically want to know if the current state is safe.
        // The requirement says "bei Tamper einen Tripwire-Alarm ... schreiben".
        // We keep the flag as a state indicator.
        return true;
    }

    /**
     * Returns {@code true} if the mirror has detected tampering at any point since
     * initialization or the last {@link #reset()} call.
     *
     * @return {@code true} if tampering has been detected
     */
    public boolean isTampered() {
        return tampered;
    }

    /**
     * Resets the tampered flag to {@code false}.
     * This should only be called after a verified repair of the anchor file.
     */
    public void reset() {
        this.tampered = false;
        LOG.info("PersonalityAnchorMirror tamper state reset.");
    }

    /**
     * Returns the path to the anchor file being monitored.
     *
     * @return the anchor file path
     */
    public Path getAnchorPath() {
        return anchorPath;
    }

    /**
     * Returns the last instant at which verification was performed.
     *
     * @return the last verification time
     */
    public Instant getLastCheckInstant() {
        return lastCheckInstant;
    }

    private void markTampered(String reason) {
        if (!tampered) {
            tampered = true;
            // Write to audit log via JUL with a structured message
            LOG.log(Level.SEVERE, 
                "TRIPWIRE_ALARM: PersonalityAnchor tamper detected. Reason: {0}. Path: {1}. ExpectedSHA256: {2}", 
                new Object[]{reason, anchorPath.toAbsolutePath(), expectedSha256Hex});
        } else {
            // Log subsequent checks as warnings to avoid log flooding
            LOG.log(Level.WARNING, 
                "PersonalityAnchor still tampered. Reason: {0}. Path: {1}.", 
                new Object[]{reason, anchorPath.toAbsolutePath()});
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            byte[] hash = digest.digest(data);
            StringBuilder hexString = new StringBuilder(2 * hash.length);
            for (byte b : hash) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(h);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}