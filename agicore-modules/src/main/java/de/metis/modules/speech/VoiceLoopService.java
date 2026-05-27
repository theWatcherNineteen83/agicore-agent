package de.metis.modules.speech;

import de.metis.kernel.action.MaryTTSSpeakAction;
import de.metis.kernel.goal.Goal;
import de.metis.kernel.goal.GoalManager;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Continuous voice interaction loop: Listen → Understand → Think → Speak.
 * <p>
 * Uses Vosk (Java-native) for speech recognition and MaryTTS (Java-native)
 * for speech synthesis. Recognized text is submitted as Goals to Metis'
 * cognitive engine for planning and execution.
 * <p>
 * Runs as a daemon thread. Start/stop via {@link #start()} / {@link #stop()}.
 * <p>
 * <b>Evolvable:</b> Metis can swap the STT/TTS engines, add wake-word detection,
 * or introduce turn-taking logic.
 */
public class VoiceLoopService {

    private static final Logger LOG = Logger.getLogger(VoiceLoopService.class.getName());
    private static final int LISTEN_SECONDS = 4;
    private static final Path RECORDING = Path.of("/tmp/metis-voice/live.wav");

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
        LOG.info("VoiceLoopService started (listen=" + LISTEN_SECONDS + "s)");
    }

    /** Stop the voice loop and wait for the current cycle to finish. */
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

    // ── loop ──

    private void runLoop() {
        while (running.get()) {
            try {
                // 1. Listen
                String heard = listen();
                if (heard.isEmpty()) {
                    Thread.sleep(500);
                    continue;
                }

                LOG.info(() -> "Heard: \"" + heard + "\"");

                // 2. Submit as Goal to Metis
                Goal goal = goals.add(new Goal(
                        "Voice input: " + heard,
                        "voice",
                        80,  // high priority for voice interaction
                        0.9, // reward
                        1    // low cost
                ));

                // 3. Wait for Metis to process (cognitive loop will handle it)
                // The response will come back via the action output mechanism.
                // For now, use a simple speak response directly.
                Thread.sleep(2000); // give Metis a cycle to process

                // 4. Speak acknowledgment (evolvable to actual Metis response)
                new MaryTTSSpeakAction("Ich habe verstanden: " + heard).execute();

                conversationCount++;

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

    // ── STT via Vosk (Java-native with shell fallback) ──

    private String listen() throws IOException, InterruptedException {
        createDirs();

        String text;

        // Try Java-native Vosk first
        try {
            text = listenViaVosk();
            if (!text.isBlank()) return text;
        } catch (Exception e) {
            LOG.fine(() -> "Java-native Vosk unavailable, trying shell fallback: " + e.getMessage());
        }

        // Fallback: shell-based whisper/vosk
        return listenViaShell();
    }

    private String listenViaVosk() {
        // Uses VoskListenAction internally - requires Java Sound API + mic
        var action = new de.metis.kernel.action.VoskListenAction(LISTEN_SECONDS);
        var result = action.execute();
        if (result.success()) {
            String text = result.body();
            if (text != null && !text.isBlank() && !"(silence)".equals(text)) {
                return text;
            }
        }
        return "";
    }

    private String listenViaShell() throws IOException, InterruptedException {
        // Record audio
        ProcessBuilder recPb = new ProcessBuilder(
                "arecord", "-D", "pipewire", "-d", String.valueOf(LISTEN_SECONDS),
                "-f", "S16_LE", "-r", "16000", "-c", "1",
                RECORDING.toString()
        );
        Process rec = recPb.start();
        rec.waitFor();

        if (!Files.exists(RECORDING) || Files.size(RECORDING) < 1000) {
            return "";
        }

        // Try Vosk Python first (fast), then whisper (accurate)
        String text = transcribeVosk();
        if (text.isBlank()) {
            text = transcribeWhisper();
        }
        return text;
    }

    private String transcribeVosk() throws IOException, InterruptedException {
        var pb = new ProcessBuilder("python3", "-c",
                "import json,wave,os; os.environ['VOSK_LOG_LEVEL']='-1';\n" +
                "from vosk import Model,KaldiRecognizer;\n" +
                "wf=wave.open('" + RECORDING + "','rb');\n" +
                "try:\n" +
                "  r=KaldiRecognizer(Model('/data/prometheus/vosk-model-de-current'),16000);\n" +
                "  while True:\n" +
                "    d=wf.readframes(4000);\n" +
                "    if len(d)==0:break;\n" +
                "    r.AcceptWaveform(d);\n" +
                "  print(json.loads(r.FinalResult()).get('text',''));\n" +
                "except: print('')\n"
        );
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        return output;
    }

    private String transcribeWhisper() throws IOException, InterruptedException {
        var pb = new ProcessBuilder(
                "whisper", RECORDING.toString(),
                "--model", "small", "--language", "de",
                "--output_dir", RECORDING.getParent().toString(),
                "--output_format", "txt"
        );
        pb.environment().put("PATH", System.getenv("PATH") + ":/home/prometheus/.local/bin");
        pb.start().waitFor();

        Path txt = RECORDING.getParent().resolve(RECORDING.getFileName().toString().replace(".wav", "") + ".txt");
        if (Files.exists(txt)) {
            return Files.readString(txt).trim();
        }

        // Try alternate naming
        try (var stream = Files.list(RECORDING.getParent())) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .findFirst()
                    .map(p -> { try { return Files.readString(p).trim(); } catch (IOException e) { return ""; } })
                    .orElse("");
        }
    }

    private void createDirs() throws IOException {
        Files.createDirectories(RECORDING.getParent());
    }
}
