package de.metis.modules.action;

import de.metis.kernel.action.Action;
import de.metis.kernel.action.ActionResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Captures a single snapshot from a configured camera via ffmpeg.
 * <p>
 * Supports both RTSP streams (Keller/Annke H.265) and MJPEG HTTP endpoints (Türkamera).
 * The snapshot is saved to /tmp/metis-camera-{camera}-{timestamp}.jpg.
 */
public class CameraSnapshotAction implements Action {

    private static final Logger LOG = Logger.getLogger(CameraSnapshotAction.class.getName());

    public static final String NAME_PREFIX = "camera-snapshot-";

    private final String cameraName;   // e.g. "tuerkamera", "keller"
    private final String source;       // RTSP URL or HTTP snapshot URL
    private final Path outputDir;

    /**
     * @param cameraName human-readable camera identifier
     * @param source     ffmpeg-compatible input URL (RTSP rtsp://... or HTTP http://.../snapshot)
     */
    public CameraSnapshotAction(String cameraName, String source) {
        this(cameraName, source, Path.of("/tmp"));
    }

    public CameraSnapshotAction(String cameraName, String source, Path outputDir) {
        this.cameraName = cameraName;
        this.source = source;
        this.outputDir = outputDir;
    }

    @Override
    public String name() {
        return NAME_PREFIX + cameraName;
    }

    @Override
    public String category() {
        return "read";
    }

    @Override
    public ApprovalLevel approvalLevel() {
        return ApprovalLevel.AUTO; // read-only observation
    }

    @Override
    public ActionResult execute() {
        var now = java.time.Instant.now();
        var timestamp = now.toString().replace(":", "-");
        var outputFile = outputDir.resolve("metis-camera-" + cameraName + "-" + timestamp + ".jpg");

        try {
            Files.createDirectories(outputDir);

            // Use ffmpeg for both RTSP and HTTP — handles H.265 decoding uniformly
            var cmd = List.of(
                    "ffmpeg", "-y", "-v", "error",
                    "-rtsp_transport", "tcp",
                    "-i", source,
                    "-frames:v", "1",
                    "-q:v", "3",
                    outputFile.toString()
            );

            LOG.fine(() -> "Capturing snapshot: " + cameraName + " → " + outputFile);

            var pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            var process = pb.start();

            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ActionResult.fail(name(), "Snapshot timed out after 15s: " + cameraName, now);
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String stderr = new String(process.getInputStream().readAllBytes());
                return ActionResult.fail(name(),
                        "ffmpeg exit " + exitCode + " for " + cameraName + ": " + stderr, now);
            }

            if (!Files.exists(outputFile) || Files.size(outputFile) == 0) {
                return ActionResult.fail(name(), "Snapshot file empty/missing: " + cameraName, now);
            }

            long fileSize = Files.size(outputFile);
            long elapsedMs = java.time.Duration.between(now, java.time.Instant.now()).toMillis();

            var body = String.format(
                    "{\"camera\":\"%s\",\"snapshot\":\"%s\",\"size_bytes\":%d,\"elapsed_ms\":%d,\"timestamp\":\"%s\"}",
                    cameraName, outputFile, fileSize, elapsedMs, now
            );

            LOG.info(() -> "Snapshot captured: " + cameraName + " (" + fileSize + " bytes, " + elapsedMs + "ms)");
            return ActionResult.ok(name(), body, now);

        } catch (IOException e) {
            return ActionResult.fail(name(), "I/O error for " + cameraName + ": " + e.getMessage(), now);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ActionResult.fail(name(), "Interrupted capturing " + cameraName, now);
        }
    }
}
