package de.metis.modules.selfrefactor;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PhaseCompletionEvaluator {

    private static final Logger LOG = Logger.getLogger(PhaseCompletionEvaluator.class.getName());

    private final String phaseId;
    private final String description;
    private final List<String> requiredArtifacts;
    private final List<Pattern> requiredMarkers;
    private final long minArtifactFileSize;

    public PhaseCompletionEvaluator(String phaseId, String description, List<String> requiredArtifacts, List<Pattern> requiredMarkers, long minArtifactFileSize) {
        this.phaseId = phaseId;
        this.description = description;
        this.requiredArtifacts = requiredArtifacts;
        this.requiredMarkers = requiredMarkers;
        this.minArtifactFileSize = minArtifactFileSize;
    }

    /**
     * Evaluates whether the specified phase is considered "DONE" based on the provided evidence.
     *
     * @param evidencePath The path to a text file containing the evidence or log output.
     * @return True if all completion criteria are met, false otherwise.
     */
    public boolean evaluate(String evidencePath) {
        if (evidencePath == null || evidencePath.trim().isEmpty()) {
            LOG.log(Level.WARNING, "Evidence path is null or empty for phase {0}. Cannot evaluate.", phaseId);
            return false;
        }

        LOG.log(Level.FINE, "Starting evaluation for Phase: {0} - {1}", new Object[]{phaseId, description});

        // Check 1: Required Markers must be present in the evidence
        boolean markersPresent = checkMarkers(evidencePath);
        if (!markersPresent) {
            LOG.log(Level.INFO, "Phase {0}: Failed marker check.", phaseId);
            return false;
        }

        // Check 2: Required Artifacts must exist and meet minimum size
        boolean artifactsValid = checkArtifacts();
        if (!artifactsValid) {
            LOG.log(Level.INFO, "Phase {0}: Failed artifact check.", phaseId);
            return false;
        }

        LOG.log(Level.INFO, "Phase {0} ({1}) evaluated as DONE.", new Object[]{phaseId, description});
        return true;
    }

    private boolean checkMarkers(String evidencePath) {
        try {
            StringBuilder content = new StringBuilder();
            try (InputStream is = new java.io.FileInputStream(evidencePath);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append('\n');
                }
            }

            String text = content.toString();
            boolean allFound = true;
            for (Pattern marker : requiredMarkers) {
                Matcher m = marker.matcher(text);
                if (!m.find()) {
                    LOG.log(Level.WARNING, "Phase {0}: Required marker not found: {1}", new Object[]{phaseId, marker.pattern()});
                    allFound = false;
                    break;
                }
            }
            return allFound;

        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to read evidence file: " + evidencePath, e);
            return false;
        }
    }

    private boolean checkArtifacts() {
        if (requiredArtifacts == null || requiredArtifacts.isEmpty()) {
            return true;
        }

        for (String artifact : requiredArtifacts) {
            java.io.File file = new java.io.File(artifact);
            if (!file.exists() || !file.isFile()) {
                LOG.log(Level.WARNING, "Phase {0}: Required artifact not found: {1}", new Object[]{phaseId, artifact});
                return false;
            }

            if (minArtifactFileSize > 0 && file.length() < minArtifactFileSize) {
                LOG.log(Level.WARNING, "Phase {0}: Artifact {1} is smaller than minimum size {2}.",
                        new Object[]{phaseId, artifact, minArtifactFileSize});
                return false;
            }
        }
        return true;
    }

    public String getPhaseId() {
        return phaseId;
    }

    public String getDescription() {
        return description;
    }
}