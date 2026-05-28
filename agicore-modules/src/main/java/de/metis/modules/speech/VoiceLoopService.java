package de.metis.modules.speech;

import de.metis.kernel.action.MaryTTSSpeakAction;
import de.metis.kernel.action.VoskListenAction;
import de.metis.kernel.goal.Goal;
import de.metis.kernel.goal.GoalManager;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Continuous voice interaction loop: Listen → Understand → Think → Speak.
 * <p>
 * Uses Vosk (Java-native, com.alphacephei:vosk:0.3.45) for speech recognition
 * and MaryTTS (Java-native, de.dfki.mary:marytts-runtime:5.2.1) for speech
 * synthesis. Recognized text is submitted as Goals to Metis' cognitive engine.
 * <p>
 * Stack: VoskListenAction → Metis Goal → MaryTTSSpeakAction
 * <p>
 * Runs as a daemon thread. Start/stop via {@link #start()} / {@link #stop()}.
 */
public class VoiceLoopService {

    private static final Logger LOG = Logger.getLogger(VoiceLoopService.class.getName());
    private static final int LISTEN_SECONDS = 4;

    private final GoalManager goals;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread loopThread;
    private int conversationCount;

    public VoiceLoopService(GoalManager goals) {
        this.goals = goals;
    }

    /** Start the voice loop in a background daemon thread. */
    public synchronized void start() {
        if (running.get()) {
            LOG.info("Voice loop already running");
            return;
        }
        running.set(true);
        loopThread = new Thread(this::runLoop, "metis-voice-loop");
        loopThread.setDaemon(true);
        loopThread.start();
        LOG.info("VoiceLoopService started — MaryTTS + Vosk (Java-native), listen=" + LISTEN_SECONDS + "s");
    }

    /** Stop the voice loop. */
    public synchronized void stop() {
        running.set(false);
        if (loopThread != null) {
            loopThread.interrupt();
            try { loopThread.join(3000); } catch (InterruptedException ignored) {}
        }
        LOG.info("VoiceLoopService stopped after " + conversationCount + " conversations");
    }

    public boolean isRunning() { return running.get(); }
    public int conversationCount() { return conversationCount; }

    // ── Main Loop: Listen → Understand → Think → Speak ──────────

    private void runLoop() {
        int loopCount = 0;
        int maxLoops = 10;  // safety: max self-talk iterations
        String lastSpoken = "";

        // Initial trigger: speak first → headphones → mic → loop starts
        try {
            new MaryTTSSpeakAction(
                "Metis Selbstgespräch beginnt. Kopfhörer auf Mikrofon.", "bits1-hsmm")
                .execute();
            Thread.sleep(3000);
        } catch (Exception e) {
            LOG.fine("Initial TTS skipped: " + e.getMessage());
        }

        while (running.get() && loopCount < maxLoops) {
            try {
                // 1. LISTEN via Vosk (Java-native, microphone)
                var listenAction = new VoskListenAction(LISTEN_SECONDS);
                var result = listenAction.execute();

                final String heard;
                if (result.success() && result.body() != null) {
                    heard = result.body().trim();
                } else {
                    heard = "";
                }

                // Clean up Vosk output: remove "partial :" prefix from incremental results
                String cleanHeard = heard;
                if (cleanHeard.toLowerCase().startsWith("partial :")) {
                    cleanHeard = cleanHeard.substring("partial :".length()).trim();
                } else if (cleanHeard.toLowerCase().startsWith("partial")) {
                    cleanHeard = cleanHeard.substring("partial".length()).trim();
                }

                // Skip empty after cleaning, noise patterns, our own voice
                if (cleanHeard.isEmpty() || "(silence)".equalsIgnoreCase(cleanHeard)
                        || cleanHeard.startsWith("Metis")  // don't echo TTS output
                        || cleanHeard.equals(":")
                        || cleanHeard.matches("^[^a-zA-ZäöüßÄÖÜ]*$")  // no actual letters
                        || cleanHeard.length() < 2) {
                    Thread.sleep(500);
                    continue;
                }

                // Minimal filter: only skip self-echo
                final String recognized = cleanHeard;
                LOG.info(() -> "Heard: \"" + recognized + "\"");

                // 2. Submit as Goal + respond via TTS (self-talk loop)
                if (recognized.toLowerCase().contains("wetter")
                        || recognized.toLowerCase().contains("metis")
                        || recognized.length() > 10) {
                    goals.add(new Goal(
                            "Voice input: " + recognized,
                            "voice",
                            80,
                            0.9,
                            1
                    ));
                    conversationCount++;
                    loopCount++;

                    // Self-talk: speak a response, which feeds back through headphones→mic
                    String response = "Metis Selbstgespräch " + loopCount + ": " + recognized;
                    new MaryTTSSpeakAction(response, "bits1-hsmm").execute();
                    lastSpoken = response;

                    LOG.info("Self-talk cycle " + loopCount + "/" + maxLoops
                            + ": spoke → heard → spoke");
                }

                Thread.sleep(2000);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOG.warning("Voice loop error: " + e.getMessage());
                if (running.get()) {
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) { break; }
                }
            }
        }
    }
}
