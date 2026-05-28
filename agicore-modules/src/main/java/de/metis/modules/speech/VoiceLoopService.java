package de.metis.modules.speech;

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
 * Uses Piper (TTS) + Whisper (STT) via CLI for reliability. Microphone input
 * via ALSA/arecord. Submits recognized speech as Goals to Metis' cognitive
 * engine, and speaks Metis' responses via Piper TTS.
 * <p>
 * Stack: arecord → Whisper CLI → Metis Goal → Piper CLI → aplay
 * <p>
 * Runs as a daemon thread. Start/stop via {@link #start()} / {@link #stop()}.
 */
public class VoiceLoopService {

    private static final Logger LOG = Logger.getLogger(VoiceLoopService.class.getName());

    // Audio configuration
    private static final int LISTEN_SECONDS = 4;
    private static final int LISTEN_SAMPLE_RATE = 16000;
    private static final Path RECORDING = Path.of("/tmp/metis-voice/live.wav");
    private static final Path TTS_OUTPUT = Path.of("/tmp/metis-voice/response.wav");
    private static final String AUDIO_DEVICE = "pipewire";  // Alias für ALSA→PipeWire

    // CLI paths (verified on miniedi)
    private static final String ARECORD_BIN = "arecord";
    private static final String PIPER_BIN = "/usr/local/bin/piper";
    private static final String PIPER_MODEL = "/home/prometheus/piper-voices/de_DE-thorsten-medium.onnx";
    private static final String WHISPER_BIN = "/home/prometheus/.local/bin/whisper";
    private static final String WHISPER_MODEL = "small";  // small für bessere Genauigkeit
    private static final String APLAY_BIN = "aplay";

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
        LOG.info("VoiceLoopService started — Piper+Whisper CLI, listen=" + LISTEN_SECONDS + "s");
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
        try {
            Files.createDirectories(RECORDING.getParent());
        } catch (IOException e) {
            LOG.severe("Cannot create voice directory: " + e.getMessage());
            return;
        }

        // Welcome message
        speak("Metis Sprachassistent bereit.");

        while (running.get()) {
            try {
                // 1. LISTEN: Record microphone → Whisper STT
                String heard = listen();
                if (heard.isEmpty()) {
                    Thread.sleep(500);
                    continue;
                }

                LOG.info(() -> "Heard: \"" + heard + "\"");

                // 2. UNDERSTAND + THINK: Submit as Goal to Metis
                goals.add(new Goal(
                        "Voice input: " + heard,
                        "voice",
                        80,  // high priority for voice
                        0.9,
                        1
                ));

                conversationCount++;

                // 3. SPEAK: acknowledge (evolvable: Metis will respond via its own planning)
                // Wait briefly for Metis to process
                Thread.sleep(1500);

                // For now: simple echo. Metis can override via goal completion hooks.
                String response = "Verstanden: " + heard;
                speak(response);

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

    // ── Speech-to-Text: arecord → Whisper ──────────────────────

    private String listen() throws IOException, InterruptedException {
        // 1. Record audio via arecord
        ProcessBuilder recPb = new ProcessBuilder(
                ARECORD_BIN,
                "-D", AUDIO_DEVICE,
                "-d", String.valueOf(LISTEN_SECONDS),
                "-f", "S16_LE",
                "-r", String.valueOf(LISTEN_SAMPLE_RATE),
                "-c", "1",
                RECORDING.toString()
        );
        recPb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process rec = recPb.start();
        boolean recOk = rec.waitFor(LISTEN_SECONDS + 5, TimeUnit.SECONDS);

        if (!recOk || !Files.exists(RECORDING) || Files.size(RECORDING) < 1000) {
            return "";
        }

        // 2. Transcribe via Whisper CLI
        return transcribeWhisper();
    }

    private String transcribeWhisper() throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                WHISPER_BIN, RECORDING.toString(),
                "--model", WHISPER_MODEL,
                "--language", "de",
                "--output_dir", RECORDING.getParent().toString(),
                "--output_format", "txt"
        );
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        p.waitFor(60, TimeUnit.SECONDS);

        // Whisper output: <recording-path>.txt
        Path transcriptFile = Path.of(RECORDING.getParent().toString(),
                RECORDING.getFileName().toString() + ".txt");
        if (Files.exists(transcriptFile)) {
            String text = Files.readString(transcriptFile).trim();
            return text;
        }

        return "";
    }

    // ── Text-to-Speech: Piper → aplay ─────────────────────────

    public void speak(String text) {
        try {
            // 1. Generate audio via Piper
            ProcessBuilder ttsPb = new ProcessBuilder(
                    PIPER_BIN,
                    "--model", PIPER_MODEL,
                    "--output_file", TTS_OUTPUT.toString()
            );
            ttsPb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process ttsProc = ttsPb.start();
            ttsProc.getOutputStream().write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ttsProc.getOutputStream().close();

            boolean ttsOk = ttsProc.waitFor(30, TimeUnit.SECONDS);
            if (!ttsOk || ttsProc.exitValue() != 0) {
                LOG.warning("Piper TTS failed");
                return;
            }

            // 2. Play audio via aplay
            ProcessBuilder playPb = new ProcessBuilder(APLAY_BIN, "-D", AUDIO_DEVICE, TTS_OUTPUT.toString());
            playPb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process playProc = playPb.start();
            playProc.waitFor(30, TimeUnit.SECONDS);

        } catch (IOException | InterruptedException e) {
            LOG.warning("TTS speak error: " + e.getMessage());
        }
    }
}
