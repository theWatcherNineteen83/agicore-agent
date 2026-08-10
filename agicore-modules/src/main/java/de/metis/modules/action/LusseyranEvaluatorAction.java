package de.metis.modules.action;

import de.metis.kernel.action.Action;
import de.metis.kernel.action.ActionResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.logging.Logger;

/**
 * Phase 13b — LusseyranEvaluatorAction.
 * <p>
 * Metis-Action die den VoiceFeatureExtractor-JSON-Output (Phase 13a)
 * durch den LusseyranEvaluator interpretiert.
 * <p>
 * Workflow: WAV → VoiceFeatureExtractor (13a) → JSON → LusseyranEvaluator (13b) → Sprecherprofil
 * <p>
 * Kombiniert beide Phasen in einer Action: Feature-Extraktion + Lusseyran-Interpretation.
 * Kann auch nur den Evaluator-Teil ausführen (wenn bereits Features vorliegen).
 * <p>
 * Approval: NOTIFY — read-only Analyse, wird geloggt.
 */
public class LusseyranEvaluatorAction implements Action {

    private static final Logger LOG = Logger.getLogger(LusseyranEvaluatorAction.class.getName());
    public static final String NAME = "lusseyran_evaluate";

    private final Path audioFile;
    private final String featureJson;  // optional: pre-extracted features
    private final LusseyranEvaluator evaluator;

    /**
     * Full pipeline: extract features from WAV, then evaluate.
     */
    public LusseyranEvaluatorAction(Path audioFile) {
        this.audioFile = audioFile;
        this.featureJson = null;
        this.evaluator = new LusseyranEvaluator();
    }

    /**
     * Evaluator-only: use pre-extracted features.
     */
    public LusseyranEvaluatorAction(String featureJson, LusseyranEvaluator evaluator) {
        this.audioFile = null;
        this.featureJson = featureJson;
        this.evaluator = evaluator;
    }

    @Override
    public String name() { return NAME; }

    @Override
    public String category() { return "analyze"; }

    @Override
    public ApprovalLevel approvalLevel() { return ApprovalLevel.NOTIFY; }

    @Override
    public ActionResult execute() {
        Instant start = Instant.now();

        try {
            // Step 1: Get features (either from file or pre-supplied)
            String features;
            if (featureJson != null && !featureJson.isBlank()) {
                features = featureJson;
                LOG.fine(() -> "LusseyranEvaluatorAction: using pre-extracted features ("
                        + features.length() + " bytes)");
            } else if (audioFile != null && Files.exists(audioFile)) {
                // Run Phase 13a VoiceFeatureAction inline
                VoiceFeatureAction vfa = new VoiceFeatureAction(audioFile);
                ActionResult featureResult = vfa.execute();
                if (!featureResult.success()) {
                    return ActionResult.fail(NAME,
                            "Feature extraction failed: " + featureResult.error(), start);
                }
                features = featureResult.body();
                LOG.fine(() -> "LusseyranEvaluatorAction: extracted features from "
                        + audioFile.getFileName() + " (" + features.length() + " bytes)");
            } else {
                return ActionResult.fail(NAME,
                        "No audio file or feature JSON provided", start);
            }

            // Step 2: Lusseyran evaluation via LLM
            String profile = evaluator.evaluate(features);
            if (profile == null || profile.contains("\"error\"")) {
                return ActionResult.fail(NAME,
                        "Lusseyran evaluation failed: " + profile, start);
            }

            // Step 3: Combine features + profile into one JSON result
            String combined = combineResult(features, profile);
            LOG.info(() -> "LusseyranEvaluatorAction: evaluated "
                    + (audioFile != null ? audioFile.getFileName() : "features")
                    + " → profile " + profile.length() + " bytes");
            return ActionResult.ok(NAME, combined, start);

        } catch (Exception e) {
            return ActionResult.fail(NAME, "Unexpected error: " + e.getMessage(), start);
        }
    }

    /**
     * Combine the raw features and the Lusseyran profile into one JSON object.
     */
    private String combineResult(String features, String profile) {
        // Strip outer braces from features and profile, merge them
        String featuresInner = features.strip();
        if (featuresInner.startsWith("{")) {
            featuresInner = featuresInner.substring(1);
        }
        if (featuresInner.endsWith("}")) {
            featuresInner = featuresInner.substring(0, featuresInner.length() - 1);
        }

        String profileInner = profile.strip();
        if (profileInner.startsWith("{")) {
            profileInner = profileInner.substring(1);
        }
        if (profileInner.endsWith("}")) {
            profileInner = profileInner.substring(0, profileInner.length() - 1);
        }

        return "{"
                + featuresInner + ","
                + "\"lusseyran_evaluation\":" + profile.strip()
                + "}";
    }

    @Override
    public String toString() {
        return "LusseyranEvaluatorAction["
                + (audioFile != null ? audioFile.getFileName() : "features")
                + " → " + evaluator.model() + "]";
    }
}
