package de.metis.modules.monitor;

import de.metis.kernel.action.ActionResult;
import de.metis.kernel.goal.Goal;
import de.metis.kernel.world.WorldModel;
import de.metis.kernel.workspace.WorkspaceShadowLogger;
import de.metis.modules.evolution.ModelRegistry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Proactive resource auto-tuner — lets Metis detect and respond to
 * memory/VRAM pressure without operator intervention.
 * <p>
 * <b>Three responsibilities:</b>
 * <ul>
 *   <li>Heap self-protection via {@link de.metis.kernel.monitor.MemoryPressureGuard}</li>
 *   <li>VRAM orchestration: unload stale models under pressure, preload useful ones when idle</li>
 *   <li>System-idle resource grabbing: increase caches, load models when CPU/VRAM are free</li>
 * </ul>
 * <p>
 * Registered as {@code resource-auto-tune} in the action registry.
 * Designed to be pulled by the Planner during idle ticks or resource-pressure goals.
 */
public class ResourceAutoTuner {

    private static final Logger LOG = Logger.getLogger(ResourceAutoTuner.class.getName());

    private final de.metis.kernel.monitor.MemoryPressureGuard memoryGuard;
    private final WorldModel worldModel;
    private final WorkspaceShadowLogger workspaceShadow;
    private final ModelRegistry modelRegistry;
    private final String ollamaBaseUrl;

    /** Prevent tuning decisions from firing too often. */
    private long lastVramCheckMs = 0;
    private static final long VRAM_CHECK_INTERVAL_MS = 60_000;
    private long lastIdleActionMs = 0;
    private static final long IDLE_ACTION_INTERVAL_MS = 5 * 60_000;

    /** Track which models we've suggested unloading. */
    private final Set<String> unloadCandidates = ConcurrentHashMap.newKeySet();

    public ResourceAutoTuner(de.metis.kernel.monitor.MemoryPressureGuard memoryGuard,
                             SystemHealthProbe healthProbe,
                             WorldModel worldModel,
                             WorkspaceShadowLogger workspaceShadow,
                             ModelRegistry modelRegistry,
                             String ollamaBaseUrl) {
        this.memoryGuard = memoryGuard;
        this.worldModel = worldModel;
        this.workspaceShadow = workspaceShadow;
        this.modelRegistry = modelRegistry;
        this.ollamaBaseUrl = ollamaBaseUrl.endsWith("/")
                ? ollamaBaseUrl.substring(0, ollamaBaseUrl.length() - 1) : ollamaBaseUrl;
    }

    /**
     * Called every tick or periodically. Evaluates all three dimensions
     * and takes action when thresholds are crossed.
     */
    public String tune() {
        StringBuilder report = new StringBuilder();

        // ── 1. Heap pressure (fast path, runs every tick) ─────
        memoryGuard.check(workspaceShadow);
        report.append("heap:").append(memoryGuard.level());

        // ── 2. VRAM orchestration (every 60s) ─────────────────
        long now = System.currentTimeMillis();
        if (now - lastVramCheckMs >= VRAM_CHECK_INTERVAL_MS) {
            lastVramCheckMs = now;
            String vramAction = tuneVram();
            if (vramAction != null) report.append(" vram:").append(vramAction);
        }

        // ── 3. Idle resource grabbing (every 5 min) ───────────
        if (isSystemIdle() && now - lastIdleActionMs >= IDLE_ACTION_INTERVAL_MS) {
            lastIdleActionMs = now;
            String idleAction = preloadIdleModel();
            if (idleAction != null) report.append(" idle:").append(idleAction);
        }

        return report.toString();
    }

    // ── VRAM Tuning ──────────────────────────────────────────────

    private String tuneVram() {
        try {
            double vramGb = queryVramUsed();
            if (vramGb < 0) return null;

            // Update world model
            worldModel.update("vram:used=" + String.format("%.1f", vramGb) + "gb",
                    0.9, "resource-auto-tuner", true);

            // ── VRAM near full (>22 GB): unload least-used model ──
            if (vramGb > 22.0) {
                return unloadLeastUsedModel();
            }

            // ── VRAM underutilised (<15 GB) + idle: preload useful model ──
            if (vramGb < 15.0 && isSystemIdle()) {
                return preloadIdleModel();
            }

            return "ok";
        } catch (Exception e) {
            LOG.fine("VRAM tune failed: " + e.getMessage());
            return null;
        }
    }

    private double queryVramUsed() {
        try {
            String raw = exec("rocm-smi --showmeminfo vram --json 2>/dev/null");
            if (raw == null || !raw.contains("VRAM")) return -1;
            // Quick JSON extraction — key may be "VRAM Total Used Memory (B)" or "Total Used Memory (B)"
            int idx = raw.indexOf("VRAM Total Used Memory");
            if (idx < 0) idx = raw.indexOf("Total Used Memory");
            if (idx < 0) return -1;
            int colon = raw.indexOf(':', idx);
            if (colon < 0) return -1;
            // Skip quotes/whitespace to get number
            int start = colon + 1;
            while (start < raw.length() && !Character.isDigit(raw.charAt(start))) start++;
            int end = start;
            while (end < raw.length() && (Character.isDigit(raw.charAt(end)) || raw.charAt(end) == '.')) end++;
            return Double.parseDouble(raw.substring(start, end)) / 1e9;
        } catch (Exception e) {
            return -1;
        }
    }

    private String unloadLeastUsedModel() {
        try {
            // Get currently loaded models
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaBaseUrl + "/api/ps"))
                    .timeout(Duration.ofSeconds(5))
                    .GET().build();
            HttpResponse<String> resp = HttpClient.newHttpClient()
                    .send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return "ps-fail";

            // Find the least essential model to unload
            String candidate = findUnloadCandidate(resp.body());
            if (candidate == null) {
                LOG.warning("VRAM full but no safe unload candidate found");
                return "no-candidate";
            }

            // Unload by sending a tiny request with keep_alive=0
            unloadModel(candidate);
            unloadCandidates.add(candidate);
            worldModel.update("vram:unloaded=" + candidate,
                    0.95, "resource-auto-tuner", true);
            LOG.warning("✂️ VRAM pressure: unloaded " + candidate + " via keep_alive=0");
            return "unloaded:" + candidate;

        } catch (Exception e) {
            LOG.fine("VRAM unload failed: " + e.getMessage());
            return "error";
        }
    }

    /** Pick the least critical loaded model to evict. Never evict the planning model. */
    private String findUnloadCandidate(String psJson) {
        // Parse model names from Ollama /api/ps response
        List<String> models = new ArrayList<>();
        int pos = 0;
        while ((pos = psJson.indexOf("\"name\":\"", pos)) >= 0) {
            pos += 8;
            int end = psJson.indexOf('"', pos);
            if (end > pos) models.add(psJson.substring(pos, end));
            pos = end + 1;
        }

        String planningModel = modelRegistry.planningModel();

        // Prefer to evict in this order (least critical first):
        // 1. Models we previously marked for unload
        // 2. Non-planning, non-embedding models
        // 3. The embedding model (only if desperate)
        for (String m : models) {
            if (unloadCandidates.contains(m)) return m;
        }
        for (String m : models) {
            if (!m.equals(planningModel) && !m.contains("embed")) return m;
        }
        for (String m : models) {
            if (m.contains("embed") && !m.equals(planningModel)) return m;
        }
        return null;
    }

    /** Send a minimal request with keep_alive=0 to trigger model unload. */
    private void unloadModel(String modelName) {
        try {
            String body = "{\"model\":\"" + modelName
                    + "\",\"prompt\":\".\",\"stream\":false,\"keep_alive\":0}";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaBaseUrl + "/api/generate"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            LOG.fine("Unload request failed (non-critical): " + e.getMessage());
        }
    }

    // ── Idle Resource Grabbing ────────────────────────────────────

    private String preloadIdleModel() {
        try {
            // Find a useful small model that isn't loaded
            String planningModel = modelRegistry.planningModel();
            String[] idleCandidates = {"granite4.1:3b", "phi4-mini:latest", "nomic-embed-text"};
            String loaded = queryLoadedModels();

            for (String candidate : idleCandidates) {
                if (loaded != null && loaded.contains(candidate)) continue;
                if (candidate.equals(planningModel)) continue;

                // Preload with keep_alive=30m
                String body = "{\"model\":\"" + candidate
                        + "\",\"prompt\":\".\",\"stream\":false,\"keep_alive\":\"30m\"}";
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(ollamaBaseUrl + "/api/generate"))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.discarding());

                worldModel.update("vram:preloaded=" + candidate,
                        0.85, "resource-auto-tuner", true);
                LOG.info("⏳ Idle preload: " + candidate + " (keep_alive=30m)");
                return "preloaded:" + candidate;
            }
            return null;
        } catch (Exception e) {
            LOG.fine("Idle preload failed: " + e.getMessage());
            return null;
        }
    }

    private String queryLoadedModels() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaBaseUrl + "/api/ps"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> resp = HttpClient.newHttpClient()
                    .send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 ? resp.body() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Check if system CPU is idle (loadavg < 1.0 on 16-thread Ryzen). */
    private boolean isSystemIdle() {
        try {
            String load = exec("cat /proc/loadavg 2>/dev/null | cut -d' ' -f1");
            if (load == null || load.isBlank()) return false;
            double load1 = Double.parseDouble(load.trim());
            return load1 < 1.0;
        } catch (Exception e) {
            return false;
        }
    }

    private String exec(String cmd) {
        try {
            Process p = new ProcessBuilder("sh", "-c", cmd)
                    .redirectErrorStream(true).start();
            p.waitFor(5, TimeUnit.SECONDS);
            return new String(p.getInputStream().readAllBytes());
        } catch (Exception e) {
            return null;
        }
    }

    // ── Public state ─────────────────────────────────────────────

    public de.metis.kernel.monitor.MemoryPressureGuard memoryGuard() { return memoryGuard; }
    public String summary() {
        return "ResourceAutoTuner(heap=" + memoryGuard.level()
                + " " + String.format("%.0f%%", memoryGuard.heapFraction() * 100) + ")";
    }
}
