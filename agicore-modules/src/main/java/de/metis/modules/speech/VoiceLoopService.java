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
        // Welcome message
        new MaryTTSSpeakAction("Metis Sprachassistent bereit.", "bits1-hsmm").execute();

        while (running.get()) {
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

                // Skip silence / empty
                if (heard.isEmpty() || "(silence)".equalsIgnoreCase(heard)) {
                    Thread.sleep(500);
                    continue;
                }

                LOG.info(() -> "Heard: \"" + heard + "\"");

                // 2. UNDERSTAND + THINK: Submit as Goal to Metis
                goals.add(new Goal(
                        "Voice input: " + heard,
                        "voice",
                        80,  // high priority for voice interaction
                        0.9,
                        1
                ));

                conversationCount++;

                // 3. Wait briefly for Metis to process
                Thread.sleep(1500);

                // 4. SPEAK response via MaryTTS (Java-native)
                new MaryTTSSpeakAction("Verstanden: " + heard, "bits1-hsmm").execute();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOG.warning("Voice loop error: " + e.getMessage());
                if (running.get()) {
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) { break; }
                }
            }
        }
    }
}
