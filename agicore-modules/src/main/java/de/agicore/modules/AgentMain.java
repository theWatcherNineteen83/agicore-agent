package de.agicore.modules;

import de.agicore.kernel.core.AgentCoreLoop;
import de.agicore.kernel.evolution.EvolutionManager;
import de.agicore.kernel.metrics.PerformanceMetrics;
import de.agicore.kernel.planner.Planner;
import de.agicore.modules.evolution.ModelRegistry;
import de.agicore.modules.evolution.OllamaMutationService;
import de.agicore.modules.planner.OllamaPlanner;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.*;

/**
 * Step E: Continuous autonomous agent runtime.
 * <p>
 * Unlike {@link DemoMain} (finite ticks), AgentMain runs indefinitely:
 * <ul>
 *   <li>Cognitive loop with configurable tick interval</li>
 *   <li>Sleep/wake cycles (active/rest phases)</li>
 *   <li>Graceful shutdown on SIGTERM/SIGINT</li>
 *   <li>Periodic state persistence (resume after restart)</li>
 *   <li>Autonomous goal generation during idle phases</li>
 *   <li>Emergence watcher — logs unusual patterns</li>
 *   <li>Evolution triggered by stagnation, not manual</li>
 * </ul>
 * <p>
 * Usage: {@code java -cp ... de.agicore.modules.AgentMain [--interval 5000] [--persist agent-state.json]}
 */
public final class AgentMain {

    private static final Logger LOG = Logger.getLogger(AgentMain.class.getName());

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    // ── Configuration ─────────────────────────────────────────
    private final long tickIntervalMs;
    private final Path persistPath;
    private final boolean enableEvolution;
    private final int idleGoalInterval;     // ticks between auto-generated goals
    private final long emergenceReportInterval; // ticks between emergence reports

    // ── Runtime state ─────────────────────────────────────────
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Agent agent;
    private final ScheduledExecutorService scheduler;
    private Instant startedAt;
    private long totalTicks = 0;
    private long idleTicks = 0;
    private long evolutionTicks = 0;

    // ── Emergence tracking ────────────────────────────────────
    private final List<String> emergenceEvents = new ArrayList<>();
    private int consecutiveFailures = 0;
    private double lastSuccessRate = 1.0;
    private long lastEmergenceReportTick = 0;

    private AgentMain(Builder builder) {
        this.tickIntervalMs = builder.tickIntervalMs;
        this.persistPath = builder.persistPath;
        this.enableEvolution = builder.enableEvolution;
        this.idleGoalInterval = builder.idleGoalInterval;
        this.emergenceReportInterval = builder.emergenceReportInterval;
        this.agent = builder.agent;

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agicore-agent");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start the autonomous agent loop. Blocks until shutdown.
     */
    public void run() throws Exception {
        startedAt = Instant.now();
        lastEmergenceReportTick = 0;

        LOG.info("╔══════════════════════════════════════════╗");
        LOG.info("║   AGI Core — Autonomous Agent Runtime   ║");
        LOG.info("║   Step E: Emergence Experiments         ║");
        LOG.info("╚══════════════════════════════════════════╝");
        LOG.info("Tick interval: " + tickIntervalMs + "ms");
        LOG.info("Persistence:   " + (persistPath != null ? persistPath : "disabled"));
        LOG.info("Evolution:     " + (enableEvolution ? "enabled" : "disabled"));
        LOG.info("Goals:         " + agent.goals().activeCount() + " active, idle generation every "
                + idleGoalInterval + " ticks\n");

        // ── Register shutdown hook ────────────────────────────
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutdown signal received — persisting state...");
            running.set(false);
            try {
                persistState();
                printSummary();
            } catch (Exception e) {
                LOG.warning("Shutdown persist failed: " + e.getMessage());
            }
        }, "agicore-shutdown"));

        // ── Resume from persisted state ───────────────────────
        if (persistPath != null && Files.exists(persistPath)) {
            try {
                resumeState();
                LOG.info("Resumed from " + persistPath);
            } catch (Exception e) {
                LOG.warning("Failed to resume state: " + e.getMessage());
            }
        }

        // ── Main loop ─────────────────────────────────────────
        long lastPersist = 0;
        long lastStatusReport = 0;
        long lastEvolution = 0;

        while (running.get()) {
            try {
                // ── Tick ──────────────────────────────────────
                var result = agent.core().tick();
                totalTicks++;

                if (result == null) {
                    idleTicks++;
                } else {
                    // Emergence detection: track anomalies
                    detectEmergence(result, totalTicks);
                }

                // ── Autonomous goal generation (idle exploration) ──
                if (totalTicks % idleGoalInterval == 0) {
                    generateAutonomousGoal();
                }

                // ── Persistence ───────────────────────────────
                if (persistPath != null && totalTicks - lastPersist >= 100) {
                    persistState();
                    lastPersist = totalTicks;
                }

                // ── Status report ─────────────────────────────
                if (totalTicks - lastStatusReport >= 50) {
                    logStatusReport();
                    lastStatusReport = totalTicks;
                }

                // ── Emergence report ──────────────────────────
                if (totalTicks - lastEmergenceReportTick >= emergenceReportInterval) {
                    logEmergenceReport();
                    lastEmergenceReportTick = totalTicks;
                }

                // ── Evolution check ───────────────────────────
                if (enableEvolution && totalTicks - lastEvolution >= 500) {
                    triggerEmergenceEvolution();
                    lastEvolution = totalTicks;
                }

                // ── Sleep ─────────────────────────────────────
                if (running.get()) {
                    Thread.sleep(tickIntervalMs);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.info("Agent interrupted — shutting down");
                break;
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Tick " + totalTicks + " threw exception", e);
                consecutiveFailures++;
                if (consecutiveFailures > 10) {
                    LOG.severe("Too many consecutive failures — emergency stop");
                    break;
                }
                // Backoff on errors
                Thread.sleep(tickIntervalMs * 2);
            }
        }

        persistState();
        printSummary();
    }

    // ── Autonomous goal generation ────────────────────────────

    private void generateAutonomousGoal() {
        var goals = agent.goals();
        if (goals.activeCount() >= 20) return; // don't flood

        var exec = agent.core().executor();
        var wm = agent.worldModel();

        // Curiosity-driven: pick an unexplored action
        Set<String> available = exec.availableActions();
        List<String> unexplored = new ArrayList<>();
        for (String action : available) {
            var beliefs = wm.query(action, 3);
            if (beliefs.isEmpty() || beliefs.stream().allMatch(b -> b.confidence() < 0.5)) {
                unexplored.add(action);
            }
        }

        if (!unexplored.isEmpty()) {
            String explore = unexplored.get(new Random().nextInt(unexplored.size()));
            String desc = switch (explore) {
                case "filesystem-read" -> "Explore filesystem via read";
                case "filesystem-list" -> "Explore filesystem via list";
                case "memory-query" -> "Query long-term memory";
                case "self-analyze" -> "Analyze own performance";
                default -> "Explore " + explore + " capability";
            };
            agent.addGoal(desc, "exploration", 25, 0.3, 2);
            LOG.fine("Auto-generated exploration goal: " + desc);
        }

        // Meta-cognitive: if confidence is low, create diagnostic goal
        if (agent.meta().confidence() < 0.3 && idleTicks > 10) {
            agent.addGoal("Diagnose low agent confidence", "meta", 50, 0.4, 1);
            LOG.fine("Auto-generated meta goal: Diagnose low confidence");
        }
    }

    // ── Emergence detection ───────────────────────────────────

    private void detectEmergence(de.agicore.kernel.action.ActionResult result, long tick) {
        double currentRate = agent.metrics().goalSuccessRate();

        // Pattern: sudden success rate drop
        if (lastSuccessRate - currentRate > 0.3 && totalTicks > 20) {
            String event = String.format("T%d: Success rate dropped %.0f%% → %.0f%% (emergent instability)",
                    tick, lastSuccessRate * 100, currentRate * 100);
            emergenceEvents.add(event);
            LOG.warning("EMERGENCE: " + event);
        }

        // Pattern: learned behavior reversal
        if (result != null && !result.success()) {
            consecutiveFailures++;
            if (consecutiveFailures >= 5) {
                String event = String.format("T%d: %d consecutive failures — learned strategy collapsed",
                        tick, consecutiveFailures);
                emergenceEvents.add(event);
                LOG.warning("EMERGENCE: " + event);
            }
        } else {
            consecutiveFailures = 0;
        }

        // Pattern: unexpected action diversity
        if (idleTicks > 20 && totalTicks > 50) {
            String event = String.format("T%d: High idle ratio %.1f%% — agent may be in exploration loop",
                    tick, 100.0 * idleTicks / totalTicks);
            if (emergenceEvents.size() < 100) emergenceEvents.add(event);
            LOG.fine("EMERGENCE: " + event);
        }

        // Pattern: world model growth surge
        int beliefCount = agent.worldModel().beliefCount();
        if (beliefCount > 50 && beliefCount % 20 == 0) {
            String event = String.format("T%d: World model reached %d beliefs — potential concept formation",
                    tick, beliefCount);
            emergenceEvents.add(event);
            LOG.info("EMERGENCE: " + event);
        }

        lastSuccessRate = currentRate;
    }

    private void triggerEmergenceEvolution() {
        double fitness = de.agicore.kernel.evolution.FitnessFunction.evaluate(
                agent.metrics(),
                agent.workspace().runningEntropy());
        var evo = agent.core().evolutionManager();

        if (evo.shouldEvolve(totalTicks, fitness)) {
            LOG.info("Emergence-triggered evolution at tick " + totalTicks);
            var evoResult = evo.evolve(fitness);
            LOG.info("Evolution result: " + evoResult);

            String event = String.format("T%d: Emergence-triggered evolution — %s",
                    totalTicks, evoResult.message());
            emergenceEvents.add(event);
        }
    }

    // ── Persistence ───────────────────────────────────────────

    private void persistState() throws IOException {
        if (persistPath == null) return;

        var state = new AgentState(
                Instant.now(),
                totalTicks,
                agent.goals().activeCount(),
                agent.metrics().goalSuccessRate(),
                agent.metrics().planningEfficiency(),
                agent.meta().confidence(),
                agent.worldModel().beliefCount(),
                agent.core().evolutionManager().acceptedMutations(),
                agent.core().evolutionManager().rejectedMutations(),
                emergenceEvents
        );

        Files.writeString(persistPath, state.toJson());
        LOG.fine("State persisted to " + persistPath);
    }

    private void resumeState() throws IOException {
        String json = Files.readString(persistPath);
        var state = AgentState.fromJson(json);
        LOG.info("Resumed: ticks=" + state.totalTicks
                + " successRate=" + String.format("%.0f%%", state.successRate * 100)
                + " confidence=" + String.format("%.2f", state.confidence)
                + " mutations=" + state.acceptedMutations + "/" + state.rejectedMutations);
        this.totalTicks = state.totalTicks;
    }

    // ── Reporting ─────────────────────────────────────────────

    private void logStatusReport() {
        var m = agent.metrics();
        var planner = agent.planner();
        String plannerInfo = "";
        if (planner instanceof OllamaPlanner op) {
            plannerInfo = String.format(" llm=%.0f%% fallbacks=%d",
                    op.llmSuccessRate() * 100, op.fallbackUses());
        }

        LOG.info(String.format(
                "Status T%d | %s running | goals=%d succ=%.0f%% plan=%.0f%% conf=%.2f | "
                        + "beliefs=%d evo=%d/%d%s | idle=%.0f%%",
                totalTicks,
                formatDuration(Duration.between(startedAt, Instant.now())),
                agent.goals().activeCount(),
                m.goalSuccessRate() * 100,
                m.planningEfficiency() * 100,
                agent.meta().confidence(),
                agent.worldModel().beliefCount(),
                agent.core().evolutionManager().acceptedMutations(),
                agent.core().evolutionManager().rejectedMutations(),
                plannerInfo,
                100.0 * idleTicks / Math.max(1, totalTicks)
        ));
    }

    private void logEmergenceReport() {
        if (emergenceEvents.isEmpty()) return;

        int since = Math.max(0, emergenceEvents.size() - 10);
        var recent = emergenceEvents.subList(since, emergenceEvents.size());

        LOG.info("═══ Emergence Report (last " + (emergenceEvents.size() - since)
                + " of " + emergenceEvents.size() + " events) ═══");
        for (String event : recent) {
            LOG.info("  " + event);
        }

        // Detect meta-patterns
        long instabilityEvents = emergenceEvents.stream()
                .filter(e -> e.contains("instability") || e.contains("collapse")).count();
        long explorationEvents = emergenceEvents.stream()
                .filter(e -> e.contains("exploration")).count();

        if (instabilityEvents > 5) {
            LOG.warning("EMERGENCE META-PATTERN: Repeated instability — agent may be oscillating");
        }
        if (explorationEvents > 20) {
            LOG.info("EMERGENCE META-PATTERN: Extensive exploration — agent developing new behaviors");
        }
    }

    private void printSummary() {
        Instant started = startedAt != null ? startedAt : Instant.now();
        Duration uptime = Duration.between(started, Instant.now());
        LOG.info("\n╔══════════════════════════════════════════╗");
        LOG.info("║   AGI Core — Run Summary                ║");
        LOG.info("╚══════════════════════════════════════════╝");
        LOG.info("Uptime:        " + formatDuration(uptime));
        LOG.info("Total ticks:   " + totalTicks);
        LOG.info("Goals:         " + agent.goals().activeCount() + " remaining");
        LOG.info("Success rate:  " + String.format("%.0f%%", agent.metrics().goalSuccessRate() * 100));
        LOG.info("Plan effic.:   " + String.format("%.0f%%", agent.metrics().planningEfficiency() * 100));
        LOG.info("Confidence:    " + String.format("%.2f", agent.meta().confidence()));
        LOG.info("Beliefs:       " + agent.worldModel().beliefCount());
        LOG.info("Idle ratio:    " + String.format("%.0f%%", 100.0 * idleTicks / Math.max(1, totalTicks)));
        LOG.info("Mutations:     " + agent.core().evolutionManager().acceptedMutations()
                + " accepted / " + agent.core().evolutionManager().rejectedMutations() + " rejected");
        LOG.info("Emergence:     " + emergenceEvents.size() + " events");

        var planner = agent.planner();
        if (planner instanceof OllamaPlanner op) {
            LOG.info("LLM calls:     " + op.llmCalls() + " ("
                    + String.format("%.0f%%", op.llmSuccessRate() * 100) + " success, "
                    + op.fallbackUses() + " fallbacks)");
        }

        LOG.info("╚══════════════════════════════════════════╝\n");
    }

    // ── Utility ───────────────────────────────────────────────

    private static String formatDuration(Duration d) {
        long hours = d.toHours();
        long minutes = d.toMinutes() % 60;
        long seconds = d.getSeconds() % 60;
        if (hours > 0) return String.format("%dh %dm %ds", hours, minutes, seconds);
        if (minutes > 0) return String.format("%dm %ds", minutes, seconds);
        return seconds + "s";
    }

    // ── AgentState record (JSON persistence) ──────────────────

    private record AgentState(
            Instant timestamp,
            long totalTicks,
            int activeGoals,
            double successRate,
            double planningEfficiency,
            double confidence,
            int beliefCount,
            int acceptedMutations,
            int rejectedMutations,
            List<String> emergenceEvents) {

        String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"timestamp\": \"").append(timestamp).append("\",\n");
            sb.append("  \"totalTicks\": ").append(totalTicks).append(",\n");
            sb.append("  \"activeGoals\": ").append(activeGoals).append(",\n");
            sb.append("  \"successRate\": ").append(String.format("%.4f", successRate)).append(",\n");
            sb.append("  \"planningEfficiency\": ").append(String.format("%.4f", planningEfficiency)).append(",\n");
            sb.append("  \"confidence\": ").append(String.format("%.4f", confidence)).append(",\n");
            sb.append("  \"beliefCount\": ").append(beliefCount).append(",\n");
            sb.append("  \"acceptedMutations\": ").append(acceptedMutations).append(",\n");
            sb.append("  \"rejectedMutations\": ").append(rejectedMutations).append(",\n");
            sb.append("  \"emergenceEvents\": [\n");
            for (int i = 0; i < Math.min(emergenceEvents.size(), 50); i++) {
                sb.append("    \"").append(emergenceEvents.get(i).replace("\"", "\\\"")).append("\"");
                if (i < Math.min(emergenceEvents.size(), 50) - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("  ]\n");
            sb.append("}\n");
            return sb.toString();
        }

        static AgentState fromJson(String json) {
            return new AgentState(
                    Instant.now(),
                    extractLong(json, "totalTicks"),
                    (int) extractLong(json, "activeGoals"),
                    extractDouble(json, "successRate"),
                    extractDouble(json, "planningEfficiency"),
                    extractDouble(json, "confidence"),
                    (int) extractLong(json, "beliefCount"),
                    (int) extractLong(json, "acceptedMutations"),
                    (int) extractLong(json, "rejectedMutations"),
                    List.of()
            );
        }

        private static long extractLong(String json, String key) {
            String search = "\"" + key + "\":";
            int idx = json.indexOf(search);
            if (idx < 0) return 0;
            idx += search.length();
            while (idx < json.length() && (json.charAt(idx) == ' ' || json.charAt(idx) == '\t')) idx++;
            StringBuilder num = new StringBuilder();
            while (idx < json.length() && (Character.isDigit(json.charAt(idx)) || json.charAt(idx) == '-')) {
                num.append(json.charAt(idx++));
            }
            try { return Long.parseLong(num.toString()); } catch (NumberFormatException e) { return 0; }
        }

        private static double extractDouble(String json, String key) {
            String search = "\"" + key + "\":";
            int idx = json.indexOf(search);
            if (idx < 0) return 0;
            idx += search.length();
            while (idx < json.length() && (json.charAt(idx) == ' ' || json.charAt(idx) == '\t')) idx++;
            StringBuilder num = new StringBuilder();
            while (idx < json.length() && (Character.isDigit(json.charAt(idx)) || json.charAt(idx) == '.' || json.charAt(idx) == '-')) {
                num.append(json.charAt(idx++));
            }
            try { return Double.parseDouble(num.toString()); } catch (NumberFormatException e) { return 0; }
        }
    }

    // ── Builder ───────────────────────────────────────────────

    public static Builder builder(Agent agent) {
        return new Builder(agent);
    }

    public static class Builder {
        private final Agent agent;
        private long tickIntervalMs = 3000;
        private Path persistPath = null;
        private boolean enableEvolution = false; // off by default for safety
        private int idleGoalInterval = 15;
        private long emergenceReportInterval = 100;

        Builder(Agent agent) { this.agent = agent; }

        /** Tick interval in milliseconds. */
        public Builder tickInterval(long ms) { this.tickIntervalMs = ms; return this; }

        /** Path for state persistence JSON. */
        public Builder persistTo(Path path) { this.persistPath = path; return this; }

        /** Enable evolution mutations during run. */
        public Builder withEvolution() { this.enableEvolution = true; return this; }

        /** Ticks between autonomous goal generation. */
        public Builder idleGoalInterval(int ticks) { this.idleGoalInterval = ticks; return this; }

        /** Ticks between emergence reports. */
        public Builder emergenceReportInterval(long ticks) { this.emergenceReportInterval = ticks; return this; }

        public AgentMain build() { return new AgentMain(this); }
    }

    // ── Main entry point ──────────────────────────────────────

    public static void main(String[] args) throws Exception {
        // Parse CLI args
        long interval = 3000;
        Path persist = null;
        boolean evolution = false;
        int maxTicks = 0; // 0 = unlimited

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--interval" -> interval = Long.parseLong(args[++i]);
                case "--persist" -> persist = Path.of(args[++i]);
                case "--evolution" -> evolution = true;
                case "--max-ticks" -> maxTicks = Integer.parseInt(args[++i]);
                case "--help", "-h" -> {
                    System.out.println("""
                            AGI Core — Autonomous Agent Runtime (Step E)
                            Options:
                              --interval N     Tick interval in ms (default: 3000)
                              --persist PATH   State persistence file
                              --evolution      Enable self-evolution
                              --max-ticks N    Stop after N ticks (default: unlimited)
                            """);
                    return;
                }
            }
        }

        // Configure logging
        Logger.getLogger("").setLevel(Level.WARNING);
        Logger.getLogger("de.agicore").setLevel(Level.INFO);

        // Discover models and build agent with auto-selection
        var modelRegistry = new ModelRegistry("http://192.168.22.204:11434").discover();

        Agent agent = Agent.builder()
                .registerShellCommand(List.of("uname", "-a"))
                .registerHttpGet(URI.create("https://httpbin.org/get"))
                .ollamaPlanner("http://192.168.22.204:11434/api/generate", modelRegistry, Duration.ofSeconds(60))
                .workspaceCapacity(7)
                .build();

        // Register filesystem actions (kernel extensibility)
        agent.core().executor().register(
                new de.agicore.kernel.action.FileSystemAction("filesystem-list",
                        de.agicore.kernel.action.FileSystemAction.Mode.LIST,
                        Path.of(System.getProperty("user.home", "/tmp"))));

        agent.core().executor().register(
                new de.agicore.kernel.action.FileSystemAction("filesystem-read",
                        de.agicore.kernel.action.FileSystemAction.Mode.READ,
                        Path.of("/tmp")));

        // Inject Ollama mutation service if evolution enabled
        if (evolution) {
            var ollama = new OllamaMutationService(modelRegistry);
            agent.core().evolutionManager().setMutationService(ollama);
            LOG.info("Evolution enabled — OllamaMutationService (auto-selected: " + ollama.currentModel() + ")");
        }

        // Bootstrap world model
        agent.worldModel().update("shell actions execute reliably", 0.95, "bootstrap", true);
        agent.worldModel().update("http actions work for health checks", 0.9, "bootstrap", true);
        agent.worldModel().update("filesystem-list explores directory contents", 0.8, "bootstrap", true);
        agent.worldModel().update("filesystem-read retrieves file contents", 0.75, "bootstrap", true);

        // Initial goals
        agent.addGoal("Check system status via shell", "shell", 85, 0.9, 1);
        agent.addGoal("HTTP health check request", "http", 70, 0.8, 2);

        // Build and run
        var runtime = AgentMain.builder(agent)
                .tickInterval(interval)
                .persistTo(persist)
                .idleGoalInterval(10)
                .emergenceReportInterval(50)
                .build();

        if (evolution) runtime = AgentMain.builder(agent)
                .tickInterval(interval)
                .persistTo(persist)
                .withEvolution()
                .idleGoalInterval(10)
                .emergenceReportInterval(50)
                .build();

        try {
            if (maxTicks > 0) {
                // Limited run for testing
                LOG.info("Running " + maxTicks + " ticks...");
                for (int i = 0; i < maxTicks && runtime.running.get(); i++) {
                    agent.core().tick();
                }
                runtime.persistState();
                runtime.printSummary();
            } else {
                runtime.run();
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Agent runtime crashed", e);
            runtime.persistState();
        }
    }
}
