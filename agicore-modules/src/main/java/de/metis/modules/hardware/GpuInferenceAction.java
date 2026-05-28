package de.metis.modules.hardware;

import de.metis.kernel.action.Action;
import de.metis.kernel.action.ActionResult;

import java.time.Instant;
import java.util.logging.Logger;

/**
 * GPU inference action using Panama FFM + OpenCL.
 * <p>
 * Discovers GPU, runs benchmark, and exposes GPU tensor operations
 * to Metis' cognitive engine. This enables direct GPU access without
 * HTTP round-trips to the Ollama server.
 * <p>
 * Category: write (allocates GPU memory)
 * Requires human approval: yes (GPU resource allocation)
 */
public class GpuInferenceAction implements Action {

    private static final Logger LOG = Logger.getLogger(GpuInferenceAction.class.getName());
    public static final String NAME = "gpu-inference";

    private final String mode; // "discover", "benchmark", "status"

    private static volatile OpenCLBridge bridge;

    public GpuInferenceAction(String mode) {
        this.mode = mode;
    }

    /** Shortcut: discover GPU and run benchmark. */
    public static GpuInferenceAction discover() {
        return new GpuInferenceAction("discover");
    }

    @Override public String name() { return NAME; }
    @Override public String category() { return "write"; }
    @Override
    public ApprovalLevel approvalLevel() {
        return ApprovalLevel.CONFIRM; // GPU resource-intensive write
    }

    @Override
    public ActionResult execute() {
        var now = Instant.now();
        try {
            if (bridge == null) {
                synchronized (GpuInferenceAction.class) {
                    if (bridge == null) {
                        bridge = new OpenCLBridge();
                    }
                }
            }

            return switch (mode) {
                case "discover" -> {
                    boolean ok = bridge.init();
                    if (ok) {
                        var bm = bridge.benchmark();
                        yield ActionResult.ok(NAME,
                                "GPU discovered: " + bridge.deviceName()
                                        + " (" + bridge.globalMemBytes() / (1024*1024*1024) + " GB VRAM, "
                                        + bridge.computeUnits() + " CUs)\n"
                                        + "Benchmark: " + bm,
                                now);
                    }
                    yield ActionResult.fail(NAME, "No GPU found via OpenCL", now);
                }
                case "status" -> ActionResult.ok(NAME,
                        bridge.status().toString(), now);
                case "benchmark" -> {
                    var bm = bridge.benchmark();
                    yield ActionResult.ok(NAME,
                            "GPU Benchmark: " + bm, now);
                }
                default -> ActionResult.fail(NAME, "Unknown mode: " + mode, now);
            };
        } catch (Exception e) {
            return ActionResult.fail(NAME, "GPU action error: " + e.getMessage(), now);
        }
    }

    /** Access the shared bridge instance (for other GPU-accelerated code). */
    public static OpenCLBridge bridge() {
        if (bridge == null) {
            synchronized (GpuInferenceAction.class) {
                if (bridge == null) {
                    bridge = new OpenCLBridge();
                    bridge.init();
                }
            }
        }
        return bridge;
    }
}
