package de.metis.modules.speech;

import de.metis.kernel.action.Action;
import de.metis.kernel.action.ActionResult;
import de.metis.kernel.action.VoskListenAction;

import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Java-native speech-to-text via Vosk (offline, German model).
 * <p>
 * Vosk is a lightweight offline speech recognition toolkit with
 * Java bindings. Supports German via downloaded acoustic model.
 * Falls back to whisper.cpp for file-based transcription.
 * <p>
 * <b>Evolvable:</b> Metis can read, understand, and mutate this Java code
 * to improve accuracy, switch model sizes, or optimize for speed.
 *
 * @see VoskListenAction for the kernel-level STT engine
 */
public class SphinxSttAction implements Action {

    private static final Logger LOG = Logger.getLogger(SphinxSttAction.class.getName());
    private static final String NAME = "stt-sphinx";

    private final String audioPath;
    private final int liveDurationSeconds;

    /** File-based transcription */
    public SphinxSttAction(String audioPath) {
        this.audioPath = audioPath;
        this.liveDurationSeconds = 0;
    }

    /** Live microphone transcription */
    public SphinxSttAction(int durationSeconds) {
        this.audioPath = null;
        this.liveDurationSeconds = durationSeconds;
    }

    @Override public String name() { return NAME; }
    @Override public String category() { return "write"; }

    @Override
    public ActionResult execute() {
        if (audioPath != null) {
            // File-based: delegate to whisper for accuracy
            LOG.info(() -> "Sphinx4 STT (file): delegating to whisper for accuracy → " + audioPath);
            return new WhisperSttAction(Path.of(audioPath)).execute();
        } else {
            // Live mic: use Vosk Java-native
            LOG.info(() -> "Vosk STT (live mic, " + liveDurationSeconds + "s)");
            return new VoskListenAction(liveDurationSeconds).execute();
        }
    }
}
