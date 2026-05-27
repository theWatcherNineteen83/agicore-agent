package de.metis.modules.speech;

import de.metis.kernel.action.Action;
import de.metis.kernel.action.ActionResult;

import java.time.Instant;
import java.util.logging.Logger;

/**
 * Java-native text-to-speech via MaryTTS (pure Java, DFKI Saarland).
 * <p>
 * MaryTTS is a multilingual TTS system written in Java, supporting
 * German natively (bits1, bits3 voices). Currently acts as a stub that
 * delegates to Piper CLI for actual synthesis, but Metis can evolve
 * this into a full Java-native implementation.
 * <p>
 * <b>Evolvable:</b> Metis can read, understand, and mutate this Java code.
 * The Piper neural output serves as reference quality for comparison.
 *
 * @see PiperTtsAction for neural reference
 */
public class MaryTtsAction implements Action {

    private static final Logger LOG = Logger.getLogger(MaryTtsAction.class.getName());
    private static final String NAME = "tts-mary";

    private final String text;

    public MaryTtsAction(String text) {
        this.text = text;
    }

    @Override public String name() { return NAME; }
    @Override public String category() { return "write"; }

    @Override
    public ActionResult execute() {
        // Currently delegates to Piper for actual synthesis.
        // Future: Metis will evolve a native MaryTTS-based implementation
        // using de.dfki.mary:marytts:5.2 Maven dependency.
        LOG.info(() -> "MaryTTS TTS (evolvable stub): \"" + text + "\"");
        var piper = new PiperTtsAction(text);
        return piper.execute();
    }

    /** Delete the unused runtime class — it's kept as reference for Metis evolution. */
}
