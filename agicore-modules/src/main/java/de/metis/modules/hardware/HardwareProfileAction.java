package de.metis.modules.hardware;

import de.metis.kernel.action.Action;
import de.metis.kernel.action.ActionResult;
import de.metis.modules.Agent;

import java.time.Instant;

/**
 * Action that profiles the current hardware state.
 * <p>
 * Measures CPU load, memory pressure, JVM heap usage, and system load.
 * Returns a structured result that the agent can use for optimization decisions.
 */
public class HardwareProfileAction implements Action {

    private static final String NAME = "hw-profile";

    private final Agent agent;

    public HardwareProfileAction(Agent agent) {
        this.agent = agent;
    }

    @Override
    public String name() { return NAME; }

    @Override
    public ActionResult execute() {
        Instant start = Instant.now();
        try {
            var snapshot = takeSnapshot();
            var profile = HardwareDiscovery.discover();

            String result = String.format("""
                    === Hardware Snapshot ===
                    CPU: %.1f%% load, %s
                    RAM: %d MB used / %d MB total (%.1f%%)
                    JVM: %d MB heap used / %d MB max
                    Load: %s
                    GPU: %s
                    """,
                    snapshot.cpuPercent(), profile.cpu().model(),
                    snapshot.usedRamMb(), profile.totalRamMb(), snapshot.memPercent(),
                    snapshot.heapUsedMb(), snapshot.heapMaxMb(),
                    snapshot.loadAvg(),
                    profile.gpus().isEmpty() ? "none detected" : profile.gpus().getFirst().model());

            return ActionResult.ok(NAME, result, start);
        } catch (Exception e) {
            return ActionResult.fail(NAME, e.getMessage(), start);
        }
    }

    // ── Live Snapshot ──────────────────────────────────────────────

    record Snapshot(double cpuPercent, long usedRamMb, double memPercent,
                    long heapUsedMb, long heapMaxMb, String loadAvg) {}

    private Snapshot takeSnapshot() {
        Runtime rt = Runtime.getRuntime();
        long heapUsed = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long heapMax = rt.maxMemory() / (1024 * 1024);

        var profile = HardwareDiscovery.discover();
        long totalRam = profile.totalRamMb();
        long availRam = profile.availableRamMb();
        long usedRam = totalRam - availRam;
        double memPct = totalRam > 0 ? (usedRam * 100.0 / totalRam) : 0;

        double cpuPct = readCpuLoad();
        String loadAvg = readLoadAvg();

        return new Snapshot(cpuPct, usedRam, memPct, heapUsed, heapMax, loadAvg);
    }

    private double readCpuLoad() {
        try {
            String stat = java.nio.file.Files.readString(java.nio.file.Path.of("/proc/stat"));
            String[] parts = stat.split("\n")[0].replace("cpu  ", "").split("\\s+");
            long total = 0, idle = 0;
            for (int i = 0; i < parts.length; i++) {
                long val = Long.parseLong(parts[i]);
                total += val;
                if (i == 3 || i == 4) idle += val; // idle + iowait
            }
            if (total == 0) return 0;
            // This is cumulative since boot — approximate from a single read
            return 100.0 * (1.0 - (double) idle / total);
        } catch (Exception e) {
            return -1;
        }
    }

    private String readLoadAvg() {
        try {
            String line = java.nio.file.Files.readString(java.nio.file.Path.of("/proc/loadavg"));
            return line.split(" ")[0] + ", " + line.split(" ")[1] + ", " + line.split(" ")[2];
        } catch (Exception e) {
            return "N/A";
        }
    }
}
