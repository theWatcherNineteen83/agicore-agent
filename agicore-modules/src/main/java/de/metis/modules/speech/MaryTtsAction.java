package de.metis.modules.speech;

import de.metis.kernel.action.Action;
import de.metis.kernel.action.ActionResult;
import de.metis.kernel.action.MaryTTSSpeakAction;

import java.util.logging.Logger;

/**
 * Java-native text-to-speech via MaryTTS (pure Java, DFKI Saarland).
 * <p>
 * MaryTTS is a multilingual TTS system written in Java, supporting
 * German natively (bits1, bits3 voices). Uses real MaryTTS Maven
 * dependency (de.dfki.mary:marytts-runtime:5.2.1).
 * <p>
 * <b>Evolvable:</b> Metis can read, understand, and mutate this Java code
 * to improve voice quality, add prosody control, or switch voices.
 *
 * @see MaryTTSSpeakAction for the kernel-level TTS engine
 */
public class MaryTtsAction implements Action {

    private static final Logger LOG = Logger.getLogger(MaryTtsAction.class.getName());
    private static final String NAME = "tts-mary";

    private final String text;
    private final String voice;

    public MaryTtsAction(String text) {
        this(text, "bits1");
    }

    public MaryTtsAction(String text, String voice) {
        this.text = text;
        this.voice = voice;
    }

    @Override public String name() { return NAME; }
    @Override public String category() { return "write"; }

    @Override
    public ActionResult execute() {
        LOG.info(() -> "MaryTTS native TTS (bits1 voice): \"" +
                (text.length() > 60 ? text.substring(0, 60) + "..." : text) + "\"");
        return new MaryTTSSpeakAction(text, voice).execute();
    }
}
