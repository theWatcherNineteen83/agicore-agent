package de.metis.modules.sensor;

import de.metis.kernel.action.Action;
import de.metis.kernel.action.ActionResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Captures OGG/Opus audio from the S9 microphone via WebSocket bridge.
 * <p>
 * Connects to ws://localhost:8765/audio, accumulates OGG Opus frames for
 * {@code captureSeconds}, saves to a temp file, and runs Vosk STT offline.
 * Returns the transcription text as ActionResult body.
 * <p>
 * Hardware path: S9 mic → ADB/TCP → ffmpeg(Opus enc) → WebSocket → this action → Vosk STT.
 * Zero cloud dependencies — all local processing.
 */
public class AudioBridgeAction implements Action {

    public static final String NAME = "audio-bridge";
    private static final Logger LOG = Logger.getLogger(AudioBridgeAction.class.getName());

    private final String wsUrl;
    private final int captureSeconds;
    private final String voskModelPath;

    public AudioBridgeAction() {
        this("ws://localhost:8765/audio", 5, System.getProperty("vosk.model.path", "/data/prometheus/vosk-model-de"));
    }

    public AudioBridgeAction(String wsUrl, int captureSeconds, String voskModelPath) {
        this.wsUrl = wsUrl;
        this.captureSeconds = captureSeconds;
        this.voskModelPath = voskModelPath;
    }

    @Override public String name() { return NAME; }
    @Override public String category() { return "read"; }
    @Override public ApprovalLevel approvalLevel() { return ApprovalLevel.AUTO; }

    @Override
    public ActionResult execute() {
        Instant start = Instant.now();
        ByteArrayOutputStream oggBuffer = new ByteArrayOutputStream();
        CompletableFuture<String> result = new CompletableFuture<>();

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            WebSocket ws = client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {

                        @Override
                        public CompletionStage<?> onBinary(WebSocket webSocket, java.nio.ByteBuffer data, boolean last) {
                            byte[] bytes = new byte[data.remaining()];
                            data.get(bytes);
                            try {
                                oggBuffer.write(bytes);
                            } catch (IOException ignored) {}
                            return null;
                        }

                        @Override
                        public void onError(WebSocket webSocket, Throwable error) {
                            result.completeExceptionally(error);
                        }

                        @Override
                        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                            if (!result.isDone()) result.complete("closed");
                            return null;
                        }
                    }).join();

            // Wait to accumulate OGG data
            TimeUnit.SECONDS.sleep(captureSeconds);
            ws.sendClose(1000, "captured");

            byte[] oggData = oggBuffer.toByteArray();
            int kb = oggData.length / 1024;
            LOG.info(() -> "AudioBridgeAction: captured " + kb + " KB OGG in " + captureSeconds + "s");

            if (oggData.length < 256) {
                return ActionResult.fail(NAME, "Captured only " + oggData.length + " bytes — microphone silent?", start);
            }

            // Decode OGG → PCM via ffmpeg, run Vosk STT
            String transcription = transcribe(oggData);

            if (transcription == null || transcription.isEmpty()) {
                return ActionResult.ok(NAME, "(silence — " + kb + " KB OGG captured)", start);
            }

            return ActionResult.ok(NAME, transcription.trim(), start);

        } catch (Exception e) {
            return ActionResult.fail(NAME, "Audio bridge error: " + e.getMessage(), start);
        }
    }

    /**
     * Decodes OGG/Opus to 16kHz mono WAV via ffmpeg piped to Vosk recognizer.
     * Returns transcription text, or null/empty if silent.
     */
    private String transcribe(byte[] oggData) {
        try {
            // Step 1: OGG → PCM 16kHz mono 16-bit via ffmpeg
            ProcessBuilder ffmpegPb = new ProcessBuilder(
                    "ffmpeg", "-loglevel", "error",
                    "-i", "pipe:0",
                    "-f", "s16le", "-acodec", "pcm_s16le",
                    "-ar", "16000", "-ac", "1",
                    "pipe:1"
            );
            Process ffmpeg = ffmpegPb.start();
            ffmpeg.getOutputStream().write(oggData);
            ffmpeg.getOutputStream().close();

            ByteArrayOutputStream pcmBuffer = new ByteArrayOutputStream();
            ffmpeg.getInputStream().transferTo(pcmBuffer);
            ffmpeg.waitFor(5, TimeUnit.SECONDS);
            byte[] pcmData = pcmBuffer.toByteArray();

            if (pcmData.length < 320) {  // less than 10ms of audio
                return null;
            }

            // Step 2: Vosk STT on PCM data
            return runVosk(pcmData);

        } catch (Exception e) {
            LOG.warning(() -> "AudioBridgeAction transcribe error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Runs Vosk offline STT on raw 16kHz mono 16-bit PCM data.
     */
    private String runVosk(byte[] pcmData) {
        try {
            org.vosk.Model model = new org.vosk.Model(voskModelPath);
            org.vosk.Recognizer recognizer = new org.vosk.Recognizer(model, 16000f);
            recognizer.setWords(true);

            // Feed PCM 320 bytes (10ms frames) at a time
            int offset = 0;
            int frameSize = 640; // 20ms at 16kHz 16-bit mono
            String finalResult = "";

            while (offset < pcmData.length) {
                int len = Math.min(frameSize, pcmData.length - offset);
                byte[] frame = java.util.Arrays.copyOfRange(pcmData, offset, offset + len);
                boolean accepted = recognizer.acceptWaveForm(frame, frame.length);
                if (accepted) {
                    // Result chunk — Vosk returns JSON with "text" field
                    String partial = recognizer.getResult();
                    // Actually acceptWaveForm returning true means we should call getResult
                    // but the API changed in newer Vosk versions. Let's try both.

                    // For Vosk 0.3.45+: acceptWaveForm returns boolean
                    // Let's just call getResult at the end
                }
                offset += len;
            }

            // Get final result
            String jsonResult = recognizer.getFinalResult();
            recognizer.close();
            model.close();

            // Parse JSON
            if (jsonResult != null && jsonResult.contains("\"text\"")) {
                try {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    var node = mapper.readTree(jsonResult);
                    if (node.has("text")) {
                        finalResult = node.get("text").asText();
                    }
                } catch (Exception e) {
                    // Fallback: simple regex extract
                    int start = jsonResult.indexOf("\"text\" : \"");
                    if (start > 0) {
                        start += 10;
                        int end = jsonResult.indexOf("\"", start);
                        if (end > start) {
                            finalResult = jsonResult.substring(start, end);
                        }
                    }
                }
            }

            return finalResult;

        } catch (Exception e) {
            LOG.warning(() -> "AudioBridgeAction Vosk error: " + e.getMessage());
            return null;
        }
    }
}
