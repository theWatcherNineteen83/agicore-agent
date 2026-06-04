package de.metis.modules;

import de.metis.kernel.core.AgentCoreLoop;
import de.metis.kernel.action.Crawl4AIAction;
import de.metis.kernel.action.NativeWebScraperAction;
import de.metis.kernel.action.WebSearchAction;
import de.metis.kernel.action.JlamaInferenceAction;
import de.metis.kernel.telemetry.TelemetryService;
import de.metis.kernel.graph.JenaRdfService;
import de.metis.kernel.action.McpBridgeAction;
import de.metis.kernel.action.WebCrawlAction;
import de.metis.kernel.action.LinuxExploreAction;
import de.metis.kernel.action.ApiExplorerAction;
import de.metis.kernel.action.JavaSandboxAction;
import de.metis.kernel.action.ReadSourceAction;
import de.metis.kernel.goal.KanbanBoard;
import de.metis.kernel.persistence.KnowledgeStore;
import de.metis.kernel.evolution.EvolutionManager;
import de.metis.kernel.evolution.EvolutionScheduler;
import de.metis.kernel.metrics.PerformanceMetrics;
import de.metis.kernel.planner.Planner;
import de.metis.modules.evolution.ModelRegistry;
import de.metis.modules.evolution.OllamaMutationService;
import de.metis.modules.evolution.OllamaEmbeddingService;
import de.metis.modules.planner.OllamaPlanner;
import de.metis.modules.telegram.TelegramBotService;
import de.metis.modules.events.EventTrigger;
import de.metis.modules.events.WeatherPollingTrigger;
import de.metis.modules.events.HAEventPoller;
import de.metis.modules.events.MqttEventService;
import de.metis.modules.events.AdsbPollingTrigger;
import de.metis.modules.events.WebcamPollingTrigger;
import de.metis.modules.events.CameraPollingTrigger;
import de.metis.modules.events.CameraPollingTrigger.CameraConfig;
import de.metis.modules.action.CameraVisionAction;
import de.metis.modules.action.CameraSnapshotAction;
import de.metis.modules.action.VideoAnalysisAction;
import de.metis.modules.events.ProactiveNotificationService;
import de.metis.modules.hardware.HardwareDiscovery;
import de.metis.modules.hardware.HardwareProfileAction;
import de.metis.modules.hardware.DeepNettsAction;
import de.metis.kernel.goal.Goal;
import de.metis.kernel.goal.KanbanBoard;
import de.metis.modules.knowledge.WikipediaKnowledgeService;
import de.metis.modules.knowledge.BookIngestionService;
import de.metis.kernel.self.EpisodicMemory;
import de.metis.kernel.self.SelfNarrative;
import de.metis.kernel.self.MoodSignal;
import de.metis.kernel.self.PersonalityAnchor;
import de.metis.kernel.self.DreamConsolidation;
import de.metis.kernel.self.SystemPromptBuilder;
import de.metis.kernel.goal.GoalHierarchy;
import de.metis.kernel.goal.HorizonPlanner;
import de.metis.kernel.goal.CommitmentRegister;
import de.metis.kernel.goal.CommitmentGuard;
import de.metis.kernel.workspace.WorkspaceShadowLogger;
import de.metis.modules.self.SelfReflector;
import de.metis.kernel.goal.GoalRevisionEngine;
import de.metis.kernel.goal.LongHorizonGoal;
import de.metis.kernel.goal.GoalHorizon;
import de.metis.kernel.goal.HorizonKanbanBridge;
import de.metis.kernel.world.HypothesisStore;
import de.metis.kernel.world.HypothesisGenerator;
import de.metis.kernel.world.InterventionRunner;
import de.metis.kernel.world.Counterfactual;
import de.metis.kernel.world.CausalModel;
import de.metis.modules.knowledge.LlmDreamSummarizer;
import de.metis.modules.knowledge.LlmHorizonDecomposer;
// TornadoVmAction lebt in src/tornado/java (Profil tornadovm-gpu) und wird
// unten reflektiv registriert, damit der Build ohne GPU-Profil überall klappt.
import de.metis.modules.multiagent.AgentCoordinator;
import de.metis.modules.CuriosityEngine;
import de.metis.kernel.metrics.FitnessSignal;
import de.metis.modules.speech.PiperTtsAction;
import de.metis.modules.speech.WhisperSttAction;
import de.metis.modules.speech.MaryTtsAction;
import de.metis.modules.speech.SphinxSttAction;
import de.metis.modules.speech.AudioOutputAction;
import de.metis.modules.speech.AudioInputAction;
import de.metis.modules.speech.VoiceLoopService;
import de.metis.modules.speech.WikipediaTrainingService;
import de.metis.modules.home.HomeAssistantAction;

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
 * Usage: {@code java -cp ... de.metis.modules.AgentMain [--interval 5000] [--persist agent-state.json]}
 */
public final class AgentMain {

    private static final Logger LOG = Logger.getLogger(AgentMain.class.getName());
    private static final Random RANDOM = new Random();

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    // ── Configuration ─────────────────────────────────────────
    private final long tickIntervalMs;
    private final Path persistPath;
    private final boolean enableEvolution;
    private final int idleGoalInterval;     // ticks between auto-generated goals
    private final long emergenceReportInterval;
    private KnowledgeStore knowledgeStore = null; // ticks between emergence reports

    // ── Runtime state ─────────────────────────────────────────
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Agent agent;
    private final ScheduledExecutorService scheduler;
    private Instant startedAt;
    private long totalTicks = 0;
    private long idleTicks = 0;
    private long evolutionTicks = 0;
    private final EvolutionScheduler evoScheduler = new EvolutionScheduler();

    // ── Emergence tracking ────────────────────────────────────
    private final List<String> emergenceEvents = new ArrayList<>();
    private int consecutiveFailures = 0;

    // ── Blue/Green Rollback + Autonomous Bugfixing (Phase 5) ─
    private RollbackManager rollbackManager;
    private BugfixingAgent bugfixingAgent;
    private int bugfixCheckInterval = 50;  // ticks between bugfix evaluations
    private double lastSuccessRate = 1.0;
    private long lastEmergenceReportTick = 0;
    private final int maxTicks;

    private AgentMain(Builder builder) {
        this.tickIntervalMs = builder.tickIntervalMs;
        this.persistPath = builder.persistPath;
        this.enableEvolution = builder.enableEvolution;
        this.idleGoalInterval = builder.idleGoalInterval;
        this.emergenceReportInterval = builder.emergenceReportInterval;
        this.knowledgeStore = builder.knowledgeStore;
        this.agent = builder.agent;
        this.maxTicks = builder.maxTicks;

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
                agent.worldModel().saveEmbeddings();
                JenaRdfService.getInstance().shutdown();
                TelemetryService.getInstance().shutdown();
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

                // maxTicks check
                if (maxTicks > 0 && totalTicks >= maxTicks) {
                    LOG.info("Reached max ticks (" + maxTicks + ") - stopping");
                    running.set(false);
                    break;
                }

                if (result == null) {
                    idleTicks++;
                } else {
                    // Emergence detection: track anomalies
                    detectEmergence(result, totalTicks);

                    // Blue/Green health tracking (Phase 5)
                    if (rollbackManager != null) {
                        rollbackManager.recordAction(result.success());
                    }

                    // Bugfixing error classification (Phase 5)
                    if (bugfixingAgent != null && !result.success()) {
                        var classified = bugfixingAgent.classifyFromResult(result);
                        if (classified != null) {
                            LOG.fine("Bug classified: " + classified.errorClass()
                                    + " for " + classified.actionName());
                        }
                    }
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

                // ── Knowledge sync (SQLite) ──────────────────
                if (knowledgeStore != null && totalTicks % 5 == 0) {
                    syncKnowledgeToStore();
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

                // ── Evolution check (adaptive scheduler) ──
                if (enableEvolution) {
                    // Record fitness every tick for trend analysis
                    double fitness = de.metis.kernel.evolution.FitnessFunction.evaluate(
                            agent.metrics(),
                            agent.workspace().runningEntropy());
                    evoScheduler.recordFitness(fitness);

                    var decision = evoScheduler.decide(totalTicks);
                    if (decision.shouldEvolve()) {
                        LOG.info("Evolution triggered by scheduler: " + decision.reason()
                                + " (urgency=" + String.format("%.2f", decision.urgency()) + ")");
                        triggerEmergenceEvolution();
                        lastEvolution = totalTicks;
                    }
                }

                // ── Blue/Green health eval + Bugfixing (Phase 5) ──
                if (totalTicks % bugfixCheckInterval == 0) {
                    evaluateHealthAndFix();
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

                // Record crash for Blue/Green rollback (Phase 5)
                if (rollbackManager != null) rollbackManager.recordCrash();
                if (bugfixingAgent != null) bugfixingAgent.classify(e.getMessage(), "agent-tick");

                if (consecutiveFailures > 10) {
                    LOG.severe("Too many consecutive failures — emergency stop");
                    // Auto-rollback before emergency stop
                    if (rollbackManager != null) {
                        rollbackManager.rollback();
                    }
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

        // Bugfix: Statt wm.query() (Substring-Match findet falsche Beliefs)
        // → echten Action-Execution-Counter aus dem Planner verwenden.
        // "Unexplored" = Action wurde NIE direkt ausgeführt (nicht: Name taucht als
        // Substring in irgendeinem Belief auf — das war das alte Verhalten).
        Set<String> available = exec.availableActions();
        Map<String, Integer> actionUsage = Map.of();
        if (agent.planner() instanceof de.metis.modules.planner.OllamaPlanner op) {
            actionUsage = op.actionUsageCount();
        }

        List<String> unexplored = new ArrayList<>();
        for (String action : available) {
            int uses = actionUsage.getOrDefault(action, 0);
            if (uses == 0) {
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

    private void detectEmergence(de.metis.kernel.action.ActionResult result, long tick) {
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
        double fitness = de.metis.kernel.evolution.FitnessFunction.evaluate(
                agent.metrics(),
                agent.workspace().runningEntropy());
        var evo = agent.core().evolutionManager();

        if (evo.shouldEvolve(totalTicks, fitness)) {
            LOG.info("Emergence-triggered evolution at tick " + totalTicks);
            var evoResult = evo.evolve(fitness);
            LOG.info("Evolution result: " + evoResult);

            // Notify scheduler about the attempt
            evoScheduler.evolutionAttempted(totalTicks, evoResult.accepted());

            String event = String.format("T%d: Emergence-triggered evolution — %s",
                    totalTicks, evoResult.message());
            emergenceEvents.add(event);
        } else {
            // Scheduler decided yes but evolution manager said no — notify scheduler
            evoScheduler.evolutionAttempted(totalTicks, false);
        }
    }

    // ── Blue/Green Health Evaluation + Bugfixing (Phase 5) ────────

    private void evaluateHealthAndFix() {
        // Health evaluation → auto-rollback if needed
        if (rollbackManager != null) {
            boolean rolledBack = rollbackManager.evaluateHealth();
            if (rolledBack) {
                LOG.severe("AUTO-ROLLBACK triggered — agent should restart");
                emergenceEvents.add("AUTO-ROLLBACK at tick " + totalTicks
                        + " → version " + rollbackManager.previousVersion());
            }
        }

        // Bugfixing pattern detection + auto-fix
        if (bugfixingAgent != null) {
            var pattern = bugfixingAgent.detectPattern();
            if (pattern.isPresent()) {
                LOG.warning("Detected error pattern: " + pattern.get()
                        + " (" + bugfixingAgent.recentErrorCount() + " recent errors)");

                // Only auto-fix repeat errors (same action+error combo ≥ 3 times)
                var lastErr = bugfixingAgent.lastError();
                if (lastErr.isPresent()
                        && bugfixingAgent.isRepeatError(lastErr.get().actionName(), pattern.get(), 3)) {
                    var fix = bugfixingAgent.attemptFix(pattern.get(), lastErr.get().actionName());
                    fix.ifPresent(f -> emergenceEvents.add("AUTO-FIX at tick " + totalTicks + ": " + f));
                }
            }
        }
    }

    // ── Persistence ───────────────────────────────────────────

    private void syncKnowledgeToStore() {
        if (knowledgeStore == null) return;
        try {
            // Sync planner mappings
            var planner = agent.planner();
            if (planner instanceof OllamaPlanner op) {
                var attempts = op.rawPlanningAttempts();
                var successes = op.rawPlanningSuccesses();
                for (String key : attempts.keySet()) {
                    String[] parts = key.split(":", 2);
                    if (parts.length == 2) {
                        knowledgeStore.savePlannerMapping(parts[0], parts[1],
                                attempts.get(key),
                                successes.getOrDefault(key, 0));
                    }
                }
            }
            // Sync recent experiences
            for (var exp : agent.stm().recent(10)) {
                knowledgeStore.saveExperience(exp);
            }
        } catch (Exception e) {
            LOG.fine("Knowledge sync skipped: " + e.getMessage());
        }
    }

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

    // ── Phase 5 accessors ────────────────────────────────────

    public RollbackManager rollbackManager() { return rollbackManager; }
    public BugfixingAgent bugfixingAgent() { return bugfixingAgent; }

    public void setRollbackManager(RollbackManager rm) { this.rollbackManager = rm; }
    public void setBugfixingAgent(BugfixingAgent ba) { this.bugfixingAgent = ba; }

    public static class Builder {
        private final Agent agent;
        private long tickIntervalMs = 3000;
        private Path persistPath = null;
        private int maxTicks = 0;
        private boolean enableEvolution = false; // off by default for safety
        private int idleGoalInterval = 15;
        private long emergenceReportInterval = 100;
        KnowledgeStore knowledgeStore = null;

        Builder(Agent agent) { this.agent = agent; }

        /** Tick interval in milliseconds. */
        public Builder tickInterval(long ms) { this.tickIntervalMs = ms; return this; }

        /** Path for state persistence JSON. */
        public Builder persistTo(Path path) { this.persistPath = path; return this; }

        /** Enable evolution mutations during run. */
        public Builder withEvolution() { this.enableEvolution = true; return this; }

        /** Ticks between autonomous goal generation. */
        public Builder idleGoalInterval(int ticks) { this.idleGoalInterval = ticks; return this; }

        public Builder maxTicks(int n) { this.maxTicks = n; return this; }

        /** Ticks between emergence reports. */
        public Builder emergenceReportInterval(long ticks) { this.emergenceReportInterval = ticks; return this; }

        /** Inject knowledge store for SQLite persistence. */
        public Builder knowledgeStore(KnowledgeStore ks) { this.knowledgeStore = ks; return this; }

        public AgentMain build() { return new AgentMain(this); }
    }

    // ── Main entry point ──────────────────────────────────────

    public static void main(String[] args) throws Exception {
        // Parse CLI args
        long interval = 3000;
        Path persist = null;
        boolean evolution = false;
        boolean kernelEvolution = false;
        boolean requireApproval = true;
        boolean kanbanEnabled = false;
        String bookDir = null;
        boolean voiceLoopEnabled = true;  // always-on for voice interaction
        int maxTicks = 0;
        int apiPort = 0;  // 0 = disabled
        String planningModel = null;
        String mutationModel = null;
        String embeddingModel = null;
        String bootstrapModel = null;
        String bootstrapModels = null;  // comma-separated multi-model
        String telegramToken = null;
        String weatherApiKey = null;
        String haUrl = null;
        String haToken = null;
        String mqttBroker = null;
        String mqttUser = null;
        String mqttPass = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--interval" -> interval = Long.parseLong(args[++i]);
                case "--persist" -> persist = Path.of(args[++i]);
                case "--evolution" -> evolution = true;
                case "--kernel-evolution" -> { evolution = true; kernelEvolution = true; }
                case "--max-ticks" -> maxTicks = Integer.parseInt(args[++i]);
                case "--api-port" -> apiPort = Integer.parseInt(args[++i]);
                case "--planning-model" -> planningModel = args[++i];
                case "--mutation-model" -> mutationModel = args[++i];
                case "--embedding-model" -> embeddingModel = args[++i];
                case "--bootstrap-model" -> bootstrapModel = args[++i];
                case "--bootstrap-models" -> bootstrapModels = args[++i];
                case "--telegram-token" -> telegramToken = args[++i];
                case "--weather-api-key" -> weatherApiKey = args[++i];
                case "--ha-url" -> haUrl = args[++i];
                case "--ha-token" -> haToken = args[++i];
                case "--mqtt-broker" -> mqttBroker = args[++i];
                case "--mqtt-user" -> mqttUser = args[++i];
                case "--mqtt-pass" -> mqttPass = args[++i];
                case "--no-approval-gate" -> requireApproval = false;
                case "--kanban" -> kanbanEnabled = true;
                case "--book-dir" -> bookDir = args[++i];
                case "--voice-loop" -> voiceLoopEnabled = true;
                case "--help", "-h" -> {
                    System.out.println("""
                            Metis AGI — Self-Evolving Agent System
                            Options:
                              --interval N          Tick interval in ms (default: 3000)
                              --persist PATH        State persistence file
                              --evolution           Enable self-evolution (modules only)
                              --evolution           Enable module evolution (non-kernel)
                              --kernel-evolution    Enable kernel + module evolution
                              --kanban              Enable Kanban goal board (WIP limits, pull system)
                              --book-dir <path>     Directory with PDF/EPUB books to learn from
                              --max-ticks N         Stop after N ticks (default: unlimited)
                              --api-port N          Start Ollama-compatible HTTP API on port N
                              --planning-model M    Override auto-selected planning model
                              --mutation-model M    Override auto-selected mutation model
                              --embedding-model M   Override auto-selected embedding model
                              --bootstrap-model M   Bootstrap from a single model
                              --bootstrap-models A,B Bootstrap with consensus from multiple models
                              --telegram-token T    Telegram Bot token for direct messaging
                              --weather-api-key K  Weather.com PWS API key
                              --ha-url URL         Home Assistant URL (event triggers)
                              --ha-token T         Home Assistant access token
                              --mqtt-broker URL    MQTT broker URL (tcp://host:port)
                              --mqtt-user USER    MQTT username
                              --mqtt-pass PASS    MQTT password
                            """);
                    return;
                }
            }
        }

        // Configure logging
        Logger.getLogger("").setLevel(Level.WARNING);
        Logger.getLogger("de.metis").setLevel(Level.INFO);

        // Discover models and build agent with auto-selection
        var modelRegistry = new ModelRegistry("http://192.168.22.204:11434").discover();

        // Apply manual model overrides from CLI
        if (planningModel != null) modelRegistry.overridePlanningModel(planningModel);
        if (mutationModel != null) modelRegistry.overrideMutationModel(mutationModel);
        if (embeddingModel != null) modelRegistry.overrideEmbeddingModel(embeddingModel);

        if (planningModel != null || mutationModel != null || embeddingModel != null) {
            LOG.info("Model overrides applied:");
            if (planningModel != null) LOG.info("  Planning:  " + planningModel);
            if (mutationModel != null) LOG.info("  Mutation:  " + mutationModel);
            if (embeddingModel != null) LOG.info("  Embedding: " + embeddingModel);
        }

        Agent agent = Agent.builder()
                .registerShellCommand(List.of("uname", "-a"))
                .registerHttpGet(URI.create("https://httpbin.org/get"))
                .ollamaPlanner("http://192.168.22.204:11434/api/generate", modelRegistry, Duration.ofSeconds(60))
                .promptChainingService("http://192.168.22.204:11434/api/generate", "nemotron-cascade-2:30b", Duration.ofSeconds(90))
                .workspaceCapacity(5)
                .build();

        // Human-in-the-loop: enable/disable approval gate for write actions
        agent.core().setRequireApprovalForWrite(requireApproval);
        LOG.info("Approval gate: " + (requireApproval ? "ENABLED (write actions blocked)" : "DISABLED"));

        // ── Kanban Goal Board (WIP limits, pull system, service classes) ──
        if (kanbanEnabled) {
            var board = new KanbanBoard();
            agent.core().goals().setKanbanBoard(board);
            LOG.info("Kanban board enabled — WIP limits per resource type:");
            LOG.info("  GPU_HEAVY=1  INFERENCE=2  CPU_HEAVY=2  LIGHT=4");
            // Wire planner so LLM-as-Judge calls count toward INFERENCE WIP
            // limit (ad-hoc slot). Prevents hidden hardware overload from
            // the otherwise unaccounted judge sub-call.
            if (agent.planner() instanceof OllamaPlanner op) {
                op.setKanbanBoard(board);
                LOG.info("Kanban wired into OllamaPlanner — judge calls under WIP limit");
            }
        }

        // Register filesystem actions (kernel extensibility)
        agent.core().executor().register(
                new de.metis.kernel.action.FileSystemAction("filesystem-list",
                        de.metis.kernel.action.FileSystemAction.Mode.LIST,
                        Path.of(System.getProperty("user.home", "/tmp"))));

        agent.core().executor().register(
                new de.metis.kernel.action.FileSystemAction("filesystem-read",
                        de.metis.kernel.action.FileSystemAction.Mode.READ,
                        Path.of("/tmp")));

        // Web-Scraper (native, JDK-only)
        agent.core().executor().register(new NativeWebScraperAction("https://example.com"));

        // Web-Crawler (Nutch-inspired, multi-page, recursive)
        agent.core().executor().register(new WebCrawlAction("https://example.com"));
        LOG.info("WebCrawlAction registered — recursive web crawler for knowledge acquisition");

        // MCP Bridge — connects to Model Context Protocol servers, discovers tools
        if (System.getProperty("metis.mcp.servers") != null) {
            String[] servers = System.getProperty("metis.mcp.servers").split(",");
            for (String spec : servers) {
                spec = spec.trim();
                if (spec.isEmpty()) continue;
                String[] parts = spec.split(":", 2);
                String srvName = parts[0].trim();
                String srvCmd = parts.length > 1 ? parts[1].trim() : srvName;
                var mcpAction = new McpBridgeAction(srvName, java.util.List.of(srvCmd.split("\\s+")));
                agent.core().executor().register(mcpAction);
                LOG.info(() -> "MCP bridge registered: " + srvName + " → " + srvCmd);
            }
        }

        // Web-Search (DuckDuckGo, no API key)
        agent.core().executor().register(new WebSearchAction("example query"));

        // JLama — pure Java LLM inference (no Ollama dependency)
        agent.core().executor().register(new JlamaInferenceAction("Hello, how are you?"));
        LOG.info("JlamaInferenceAction registered — pure Java LLM fallback inference");

        // Jena RDF — graph-based causal knowledge store (Apache Jena TDB2)
        try {
            JenaRdfService.getInstance().init();
            LOG.info("Jena RDF graph store initialized");
        } catch (Exception e) {
            LOG.warning("Jena RDF init skipped: " + e.getMessage());
        }

        // OpenTelemetry — structured tracing + Prometheus metrics
        try {
            TelemetryService.getInstance().init();
            LOG.info("OpenTelemetry initialized");
        } catch (Exception e) {
            LOG.warning("Telemetry init skipped: " + e.getMessage());
        }

        // Java-Code-Sandbox (jshell)
        agent.core().executor().register(new JavaSandboxAction("System.out.println(\"Hello from Metis!\");"));

        // Linux-Lernmodus (Level 1-3)
        // ── Source reading (self-introspection) ──
        agent.core().executor().register(new ReadSourceAction(
                "OllamaPlanner",
                List.of(
                        Path.of("agicore-kernel/src/main/java"),
                        Path.of("agicore-modules/src/main/java"),
                        Path.of("agicore-watchdog/src/main/java"))));
        LOG.info("ReadSourceAction registered — Metis can read its own Java source code");

        agent.core().executor().register(new LinuxExploreAction(1));
        agent.core().executor().register(new LinuxExploreAction(2) {
            @Override public String name() { return "linux-explore-system"; }
        });

        // API-Explorer
        agent.core().executor().register(new ApiExplorerAction("http://localhost:8080"));

        // ── Phase 4: Sprachausgabe & Spracherkennung ──
        agent.core().executor().register(new PiperTtsAction("Hallo, ich bin Metis."));
        agent.core().executor().register(new WhisperSttAction(Path.of("/tmp/metis-speech.wav")));
        // Java-native evolvable stubs (delegate to Piper/Whisper for now)
        agent.core().executor().register(new MaryTtsAction("Hallo Welt"));
        agent.core().executor().register(new SphinxSttAction("/tmp/metis-speech.wav"));
        // Audio I/O (Mikrofon + Lautsprecher)
        agent.core().executor().register(new AudioInputAction(5));
        agent.core().executor().register(new AudioOutputAction(Path.of("/tmp/test-piper.wav")));
        // Vocabulary Learning (STT correction → grammar improvement)
        agent.core().executor().register(new de.metis.kernel.action.VocabularyLearningAction(
                "test", "test"));
        // Wikipedia self-service — reads articles from local dump
        agent.core().executor().register(new de.metis.kernel.action.WikipediaAction(
                "Künstliche Intelligenz"));

        // ── Phase 3.2: Kamera-Integration ──
        agent.core().executor().register(new CameraSnapshotAction(
                "tuerkamera", "http://192.168.22.161:9081/snapshot"));
        agent.core().executor().register(new CameraSnapshotAction(
                "keller", "rtsp://3insicht:w1rB3obachtenEuc@192.168.22.148/H265/ch1/main/av_stream"));
        LOG.info("Camera actions registered: camera-snapshot-tuerkamera, camera-snapshot-keller");

        // ── Phase 3.3: Video-Analyse (Coburg Webcams + Live-Streams) ──
        agent.core().executor().register(new VideoAnalysisAction(
                "https://images.bergfex.at/webcams/?id=14275&2&format=44",
                "Coburg-Marktplatz-Stream", 5, 0.2, 25));
        agent.core().executor().register(new VideoAnalysisAction(
                "rtsp://3insicht:w1rB3obachtenEuc@192.168.22.148/H265/ch1/main/av_stream",
                "Keller-Live", 5, 0.2, 20));
        LOG.info("Video analysis actions registered: video-analyze (Coburg + Keller)");

        // ── Voice Loop Service (optional, controlled by voice-loop flag) ──
        VoiceLoopService voiceLoop = null;
        if (voiceLoopEnabled) {
            voiceLoop = new VoiceLoopService(agent.core().goals());
            voiceLoop.start();
            LOG.info("Voice loop started (Vosk → Metis → MaryTTS)");
        }

        // ── SQLite Knowledge Store (muss VOR Evolution-Block stehen — EvalHarness braucht ihn) ──
        Path dbPath = persist != null
                ? persist.resolveSibling("metis-knowledge.db")
                : Path.of("metis-knowledge.db");
        KnowledgeStore knowledgeStore = new KnowledgeStore(dbPath);
        agent.worldModel().setKnowledgeStore(knowledgeStore);
        agent.worldModel().loadFromStore();

        // Embedding: Ollama nomic-embed-text CPU-only (num_gpu=0, circuit-tolerant)
        // JLama multi-model embedding targeted for JLama >= 0.9.x
        var ollamaEmbedSvc = new OllamaEmbeddingService();
        agent.worldModel().setEmbeddingProvider(ollamaEmbedSvc::embed);

        // Enable RAG Advanced: persistent embeddings + hybrid keyword/semantic search
        var vectorsPath = dbPath.resolveSibling("metis-vectors.bin");
        agent.worldModel().enableRagAdvanced(vectorsPath);

        LOG.info("KnowledgeStore: " + knowledgeStore.beliefCount() + " beliefs, "
                + knowledgeStore.experienceCount() + " experiences, "
                + knowledgeStore.mappingCount() + " mappings from DB");

        // Inject Ollama mutation service if evolution enabled
        if (evolution) {
            var ollama = new OllamaMutationService(modelRegistry);
            agent.core().evolutionManager().setMutationService(ollama);
            // Share prompt bank: EvolutionManager records successes, OllamaMutationService reads for few-shot
            ollama.setPromptBank(agent.core().evolutionManager().promptBank());
            String scope = kernelEvolution ? "Kernel + Modules" : "Modules only";
            LOG.info("Evolution enabled — " + scope + " (mutator: " + ollama.currentModel() + ")");

            // Kernel evolution: register kernel source dir and enable feature branches
            if (kernelEvolution) {
                agent.core().evolutionManager().enableKernelEvolution(
                        java.nio.file.Path.of("agicore-kernel/src/main/java"));
                // Register safety-critical kernel classes as evolvable
                agent.core().evolutionManager().registerKernelModule(
                        "de.metis.kernel.planner.PlanValidator",
                        "de/metis/kernel/planner/PlanValidator.java");
                agent.core().evolutionManager().registerKernelModule(
                        "de.metis.kernel.goal.GoalManager",
                        "de/metis/kernel/goal/GoalManager.java");
                LOG.info("Kernel evolution enabled — 2 safety-critical modules registered");
            }

            // ── System Health Probe: GPU/VRAM/Ollama/dmesg monitoring ──
            var healthProbe = new de.metis.modules.monitor.SystemHealthProbe(
                    "http://192.168.22.204:11434", 60);
            healthProbe.start();
            LOG.info("SystemHealthProbe started — VRAM, GPU temp, Ollama models, dmesg errors every 60s");

            // ── Eval Harness: periodic model evaluation ──
            var evalReportDir = persist != null
                    ? persist.resolveSibling("eval-reports")
                    : Path.of("eval-reports");
            var evalInvoker = new de.metis.modules.eval.LiveMetisInvoker(
                    "http://192.168.22.204:11735",
                    "http://192.168.22.204:11434",
                    modelRegistry);
            var evalRunner = new de.metis.modules.eval.EvalRunner(evalInvoker, knowledgeStore, evalReportDir);
            // Run initial SMOKE test after startup (use daemon thread for delayed execution)
            var evalScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "eval-smoke");
                t.setDaemon(true);
                return t;
            });
            // Initial SMOKE at 30s, then every 6 hours
            evalScheduler.scheduleAtFixedRate(() -> {
                try {
                    var report = evalRunner.run("SMOKE");
                    String gate = report != null ? report.gate().ok() ? "PASS" : "FAIL" : "N/A";
                    LOG.info("Periodic SMOKE eval: " + gate
                        + (report != null ? " | regressions=" + report.regressions().size() : ""));
                    if (report != null && !report.gate().ok()) {
                        LOG.warning("Eval gate FAIL — " + report.gate().failingMetrics().size() + " failing metrics");
                    }
                } catch (Exception e) {
                    LOG.warning("Periodic eval failed: " + e.getMessage());
                }
            }, 30, 6 * 3600, TimeUnit.SECONDS);
            LOG.info("EvalHarness initialized — SMOKE every 6h (first in 30s)");
        }

        // Bootstrap world model
        agent.worldModel().update("shell actions execute reliably", 0.95, "bootstrap", true);
        agent.worldModel().update("http actions work for health checks", 0.9, "bootstrap", true);
        agent.worldModel().update("filesystem-list explores directory contents", 0.8, "bootstrap", true);
        agent.worldModel().update("filesystem-read retrieves file contents", 0.75, "bootstrap", true);
        agent.worldModel().update("webscrape extracts readable text from web pages", 0.85, "bootstrap", true);
        agent.worldModel().update("javasandbox runs Java code experiments safely", 0.9, "bootstrap", true);
        agent.worldModel().update("linux-explore probes system commands", 0.85, "bootstrap", true);
        agent.worldModel().update("api-explore discovers HTTP endpoints", 0.8, "bootstrap", true);

        // External knowledge bootstrap
        List<String> bootstrapModelList = new ArrayList<>();
        if (bootstrapModels != null) {
            bootstrapModelList = Arrays.asList(bootstrapModels.split(","));
        } else if (bootstrapModel != null) {
            bootstrapModelList = List.of(bootstrapModel);
        }

        if (!bootstrapModelList.isEmpty()) {
            LOG.info("Bootstrapping knowledge from " + bootstrapModelList.size()
                    + " model(s): " + bootstrapModelList);
            var kb = new KnowledgeBootstrap("http://192.168.22.204:11434", bootstrapModelList);
            var beliefs = kb.bootstrap();
            for (var b : beliefs) {
                agent.worldModel().update(b.statement(), b.confidence(), b.source(), true);
            }
            LOG.info("Injected " + beliefs.size() + " consensus beliefs into WorldModel");
        }

        // Initial goals
        agent.addGoal("Check system status via shell", "shell", 85, 0.9, 1);
        agent.addGoal("HTTP health check request", "http", 70, 0.8, 2);
        agent.addGoal("Reflektiere ethische Grundsaetze (Dhammapada, Metta Sutta) bei Entscheidungen", "ethics", 90, 0.9, 2);

        // ── Hardware Discovery + Self-Awareness ──────────────────
        HardwareDiscovery.HardwareProfile hw = HardwareDiscovery.discover();
        LOG.info("Hardware discovered:\n" + hw.summary());

        // Seed hardware beliefs into WorldModel
        agent.worldModel().update("I run on CPU: " + hw.cpu().model()
                + " (" + hw.cpu().physicalCores() + " cores, " + hw.cpu().logicalThreads() + " threads)",
                0.95, "hardware-discovery", true);
        agent.worldModel().update("Total RAM: " + hw.totalRamMb() + " MB, Available: "
                + hw.availableRamMb() + " MB", 0.95, "hardware-discovery", true);
        if (hw.hasROCm()) {
            agent.worldModel().update("GPU acceleration available via ROCm — AMD GPU detected",
                    0.95, "hardware-discovery", true);
        }
        if (hw.canRunLargeModels()) {
            agent.worldModel().update("Sufficient VRAM for large language models (>=16 GB)",
                    0.95, "hardware-discovery", true);
        }
        if (hw.cpu().hasAVX2()) {
            agent.worldModel().update("CPU supports AVX2 SIMD instructions — vectorized operations possible",
                    0.9, "hardware-discovery", true);
        }
        LOG.info("Hardware beliefs seeded: " + hw.cpu().model() + ", "
                + hw.totalRamMb() + " MB RAM, " + hw.gpus().size() + " GPU(s)");

        // Register hardware profiling + Deep Netts AI actions
        var hwAction = new HardwareProfileAction(agent);
        agent.core().executor().register(hwAction);
        LOG.info("Registered action: " + hwAction.name());

        var dnAction = new DeepNettsAction();
        agent.core().executor().register(dnAction);
        LOG.info("Registered action: " + dnAction.name() + " — Deep Netts neural networks");

        // TornadoVM-Action nur registrieren, wenn sie mitkompiliert wurde
        // (Profil tornadovm-gpu). Sonst sauber überspringen — kein harter Bezug.
        try {
            var tvClass = Class.forName("de.metis.modules.hardware.TornadoVmAction");
            var tvAction = (de.metis.kernel.action.Action) tvClass
                    .getDeclaredConstructor().newInstance();
            agent.core().executor().register(tvAction);
            LOG.info("Registered action: " + tvAction.name() + " — TornadoVM GPU");
        } catch (ClassNotFoundException e) {
            LOG.info("TornadoVmAction not on classpath (built without tornadovm-gpu) — skipping GPU action");
        } catch (Exception e) {
            LOG.warning("TornadoVmAction registration failed: " + e.getMessage());
        }

        // ── Multi-Agent Coordinator ────────────────────────────
        AgentCoordinator coordinator = new AgentCoordinator();
        // Spawn Ops-Agent: 24/7 monitoring, no evolution
        var opsAgent = Agent.builder()
                .registerShellCommand(List.of("uptime"))
                .registerHttpGet(URI.create("https://httpbin.org/status/200"))
                .ollamaPlanner("http://192.168.22.204:11434/api/generate", modelRegistry, Duration.ofSeconds(60))
                .workspaceCapacity(5)
                .build();
        opsAgent.worldModel().update("I monitor system health and MQTT events", 0.95, "coordinator", true);
        opsAgent.worldModel().update("I report anomalies to the main Metis agent", 0.9, "coordinator", true);
        opsAgent.addGoal("Monitor system health continuously", "monitor", 85, 0.9, 1);
        coordinator.spawn("ops", "24/7 System Monitor", opsAgent, Duration.ofSeconds(10));
        LOG.info("Multi-agent: Metis (main) + Ops (monitor) — " + coordinator.agentCount() + " agents");

        // Seed Deep Netts capability belief
        agent.worldModel().update(
                "I can create and train neural networks using Deep Netts (pure Java, CPU)",
                0.9, "deepnetts-init", true);

        // ── Start HTTP API (OpenWebUI integration) ────────────
        MetisHttpServer httpServer = null;
        // ── Phase 8: Narratives Selbstmodell ─────────────────────────
        // EpisodicMemory + SelfNarrative + MoodSignal + PersonalityAnchor
        // + DreamConsolidation (nightly tick at 03:00 local)
        var personalityAnchor = new PersonalityAnchor();
        if (personalityAnchor.isTampered()) {
            LOG.severe("⚠️ PersonalityAnchor TAMPERED — running with degraded identity guarantee!");
        }
        var episodicMemory = new EpisodicMemory();
        var selfNarrative  = new SelfNarrative();
        var moodSignal     = new MoodSignal();
        var dreamConsolidation = new DreamConsolidation(episodicMemory, selfNarrative, moodSignal);
        dreamConsolidation.setSummarizer(new LlmDreamSummarizer(
                "http://192.168.22.204:11434", "gemma4:e4b"));

        // Update mood every minute from current metrics (cheap, deterministic)
        var moodScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "mood-signal");
            t.setDaemon(true);
            return t;
        });
        moodScheduler.scheduleAtFixedRate(() -> {
            try {
                var m = agent.metrics();
                double goalRate = m != null ? m.goalSuccessRate() : 0.5;
                double surprise = 0.5; // CuriosityEngine surface not exported; placeholder
                double evalOk   = 1.0; // Watchdog overrides at runtime if Eval fails
                double recentEnergy = Math.min(1.0, (m != null ? m.totalTicks() : 0) / 10000.0);
                moodSignal.update(null, goalRate, evalOk, surprise, recentEnergy);
            } catch (Exception e) { /* mood updates are best-effort */ }
        }, 30, 60, TimeUnit.SECONDS);

        // Nightly dream consolidation at 03:00 local (Europe/Berlin).
        // Initial delay aligned to next 03:00, then every 24h.
        var dreamScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "dream-consolidation");
            t.setDaemon(true);
            return t;
        });
        long initialDreamDelaySec;
        {
            var zone = java.time.ZoneId.of("Europe/Berlin");
            var now  = java.time.ZonedDateTime.now(zone);
            var next = now.withHour(3).withMinute(0).withSecond(0).withNano(0);
            if (!next.isAfter(now)) next = next.plusDays(1);
            initialDreamDelaySec = java.time.Duration.between(now, next).toSeconds();
        }
        dreamScheduler.scheduleAtFixedRate(() -> {
            try {
                var m = agent.metrics();
                var end = java.time.Instant.now();
                var start = end.minus(java.time.Duration.ofHours(24));
                var stats = new DreamConsolidation.DayStats(
                        start, end,
                        m != null ? m.totalTicks() : 0,
                        agent.worldModel().beliefCount(),
                        m != null ? (int) Math.round(m.goalSuccessRate() * 100) : 0,
                        0,
                        m != null ? m.goalSuccessRate() : 0.0,
                        1.0,
                        java.util.List.of(),
                        java.util.List.of(),
                        java.util.List.of(),
                        null);
                dreamConsolidation.consolidate(stats);
                LOG.info("Nightly dream consolidated. Episodes total: " + episodicMemory.size());
            } catch (Exception e) {
                LOG.warning("Dream consolidation failed: " + e.getMessage());
            }
        }, initialDreamDelaySec, 24 * 3600, TimeUnit.SECONDS);
        // ── Phase 9: Long-Horizon-Planung ────────────────────────────
        var goalHierarchy = new GoalHierarchy();
        var horizonPlanner = new HorizonPlanner(goalHierarchy);
        var commitmentRegister = new CommitmentRegister(goalHierarchy);
        var revisionEngine = new GoalRevisionEngine(goalHierarchy);

        // Seed a lifetime goal once (idempotent: only if hierarchy is empty)
        if (goalHierarchy.size() == 0) {
            goalHierarchy.upsert(new LongHorizonGoal(
                    null, "Hilf Georg ein EDI-ähnliches System zu bauen",
                    "Lifetime-Goal aus PersonalityAnchor abgeleitet.",
                    GoalHorizon.LIFETIME,
                    LongHorizonGoal.Status.ACTIVE,
                    null, java.util.List.of(),
                    null, null, null, null, 0.5,
                    100, "metis",
                    java.util.List.of("lifetime", "edi")));
            LOG.info("GoalHierarchy: seeded lifetime goal");
        }

        // Periodic revision every 30 min — auto-blocks overdue, auto-completes,
        // rolls up parent progress. Result is logged + appended to SelfNarrative
        // when something actually changes.
        var revisionScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "goal-revision");
            t.setDaemon(true);
            return t;
        });
        revisionScheduler.scheduleAtFixedRate(() -> {
            try {
                var rep = revisionEngine.revise();
                if (rep.anyChange()) {
                    LOG.info("GoalRevision: overdue=" + rep.overdue()
                            + " autoDone=" + rep.autoCompleted()
                            + " stale=" + rep.reviewedStale()
                            + " parentRolled=" + rep.parentRolled());
                    selfNarrative.append("revision",
                            "Goal-Revision: " + String.join("; ", rep.notes()));
                }
            } catch (Exception e) {
                LOG.warning("GoalRevision failed: " + e.getMessage());
            }
        }, 5, 30, TimeUnit.MINUTES);

        LOG.info("Phase 9 wired — hierarchy=" + goalHierarchy.size() + " goals");

        // ── Phase 10: Aktive kausale Hypothesen ──────────────────────
        var hypothesisStore = new HypothesisStore();
        var hypothesisGenerator = new HypothesisGenerator(hypothesisStore);
        var causalModel = new CausalModel();
        var interventionRunner = new InterventionRunner(hypothesisStore, causalModel);
        LOG.info("Phase 10 wired — hypotheses=" + hypothesisStore.size()
                + ", confirmed=" + hypothesisStore.confirmedCount()
                + ", refuted=" + hypothesisStore.refutedCount());

        // Phase 10 Hot-Path: inject HypothesisStore into planner for causal awareness
        if (agent.planner() instanceof de.metis.modules.planner.OllamaPlanner op) {
            op.withHypothesisStore(hypothesisStore);
            op.withCausalModel(causalModel);
            var counterfactual = new de.metis.kernel.world.Counterfactual(causalModel);
            op.withCounterfactual(counterfactual);
            LOG.info("Phase 10 Hot-Path wired — causal hypotheses in planning prompt");
        }

        // Phase 9.3b — LLM decomposer drop-in (falls Ollama down: deterministischer Fallback)
        horizonPlanner.setDecomposer(new LlmHorizonDecomposer(
                "http://192.168.22.204:11434", "gemma4:e4b"));

        // Phase 9.6b — Brücke Long-Horizon → Kanban-Board
        var horizonKanbanBridge = (agent.core().goals().kanbanBoard() != null)
                ? new HorizonKanbanBridge(goalHierarchy, agent.core().goals().kanbanBoard())
                : null;
        if (horizonKanbanBridge != null) {
            var bridgeScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "horizon-kanban-bridge");
                t.setDaemon(true);
                return t;
            });
            bridgeScheduler.scheduleAtFixedRate(() -> {
                try {
                    int n = horizonKanbanBridge.promoteDueGoals();
                    if (n > 0) {
                        LOG.info("HorizonKanbanBridge: promoted " + n + " goals -> BACKLOG");
                    }
                } catch (Exception e) {
                    LOG.warning("HorizonKanbanBridge tick failed: " + e.getMessage());
                }
            }, 60, 300, TimeUnit.SECONDS);
            LOG.info("Phase 9.6b wired — HorizonKanbanBridge scheduled every 5 min");
        }

        var systemPromptBuilder = new SystemPromptBuilder(
                personalityAnchor, selfNarrative, moodSignal, episodicMemory);
        systemPromptBuilder.setGoalHierarchy(goalHierarchy);
        LOG.info("Phase 8 wired — episodes=" + episodicMemory.size()
                + ", anchor=" + (personalityAnchor.isTampered() ? "TAMPERED" : "verified")
                + ", next dream in " + initialDreamDelaySec + "s");

        // ── Phase 11: PersonModel + Verdrahtung ─────────────────────
        var empathySignal = new de.metis.kernel.person.EmpathySignal();
        var personStore = new de.metis.kernel.person.PersonStore();
        // Bootstrap Georg as Owner
        personStore.ensureOwner("265324594", "Georg");
        // Wire into SystemPromptBuilder (Partner-Block im Prompt)
        systemPromptBuilder.setPersonStore(personStore, empathySignal);
        LOG.info("Phase 11 wired — PersonStore=" + personStore.size()
                + " persons, trust-to-approval mapping active");
        // ── Phase 12a: BugTracker — Self-healing exception handler ──
        var bugTracker = new de.metis.kernel.self.BugTracker();
        var compileReporter = new de.metis.modules.self.CompileErrorReporter(".");
        LOG.info("Phase 12a: CompileErrorReporter ready — build dir: .");
        bugTracker.withRollbackTrigger(() -> {
            LOG.severe("Phase 12a: Bug exhausted fix attempts -- auto-rollback triggered");
            // RollbackManager lives in AgentMain scope; fallback to systemd restart
            try {
                var pb = new ProcessBuilder("sudo", "systemctl", "restart", "metis.service");
                pb.start();
            } catch (Exception ex) {
                LOG.severe("Auto-rollback restart failed: " + ex.getMessage());
            }
        }).withFixGoalTrigger(goalDesc -> {
            var bugGoal = new de.metis.kernel.goal.Goal(
                    goalDesc, "fix", 90, 0.9, 1,
                    de.metis.kernel.goal.Goal.ServiceClass.EXPEDITE,
                    de.metis.kernel.goal.Goal.ResourceType.CPU_HEAVY,
                    null);
            LOG.info("Phase 12a: BugFix goal created: " + goalDesc);
        });
        agent.core().withExceptionHandler(e -> {
            var source = e.getStackTrace().length > 0
                    ? e.getStackTrace()[0].getClassName() + "." + e.getStackTrace()[0].getMethodName()
                    : "unknown";
            bugTracker.report(source, e);
            String errorDesc = source + " threw " + e.getClass().getSimpleName()
                    + ": " + (e.getMessage() != null ? e.getMessage() : "no message");
            agent.worldModel().update("Last error: " + errorDesc, 0.9, "bugtracker", false);
        });
        LOG.info("Phase 12a wired — BugTracker active, self-healing exception handler");
        var fixAction = new de.metis.modules.action.SelfFixAction(
                "http://192.168.22.204:11434/api/generate", "nemotron:latest", ".");
        agent.core().executor().register(fixAction);
        LOG.info("Phase 12a: SelfFixAction registered — self-fix action available");
        var featureGenAction = new de.metis.modules.action.FeatureGenAction(
                "http://192.168.22.204:11434/api/generate", "nemotron:latest", ".");
        agent.core().executor().register(featureGenAction);
        LOG.info("Phase 12b: FeatureGenAction registered");

        // Phase 12b: GapAnalyzer
        var gapAnalyzer = new de.metis.modules.evolution.GapAnalyzer();
        var riskGate = new de.metis.modules.evolution.RiskGate();
        var metricSeries = new de.metis.modules.evolution.MetricTimeSeries();
        LOG.info("Phase 12b: GapAnalyzer + RiskGate + MetricTimeSeries ready");

        // ── Phase 8.6 — SelfReflector: kontinuierlicher innerer Monolog ──────
        // Konvergente Empfehlung aus 9 KI-Reviews (2026-05-31): kleiner, schneller
        // Reflexions-Takt schließt die Lücke zwischen nightly-dream und revision.
        // Liest die letzten ~20 Experiences, verdichtet via granite4.1:3b zu 2
        // Sätzen, hängt sie an SelfNarrative an (vom SystemPromptBuilder gelesen).
        var selfReflector = new SelfReflector(
                "http://192.168.22.204:11434", "phi4-mini:latest",
                selfNarrative,
                () -> agent.memory().stm().recent(20),
                () -> { var m = agent.metrics(); return m != null ? m.goalSuccessRate() : 0.5; });
        var reflectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "self-reflector");
            t.setDaemon(true);
            return t;
        });
        // Erste Reflexion nach 120 s, dann alle 120 s. Best-effort, schluckt Fehler.
        reflectScheduler.scheduleAtFixedRate(() -> selfReflector.reflectOnce(),
                120, 120, TimeUnit.SECONDS);
        LOG.info("Phase 8.6 wired — SelfReflector every 120s (granite4.1:3b, append-only)");

        // ── Phase 8.7 — PersonalityTripwire: Drift-Erkennung alle 5 min ──
        var tripwire = new de.metis.kernel.self.PersonalityTripwire(
                personalityAnchor, selfNarrative,
                alert -> LOG.severe("TRIPWIRE ALERT (for Watchdog): " + alert));
        var tripwireScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "personality-tripwire");
            t.setDaemon(true);
            return t;
        });
        tripwireScheduler.scheduleAtFixedRate(() -> {
            try {
                if (tripwire.checkForDrift()) {
                    LOG.warning("PersonalityTripwire: narrative drift detected ("
                            + tripwire.driftCount() + " incidents)");
                }
            } catch (Exception e) {
                LOG.warning("Tripwire check failed (non-fatal): " + e.getMessage());
            }
        }, 5, 5, TimeUnit.MINUTES);
        LOG.info("Phase 8.7 wired — PersonalityTripwire every 5 min");

        // ── Phase 10.5 — CausalDreamer: Kausalhypothesen im Leerlauf ──
        var casualDreamer = new de.metis.modules.self.CausalDreamer(
                agent.memory(), agent.core().goals().kanbanBoard(),
                hypothesisGenerator, hypothesisStore, selfNarrative,
                interventionRunner);        var dreamerScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "causal-dreamer");
            t.setDaemon(true);
            return t;
        });
        dreamerScheduler.scheduleAtFixedRate(() -> {
            try {
                casualDreamer.dreamOnce();
            } catch (Exception e) {
                LOG.warning("CausalDreamer tick failed (non-fatal): " + e.getMessage());
            }
        }, 180, 120, TimeUnit.SECONDS);
        LOG.info("Phase 10.5 wired — CausalDreamer every 2 min (WIP<2 trigger)");

        // Phase 12b: GapAnalyzer periodic check (every 60s)
        var gapScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r, "gap-analyzer"); t.setDaemon(true); return t; });
        gapScheduler.scheduleAtFixedRate(() -> {
            try {
                var metrics = new java.util.HashMap<String, Object>();
                metrics.put("planningEfficiency", agent.metrics().planningEfficiency());
                metrics.put("successRate", agent.metrics().goalSuccessRate());
                metrics.put("confidence", agent.meta().confidence());
                metrics.put("beliefCount", agent.worldModel().beliefCount());
                // Phase 12c: record metric snapshot
                metricSeries.record(
                        agent.metrics().planningEfficiency(),
                        agent.metrics().goalSuccessRate(),
                        agent.meta().confidence(),
                        agent.worldModel().beliefCount(),
                        agent.goals().activeCount(),
                        0.0
                );                var proposals = gapAnalyzer.analyze(metrics);
                for (var p : proposals) {
                    if (riskGate.allow(p)) {
                        agent.addGoal(
                                "feature: " + p.shortDescription(),
                                "feature-gen", p.priority(), 0.5, 2);
                        LOG.info("Phase 12b: Feature goal created: "
                                + p.id() + " prio=" + p.priority());
                    }
                }
            } catch (Exception e) {
                LOG.fine("GapAnalyzer tick failed (non-fatal): " + e.getMessage());
            }
        }, 60, 60, java.util.concurrent.TimeUnit.SECONDS);
        LOG.info("Phase 12b: GapAnalyzer scheduled every 60s");

        // ── Phase 9.5 — CommitmentGuard: Schutz gegen leichtfertigen Bruch ──
        // Deterministischer Wächter; vom Revision-/Planner-Pfad nutzbar, um
        // HARD-Commitments (priority≥85, tag=commitment) nicht ohne Begründung
        // nach ABANDONED zu wechseln. Vorerst beobachtend verdrahtet.
        var commitmentGuard = new CommitmentGuard();
        LOG.info("Phase 9.5 wired — CommitmentGuard active (hard-priority="
                + CommitmentGuard.HARD_PRIORITY + ")");

        // ── Phase 7.x — GlobalWorkspace Schattenmodus (read-only) ───────────
        // Schreibt jeden Broadcast als JSONL-Zeile, ohne Verhalten zu ändern.
        // Dient der Offline-Auswertung der Aufmerksamkeitskonkurrenz (Attention
        // Hijacking, Kohärenz) vor einem späteren CoreLoop-Umbau auf GWT.
        var workspaceShadow = new WorkspaceShadowLogger();
        if (workspaceShadow.isEnabled()) {
            var workspace = agent.workspace();
            var shadowScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "workspace-shadow");
                t.setDaemon(true);
                return t;
            });
            // Snapshot des letzten Broadcasts alle 5 s mitschreiben (read-only).
            shadowScheduler.scheduleAtFixedRate(() -> {
                try {
                    var bc = workspace.currentBroadcast();
                    if (bc != null && !bc.isEmpty()) {
                        workspaceShadow.log(bc, workspace.normalisedEntropy(),
                                workspace.isAttentionStuck());
                    }
                } catch (Exception e) {
                    LOG.fine("WorkspaceShadow tick failed (non-fatal): " + e.getMessage());
                }
            }, 30, 5, TimeUnit.SECONDS);
            LOG.info("Phase 7.x wired — WorkspaceShadowLogger -> " + workspaceShadow.file());
        }

        if (apiPort > 0) {
            httpServer = new MetisHttpServer(agent, apiPort);
            httpServer.setKnowledgeStore(knowledgeStore);
            httpServer.setModelRegistry(modelRegistry);
            httpServer.setCoordinator(coordinator);
            if (kanbanEnabled) {
                httpServer.setKanbanBoard(agent.core().goals().kanbanBoard());
            }
            httpServer.setEmbeddingService(ollamaEmbedSvc);
            httpServer.setSystemPromptBuilder(systemPromptBuilder);
            httpServer.setGoalHierarchy(goalHierarchy);
            httpServer.setHypothesisStore(hypothesisStore);
            httpServer.setPersonStore(personStore, empathySignal);
            httpServer.setBugTracker(bugTracker);
            httpServer.start();
        }

        // ── Start Telegram Bot ──────────────────────────────
        TelegramBotService telegramBot = null;
        if (telegramToken != null && !telegramToken.isBlank()) {
            telegramBot = new TelegramBotService(telegramToken, agent);
            telegramBot.setKnowledgeStore(knowledgeStore);
            telegramBot.setSystemPromptBuilder(systemPromptBuilder);
            telegramBot.setPersonStore(personStore, empathySignal);
            if (agent.core().goals().kanbanBoard() != null) {
                telegramBot.setKanbanBoard(agent.core().goals().kanbanBoard());
            }
            telegramBot.start();
            LOG.info("Telegram bot active — direct messaging enabled");
        }

        // ── Proactive Notifications ───────────────────────────
        ProactiveNotificationService notifier = null;
        if (telegramBot != null) {
            notifier = new ProactiveNotificationService(telegramBot, 265324594L);
            notifier.start();
            agent.core().goals().onGoalAdded(notifier::onGoalAdded);
            LOG.info("Proactive notifications active → Telegram chat 265324594");
        }

        // ── Start Event Triggers ──────────────────────────────
        List<EventTrigger> eventTriggers = new ArrayList<>();
        if (weatherApiKey != null && !weatherApiKey.isBlank()) {
            var weather = new WeatherPollingTrigger(weatherApiKey, "ICOBURG22");
            weather.start(agent);
            eventTriggers.add(weather);
            LOG.info("Weather event trigger active — " + weather.description());
        }
        if (haUrl != null && !haUrl.isBlank() && haToken != null && !haToken.isBlank()) {
            var ha = new HAEventPoller(haUrl, haToken)
                    .watch("binary_sensor.", "person.", "camera.");
            ha.start(agent);
            eventTriggers.add(ha);
            LOG.info("HA event trigger active — " + ha.description());

            // Phase 3: HA Direktzugriff — states lesen + services aufrufen
            agent.core().executor().register(
                    HomeAssistantAction.getState(haUrl, haToken, "sun.sun"));
            agent.core().executor().register(
                    HomeAssistantAction.callService(haUrl, haToken, "light", "toggle", "light.example"));
            LOG.info("HA direct access actions registered (ha-state, ha-call)");
        }
        if (mqttBroker != null && !mqttBroker.isBlank()) {
            var topics = List.of(
                    "+/+/RTL_433toMQTT/+/+/temperature_C",  // Temperatur-Sensoren
                    "+/+/RTL_433toMQTT/+/+/humidity",       // Feuchte
                    "+/+/RTL_433toMQTT/+/+/battery_ok",     // Batterie-Status
                    "homeassistant/binary_sensor/+/state",   // Bewegungsmelder
                    "homeassistant/sensor/+/state",          // HA-Sensoren
                    "solar/+/+",                            // Solar/Batterie
                    "stat/+/+"                               // Status-Updates
            );
            var mqtt = new MqttEventService(mqttBroker, mqttUser, mqttPass, topics);
            mqtt.start(agent);
            eventTriggers.add(mqtt);
            LOG.info("MQTT event trigger active — " + mqtt.description());
        }

        // Phase 3: ADS-B flight tracking (readsb JSON API)
        var adsb = new AdsbPollingTrigger();
        adsb.start(agent);
        eventTriggers.add(adsb);
        LOG.info("ADS-B event trigger active — " + adsb.description());

        // Phase 3: Webcam Coburg Marktplatz (public bergfex webcam)
        var webcam = new WebcamPollingTrigger();
        webcam.start(agent);
        eventTriggers.add(webcam);
        LOG.info("Webcam event trigger active — " + webcam.description());

        // Phase 3.2: Camera polling (Türkamera + Keller)
        var cameraPolling = new CameraPollingTrigger(List.of(
                new CameraConfig("tuerkamera", "http://192.168.22.161:9081/snapshot", "Türkamera 1080p MJPEG"),
                new CameraConfig("keller", "rtsp://3insicht:w1rB3obachtenEuc@192.168.22.148/H265/ch1/main/av_stream", "Keller Annke 720p H.265")
        ));
        cameraPolling.start(agent);
        eventTriggers.add(cameraPolling);
        LOG.info("Camera event trigger active — " + cameraPolling.description());


        // Phase 4: Wikipedia Knowledge Acquisition (live API, no local dump needed)
        // Phase 4: Wikipedia Knowledge Acquisition (live API, no local dump needed)
        var wikiKnowledge = new de.metis.modules.knowledge.WikipediaKnowledgeService(
                "http://192.168.22.204:11434", agent.worldModel());
        
        // Curiosity-driven periodic learning: every 10 minutes, learn one article
        // Wikipedia-Lerner: dedizierter Scheduler-Thread (Platform für Timing-Stabilität),
        // aber die eigentliche Lernarbeit wird auf Virtual Threads ausgelagert (Java 25 Loom),
        // damit ein hängender Ollama-Call die nächste Tick-Auslösung nicht blockiert.
        var wikiScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "wikipedia-learner");
            t.setDaemon(true);
            return t;
        });
        var wikiVtFactory = Thread.ofVirtual().name("wiki-learn-vt-", 0).factory();
        var wikiWorkPool = Executors.newThreadPerTaskExecutor(wikiVtFactory);
        wikiScheduler.scheduleAtFixedRate(() -> wikiWorkPool.submit(() -> {
            try {
                // Get curiosity topics from least-explored belief domains
                var beliefs = agent.worldModel().all();
                var sourceCounts = new java.util.HashMap<String, Integer>();
                for (var b : beliefs) {
                    if (b.source() != null && !b.source().startsWith("bootstrap")) {
                        sourceCounts.merge(b.source(), 1, Integer::sum);
                    }
                }
                // Pick 3 least-represented sources as curiosity topics
                var topics = sourceCounts.entrySet().stream()
                        .sorted(Map.Entry.comparingByValue())
                        .limit(3)
                        .map(Map.Entry::getKey)
                        .collect(java.util.stream.Collectors.toList());
                wikiKnowledge.addCuriosityTopics(topics);

                // Kanban: add Wikipedia/Speech goals to BACKLOG, Metis pulls when ready
                // CoreLoop promotes BACKLOG → READY on each tick (WIP-respecting)
                boolean isSpeechGoal = RANDOM.nextDouble() < 0.05; // ~5% speech-loop

                if (agent.core().goals().kanbanBoard() != null) {
                    var board = agent.core().goals().kanbanBoard();
                    // Add to BACKLOG only — Metis pulls independently
                    if (isSpeechGoal) {
                        Goal speechGoal = new Goal(
                                "Speech-Loop: Wikipedia article (speak→listen→learn)",
                                "speech-loop", 55, 0.6, 2,
                                Goal.ServiceClass.STANDARD, Goal.ResourceType.CPU_HEAVY, null);
                        board.add(speechGoal);
                        LOG.fine("Kanban: BACKLOG ← Speech-Loop goal");
                    } else {
                        Goal wikiGoal = new Goal(
                                "Learn Wikipedia article (curiosity-driven)",
                                "wikipedia-learn", 50, 0.5, 1,
                                Goal.ServiceClass.STANDARD, Goal.ResourceType.INFERENCE, null);
                        board.add(wikiGoal);
                        LOG.fine("Kanban: BACKLOG ← Wikipedia learning goal");
                    }
                }

                // Pull from READY: check if a goal of our type is ready to execute
                Goal pulledGoal = null;
                int learned = 0;
                if (agent.core().goals().kanbanBoard() != null) {
                    var board = agent.core().goals().kanbanBoard();
                    var snapshot = board.snapshot();
                    // Try speech-loop first (higher priority)
                    pulledGoal = snapshot.ready().stream()
                            .filter(g -> g.category().equals("speech-loop"))
                            .findFirst().orElse(null);
                    if (pulledGoal == null) {
                        pulledGoal = snapshot.ready().stream()
                                .filter(g -> g.category().equals("wikipedia-learn"))
                                .findFirst().orElse(null);
                    }
                    if (pulledGoal != null) {
                        pulledGoal = board.pull(); // READY → IN_PROGRESS (respects WIP)
                    }
                }

                if (pulledGoal != null) {
                    boolean isSpeech = pulledGoal.category().equals("speech-loop");
                    if (isSpeech) {
                        learned = runSpeechLoop(wikiKnowledge, pulledGoal, agent);
                    } else {
                        learned = wikiKnowledge.learnOneArticle();
                    }
                    // Complete or requeue
                    if (agent.core().goals().kanbanBoard() != null) {
                        if (learned > 0) {
                            agent.core().goals().kanbanBoard().complete(pulledGoal.id());
                            LOG.info("Wikipedia: learned " + learned + " facts (total: "
                                    + wikiKnowledge.factsLearned() + " from " + wikiKnowledge.articlesProcessed() + " articles)");
                        } else {
                            agent.core().goals().kanbanBoard().requeue(pulledGoal);
                            LOG.fine("Wikipedia: nothing learned — requeued");
                        }
                    }
                } else {
                    // No READY goal — just feed knowledge directly
                    learned = wikiKnowledge.learnOneArticle();
                    if (learned > 0) {
                        LOG.info("Wikipedia curiosity: learned " + learned + " facts (total: "
                                + wikiKnowledge.factsLearned() + " from " + wikiKnowledge.articlesProcessed() + " articles)");
                    }
                }
            } catch (Exception e) {
                LOG.fine("Wikipedia learning cycle: " + e.getMessage());
            }
        }), 3, 10, TimeUnit.MINUTES);
        LOG.info("Wikipedia knowledge acquisition active — API-based, curiosity-driven, every 10 min");

        // ── Book Ingestion Service ───────────────────────────
        if (bookDir != null && !bookDir.isBlank()) {
            var bookIngestion = new BookIngestionService(
                    knowledgeStore, agent.core().goals().kanbanBoard(),
                    java.nio.file.Path.of(bookDir));
            
            // Initial ingestion
            int ingested = bookIngestion.ingestNewBooks();
            LOG.info("Book ingestion: " + ingested + " books from " + bookDir);
            
            // Periodic re-scan (every 30 min, daemon thread)
            Thread.ofPlatform().name("book-scanner").daemon().start(() -> {
                while (!Thread.interrupted()) {
                    try { Thread.sleep(1_800_000); } catch (InterruptedException e) { break; }
                    try {
                        int newly = bookIngestion.ingestNewBooks();
                        if (newly > 0) {
                            LOG.info("Book scanner: ingested " + newly + " new books");
                        }
                    } catch (Exception ex) {
                        LOG.warning("Book scanner error: " + ex.getMessage());
                    }
                }
            });
            LOG.info("Book ingestion active — directory: " + bookDir + ", rescan every 30 min");
        }

        // Phase 7: Camera Vision (minicpm-v) — periodic observations from existing cameras
        var visionCameras = List.of(
                new CameraVisionAction("tuerkamera", "http://192.168.22.161:9081/snapshot", agent.worldModel()),
                new CameraVisionAction("balkon", "http://192.168.22.180:8080/photo.jpg", agent.worldModel())
        );
        var visionScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "camera-vision");
            t.setDaemon(true);
            return t;
        });
        // Java 25 Loom: observe all cameras in parallel on virtual threads.
        // Replaces serial loop with 3s sleep -> all cameras finish in ~one snapshot cycle.
        var visionVtFactory = Thread.ofVirtual().name("camera-vision-vt-", 0).factory();
        visionScheduler.scheduleAtFixedRate(() -> {
            try (var visionVt = Executors.newThreadPerTaskExecutor(visionVtFactory)) {
                for (var cam : visionCameras) {
                    visionVt.submit(() -> {
                        try { cam.observe(); }
                        catch (Exception e) { LOG.fine("Camera vision cycle: " + e.getMessage()); }
                    });
                }
                // try-with-resources auto-closes -> awaits termination
            } catch (Exception e) {
                LOG.fine("Camera vision dispatch: " + e.getMessage());
            }
        }, 2, 5, TimeUnit.MINUTES);
        LOG.info("Camera vision active — minicpm-v observes 2 cameras every 5 min");

        // Goal 2: Java Learning — explore Zulu JDK 25, try sandbox commands
        var javaLearnService = new de.metis.modules.hardware.JavaLearningService(agent.worldModel());
        var javaScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "java-learner");
            t.setDaemon(true);
            return t;
        });
        javaScheduler.scheduleAtFixedRate(() -> {
            try {
                if (agent.core().goals().kanbanBoard() != null) {
                    var board = agent.core().goals().kanbanBoard();
                    // Add to BACKLOG — Metis pulls when ready
                    Goal javaGoal = new Goal(
                            "Learn Java: Zulu JDK 25 exploration",
                            "java-learn", 45, 0.45, 1,
                            Goal.ServiceClass.STANDARD, Goal.ResourceType.CPU_HEAVY, null);
                    board.add(javaGoal);
                    LOG.fine("Kanban: BACKLOG ← Java learning goal");

                    // Pull from READY: check if a java-learn goal is ready
                    var snapshot = board.snapshot();
                    Goal readyGoal = snapshot.ready().stream()
                            .filter(g -> g.category().equals("java-learn"))
                            .findFirst().orElse(null);
                    if (readyGoal != null) {
                        readyGoal = board.pull(); // READY → IN_PROGRESS (respects WIP)
                        if (readyGoal != null) {
                            int result;
                            if (RANDOM.nextDouble() < 0.3 && javaLearnService.commandsSucceeded() >= 5) {
                                result = javaLearnService.tryCompileAndRun();
                            } else {
                                result = javaLearnService.exploreOneTool();
                            }
                            if (result >= 0) {
                                board.complete(readyGoal.id());
                            } else {
                                board.requeue(readyGoal);
                            }
                            LOG.info("JavaLearn: " + result + " beliefs, "
                                    + javaLearnService.commandsSucceeded() + "/"
                                    + javaLearnService.commandsTried() + " success");
                        }
                    }
                } else {
                    javaLearnService.exploreOneTool();
                }
            } catch (Exception e) {
                LOG.fine("Java learning cycle: " + e.getMessage());
            }
        }, 5, 15, TimeUnit.MINUTES);
        LOG.info("Java learning active — Zulu JDK 25 exploration every 15 min");

        // Build the runtime, wiring in the HTTP server for evolution control
        final MetisHttpServer api = httpServer;

        // Build and run
        var runtime = AgentMain.builder(agent)
                .tickInterval(interval)
                .persistTo(persist)
                .knowledgeStore(knowledgeStore)
                .idleGoalInterval(10)
                .emergenceReportInterval(50)
                .maxTicks(maxTicks)
                .build();

        // Phase 5: Blue/Green Rollback + Bugfixing
        Path deployDir = Path.of(".").toAbsolutePath();
        var rollbackMgr = new RollbackManager(deployDir);
        rollbackMgr.promoteToBlue("0.3.0-phase5"); // initial deployment
        runtime.setRollbackManager(rollbackMgr);

        var bugfixer = new BugfixingAgent().withRollbackManager(rollbackMgr);
        runtime.setBugfixingAgent(bugfixer);

        // Wire health monitoring to HTTP API
        if (httpServer != null) {
            httpServer.setRollbackManager(rollbackMgr);
            httpServer.setBugfixingAgent(bugfixer);
        }
        LOG.info("Blue/Green rollback + autonomous bugfixing active (Phase 5)");

        if (evolution) {
            runtime = AgentMain.builder(agent)
                    .tickInterval(interval)
                    .persistTo(persist)
                    .knowledgeStore(knowledgeStore)
                    .withEvolution()
                    .idleGoalInterval(10)
                    .emergenceReportInterval(50)
                    .maxTicks(maxTicks)
                    .build();
        }

        try {
            // maxTicks is now handled inside run()
            runtime.run();
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Agent runtime crashed", e);
            runtime.persistState();
        } finally {
            if (notifier != null) notifier.stop();
            coordinator.shutdown();
            for (var trigger : eventTriggers) {
                trigger.stop();
            }
            if (telegramBot != null) {
                telegramBot.stop();
            }
            if (httpServer != null) {
                httpServer.stop();
            }
        }
    }

    /**
     * Run speech-loop learning: pick a random Wikipedia article,
     * speak it via Piper TTS, listen back via Vosk STT, compare.
     * Only called for ~5% of Wikipedia learning cycles.
     */
    private static int runSpeechLoop(
            de.metis.modules.knowledge.WikipediaKnowledgeService wikiKnowledge,
            Goal goal, Agent agent) {
        try {
            // Pick a random article from the wiki-feed directory
            Path wikiDir = Path.of("/home/prometheus/wiki-feed");
            if (!Files.exists(wikiDir)) return wikiKnowledge.learnOneArticle();

            List<Path> articles;
            try (var stream = Files.list(wikiDir)) {
                articles = stream
                        .filter(p -> p.toString().endsWith(".txt"))
                        .collect(java.util.stream.Collectors.toList());
            }
            if (articles.isEmpty()) return wikiKnowledge.learnOneArticle();

            Path article = articles.get(RANDOM.nextInt(articles.size()));
            String content = Files.readString(article);
            String[] lines = content.split("\n", 2);
            String title = lines[0].trim();
            String text = lines.length > 1 ? lines[1].trim() : title;

            // Run speech loop
            var speechLoop = new de.metis.modules.speech.SpeechLoopAction(text, title);
            var result = speechLoop.execute();

            if (result.success()) {
                LOG.info("SpeechLoop [" + title + "]: " + result.body());
                // Also learn the article normally
                wikiKnowledge.learnOneArticle();
                return 1;
            } else {
                LOG.warning("SpeechLoop failed [" + title + "]: " + result.body());
                return 0;
            }
        } catch (Exception e) {
            LOG.warning("SpeechLoop error: " + e.getMessage());
            return wikiKnowledge.learnOneArticle(); // fallback
        }
    }
}
