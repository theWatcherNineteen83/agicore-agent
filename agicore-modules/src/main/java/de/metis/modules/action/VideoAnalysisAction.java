package de.metis.modules.action;

import de.metis.kernel.action.Action;
import de.metis.kernel.action.ActionResult;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Analysiert Video-Dateien oder Live-Streams via ffmpeg Frame-Extraction + Ollama Vision.
 * <p>
 * Pipeline: Video/Stream → ffmpeg (1 Frame/s) → minicpm-v (Beschreibung pro Frame) →
 * aggregierte Zusammenfassung.
 * <p>
 * Zwei Modi:
 * <ul>
 *   <li><b>Datei-Modus:</b> lokale oder remote Video-Datei (mp4, mkv, avi, etc.)</li>
 *   <li><b>Stream-Modus:</b> RTSP/HTTP-Livestream (Webcam, Kamera)</li>
 * </ul>
 * <p>
 * Category: read. Approval: AUTO (analysiert nur, verändert nichts).
 * <p>
 * Abhängigkeiten: ffmpeg im PATH, Ollama mit minicpm-v auf 192.168.22.204.
 */
public class VideoAnalysisAction implements Action {

    private static final Logger LOG = Logger.getLogger(VideoAnalysisAction.class.getName());
    public static final String NAME = "video-analyze";

    private static final String OLLAMA_URL = "http://192.168.22.204:11434/api/chat";
    private static final String VISION_MODEL = "minicpm-v:latest";
    private static final String FFPROBE_BIN = "ffprobe";
    private static final String FFMPEG_BIN = "ffmpeg";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(60);

    // Konfigurierbar
    private final String videoSource;       // Dateipfad oder URL
    private final String sourceName;        // Anzeigename für Logs/Beliefs
    private final int maxFrames;            // max. Frames zu analysieren (Default: 12 = ~1 min bei 0.2 fps)
    private final double fps;               // Frames pro Sekunde (Default: 0.2 = alle 5s)
    private final int streamDurationSec;    // bei Live-Streams: wie lange capturen?
    private final boolean isStream;         // true = Livestream, false = Datei

    /**
     * Video-Datei analysieren.
     *
     * @param videoPath  Pfad zur Video-Datei
     * @param sourceName Anzeigename
     * @param maxFrames  max. Anzahl Frames zu analysieren
     * @param fps        Frames pro Sekunde (z.B. 0.2 = alle 5 Sekunden)
     */
    public VideoAnalysisAction(String videoPath, String sourceName, int maxFrames, double fps) {
        this(videoPath, sourceName, maxFrames, fps, 0, false);
    }

    /**
     * Live-Stream analysieren (RTSP/HTTP).
     *
     * @param streamUrl         Stream-URL (rtsp://... oder http://...)
     * @param sourceName        Anzeigename
     * @param maxFrames         max. Frames zu analysieren
     * @param fps               Frames pro Sekunde
     * @param streamDurationSec wie lange capturen (z.B. 30 Sekunden)
     */
    public VideoAnalysisAction(String streamUrl, String sourceName, int maxFrames,
                               double fps, int streamDurationSec) {
        this(streamUrl, sourceName, maxFrames, fps, streamDurationSec, true);
    }

    private VideoAnalysisAction(String videoSource, String sourceName, int maxFrames,
                                double fps, int streamDurationSec, boolean isStream) {
        this.videoSource = videoSource;
        this.sourceName = sourceName;
        this.maxFrames = Math.min(maxFrames, 30);
        this.fps = Math.max(0.05, Math.min(fps, 10.0));
        this.streamDurationSec = Math.min(streamDurationSec, 120);
        this.isStream = isStream;
    }

    @Override
    public String name() { return NAME; }

    @Override
    public String category() { return "read"; }

    @Override
    public Action.ApprovalLevel approvalLevel() { return ApprovalLevel.AUTO; }

    @Override
    public ActionResult execute() {
        Instant start = Instant.now();
        Path tempDir = null;
        long frameCount = 0;

        try {
            // 1. ffprobe: Video-Metadaten abrufen
            String metadata = probeVideo();
            LOG.info("Video metadata for " + sourceName + ": " + metadata);

            // 2. Temp-Verzeichnis für Frames
            tempDir = Files.createTempDirectory("metis-video-");

            // 3. ffmpeg: Frames extrahieren
            frameCount = extractFrames(tempDir);
            if (frameCount == 0) {
                return ActionResult.fail(NAME, "No frames extracted from " + sourceName, start);
            }

            LOG.info("Extracted " + frameCount + " frames from " + sourceName
                    + " to " + tempDir);

            // 4. Frames via Ollama Vision analysieren
            List<FrameAnalysis> analyses = new ArrayList<>();
            try (DirectoryStream<Path> frames = Files.newDirectoryStream(tempDir, "*.jpg")) {
                for (Path frame : frames) {
                    byte[] imageBytes = Files.readAllBytes(frame);
                    if (imageBytes.length < 100) continue;

                    String description = analyzeWithVision(imageBytes);
                    if (description != null && !description.isBlank()) {
                        analyses.add(new FrameAnalysis(
                                frame.getFileName().toString(),
                                description.strip()
                        ));
                    }

                    if (analyses.size() >= maxFrames) break;
                }
            }

            // 5. Zusammenfassung generieren
            String summary = buildSummary(metadata, analyses);
            long elapsedMs = Duration.between(start, Instant.now()).toMillis();

            LOG.info("Video analysis complete: " + sourceName
                    + " (" + analyses.size() + " frames in " + elapsedMs + "ms)");

            return ActionResult.ok(NAME, summary, start);

        } catch (Exception e) {
            LOG.severe("Video analysis failed for " + sourceName + ": " + e.getMessage());
            return ActionResult.fail(NAME, "Video analysis error: " + e.getMessage(), start);
        } finally {
            // 6. Temp-Verzeichnis aufräumen
            if (tempDir != null) {
                cleanup(tempDir);
            }
        }
    }

    // ── ffprobe: Video-Metadaten ─────────────────────────────────

    private String probeVideo() throws Exception {
        List<String> args = new ArrayList<>(List.of(
                FFPROBE_BIN,
                "-v", "quiet",
                "-print_format", "json",
                "-show_format",
                "-show_streams",
                "-analyzeduration", isStream ? "5000000" : "10000000",
                "-probesize", isStream ? "5000000" : "10000000"
        ));

        if (isStream) {
            args.add("-rtsp_transport");
            args.add("tcp");
        }

        args.add(videoSource);

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        Process proc = pb.start();

        String output = new String(proc.getInputStream().readAllBytes());
        proc.waitFor();

        if (output.contains("\"format\"")) {
            return extractFields(output);
        }

        return "duration=unknown, format=unknown";
    }

    /**
     * Extrahiert relevante Felder aus ffprobe JSON (Lightweight, kein Jackson nötig).
     */
    private String extractFields(String json) {
        StringBuilder sb = new StringBuilder();

        // duration
        String dur = extractJsonString(json, "duration");
        if (!dur.isEmpty()) sb.append("duration=").append(dur).append("s, ");

        // format_name
        String fmt = extractJsonString(json, "format_name");
        if (!fmt.isEmpty()) sb.append("format=").append(fmt).append(", ");

        // codec_name (first stream)
        int codecIdx = json.indexOf("\"codec_name\"");
        if (codecIdx > 0) {
            int valStart = json.indexOf("\"", codecIdx + "\"codec_name\"".length() + 1);
            if (valStart > 0) {
                int valEnd = json.indexOf("\"", valStart + 1);
                if (valEnd > 0) {
                    sb.append("codec=").append(json, valStart + 1, valEnd).append(", ");
                }
            }
        }

        // width x height
        String w = extractJsonInt(json, "width");
        String h = extractJsonInt(json, "height");
        if (!w.isEmpty() && !h.isEmpty()) sb.append("resolution=").append(w).append("x").append(h);

        String result = sb.toString();
        return result.endsWith(", ") ? result.substring(0, result.length() - 2) : result;
    }

    private String extractJsonString(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return "";

        // find string value
        int valStart = json.indexOf("\"", idx + search.length());
        if (valStart < 0) return "";

        // skip if value is numeric (not a string)
        char next = json.charAt(valStart + 1);
        if (next == '{' || next == '[' || Character.isDigit(next) || next == '-') {
            return "";
        }

        int valEnd = json.indexOf("\"", valStart + 1);
        if (valEnd < 0) return "";

        return json.substring(valStart + 1, valEnd);
    }

    private String extractJsonInt(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return "";

        int valStart = json.indexOf(":", idx + search.length());
        if (valStart < 0) return "";

        // skip whitespace and colon
        valStart++;
        while (valStart < json.length() && (json.charAt(valStart) == ' ' || json.charAt(valStart) == '\n')) {
            valStart++;
        }

        // read digits
        int valEnd = valStart;
        while (valEnd < json.length() && Character.isDigit(json.charAt(valEnd))) {
            valEnd++;
        }

        return valEnd > valStart ? json.substring(valStart, valEnd) : "";
    }

    // ── ffmpeg Frame-Extraction ──────────────────────────────────

    private long extractFrames(Path outputDir) throws Exception {
        List<String> args = new ArrayList<>(List.of(
                FFMPEG_BIN,
                "-y",                          // overwrite output files
                "-loglevel", "error"           // nur Fehler loggen
        ));

        // Input-Optionen
        if (isStream) {
            args.add("-rtsp_transport");
            args.add("tcp");
            args.add("-analyzeduration");
            args.add("5000000");
            args.add("-probesize");
            args.add("5000000");
            args.add("-i");
            args.add(videoSource);
            args.add("-t");
            args.add(String.valueOf(streamDurationSec));
        } else {
            args.add("-i");
            args.add(videoSource);
        }

        // Frame-Extraction: 1 Frame alle 1/fps Sekunden
        args.add("-vf");
        args.add("fps=" + fps);
        args.add("-frames:v");
        args.add(String.valueOf(maxFrames));
        args.add("-q:v");
        args.add("2");                       // JPEG-Qualität (2 = gut)
        args.add(outputDir.resolve("frame_%04d.jpg").toString());

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        Process proc = pb.start();

        // Stderr lesen für Fehler
        StringWriter stderr = new StringWriter();
        try (InputStream is = proc.getInputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) {
                stderr.write(new String(buf, 0, n));
            }
        }

        int exitCode = proc.waitFor();
        if (exitCode != 0 && exitCode != 255) { // 255 = normal EOF bei Streams
            String err = stderr.toString();
            if (!err.isBlank()) {
                LOG.warning("ffmpeg exited with " + exitCode + ": " + err.trim());
            }
        }

        // Extraktion verifizieren
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(outputDir, "frame_*.jpg")) {
            long count = 0;
            for (@SuppressWarnings("unused") Path p : ds) count++;
            return count;
        }
    }

    // ── Ollama Vision ────────────────────────────────────────────

    private String analyzeWithVision(byte[] imageBytes) throws Exception {
        String b64 = Base64.getEncoder().encodeToString(imageBytes);

        String prompt = "Beschreibe kurz auf Deutsch in 1-2 Sätzen was auf diesem Bild/Videoframe zu sehen ist. "
                + "Fokus: Objekte, Personen, Bewegung, Wetter, Lichtverhältnisse, Atmosphäre. "
                + "Quelle: " + sourceName + ".";

        String safePrompt = escapeJson(prompt);
        String jsonBody = String.format(
                "{\"model\":\"%s\",\"stream\":false,\"messages\":["
                        + "{\"role\":\"user\",\"content\":\"%s\",\"images\":[\"%s\"]}"
                        + "],\"options\":{\"temperature\":0.2,\"num_predict\":120},\"keep_alive\":0}",
                VISION_MODEL, safePrompt, b64);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OLLAMA_URL))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        // Jeder Vision-Call bekommt einen frischen HttpClient
        // (vermeidet "selector manager closed" bei Dauer-Polling)
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            LOG.warning("Ollama Vision returned " + response.statusCode());
            return null;
        }

        return extractContent(response.body());
    }

    // ── Zusammenfassung ──────────────────────────────────────────

    private String buildSummary(String metadata, List<FrameAnalysis> analyses) {
        StringBuilder sb = new StringBuilder();
        sb.append("Videobeschreibung: ").append(sourceName).append("\n");
        sb.append("Metadaten: ").append(metadata).append("\n");
        sb.append("Analysierte Frames: ").append(analyses.size()).append("\n\n");

        if (analyses.isEmpty()) {
            sb.append("Keine Frames analysierbar.");
            return sb.toString();
        }

        sb.append("Frame-für-Frame Analyse:\n");
        for (int i = 0; i < analyses.size(); i++) {
            FrameAnalysis fa = analyses.get(i);
            sb.append("  Frame ").append(i + 1).append(" (").append(fa.frame()).append("): ")
                    .append(fa.description()).append("\n");
        }

        return sb.toString();
    }

    // ── JSON-Helfer ──────────────────────────────────────────────

    private String extractContent(String json) {
        String search = "\"content\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();

        StringBuilder val = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case 'n' -> { val.append('\n'); i++; }
                    case 't' -> { val.append('\t'); i++; }
                    case '"' -> { val.append('"'); i++; }
                    case '\\' -> { val.append('\\'); i++; }
                    default -> val.append(c);
                }
            } else if (c == '"') {
                break;
            } else {
                val.append(c);
            }
        }
        return val.toString();
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    // ── Cleanup ──────────────────────────────────────────────────

    private void cleanup(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                    for (Path p : ds) Files.deleteIfExists(p);
                }
                Files.deleteIfExists(dir);
            }
        } catch (IOException e) {
            LOG.fine("Cleanup partial: " + dir + " - " + e.getMessage());
        }
    }

    // ── Record ───────────────────────────────────────────────────

    record FrameAnalysis(String frame, String description) {}
}
