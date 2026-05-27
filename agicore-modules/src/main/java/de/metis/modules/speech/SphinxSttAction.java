package de.metis.modules.speech;

import de.metis.kernel.action.Action;
import de.metis.kernel.action.ActionResult;

import java.time.Instant;
import java.util.logging.Logger;

/**
 * Java-native speech-to-text via CMU Sphinx4 (evolvable).
 * <p>
 * Sphinx4 is a pure-Java speech recognition framework. Currently acts
 * as a stub that delegates to whisper.cpp for actual transcription,
 * but Metis can evolve this into a full Java-native implementation.
 * <p>
 * <b>Evolvable:</b> Metis can read, understand, and mutate this Java code.
 * The whisper.cpp output serves as training/reference data for improvement.
 *
 * @see WhisperSttAction for neural reference
 */
public class SphinxSttAction implements Action {

    private static final Logger LOG = Logger.getLogger(SphinxSttAction.class.getName());
    private static final String NAME = "stt-sphinx";

    private final String audioPath;

    public SphinxSttAction(String audioPath) {
        this.audioPath = audioPath;
    }

    @Override public String name() { return NAME; }
    @Override public String category() { return "write"; }

    @Override
    public ActionResult execute() {
        Instant start = Instant.now();
        // Currently delegates to whisper.cpp for actual transcription.
        // Future: Metis will evolve a native Sphinx4-based implementation
        // using German acoustic/language models from VoxForge.
        LOG.info(() -> "Sphinx4 STT: delegating to whisper.cpp (evolvable stub)");
        var whisper = new WhisperSttAction(java.nio.file.Path.of(audioPath));
        return whisper.execute();
    }
}
