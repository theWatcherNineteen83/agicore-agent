package de.metis.kernel.core;

import de.metis.kernel.evolution.EvolutionManager;
import de.metis.kernel.evolution.FitnessFunction;
import de.metis.kernel.action.Action;
import de.metis.kernel.action.ActionExecutor;
import de.metis.kernel.action.ActionResult;
import de.metis.kernel.goal.Goal;
import de.metis.kernel.goal.GoalManager;
import de.metis.kernel.memory.Experience;
import de.metis.kernel.memory.MemoryConsolidator;
import de.metis.kernel.memory.ShortTermMemory;
import de.metis.kernel.meta.MetaCognition;
import de.metis.kernel.meta.MetaRepresentation;
import de.metis.kernel.metrics.PerformanceMetrics;
import de.metis.kernel.optimize.HyperparameterMutator;
import de.metis.kernel.planner.PlanValidator;
import de.metis.kernel.goal.Goal;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import de.metis.kernel.planner.Planner;

import java.util.function.Supplier;
import de.metis.kernel.self.SelfModel;
import de.metis.kernel.workspace.ContentItem;
import de.metis.kernel.workspace.GlobalWorkspace;
import de.metis.kernel.world.WorldModel;
import de.metis.kernel.world.CausalModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import java.util.function.Consumer;

/**
 * Phase 3: Global Workspace + Self Model + World Model.
 * <p>
 * The cognitive loop is extended with an attention bottleneck:
 * <pre>
 *   SENSORS → (Global Workspace: Competition → Broadcast) → PERCEIVE → PLAN → EXECUTE → OBSERVE → LEARN
 *              ↑                                              │
 *              └── Self Model ── World Model ── Meta ─────────┘
 * </pre>
 * <p>
 * Before each tick, the Global Workspace runs a competition cycle.
 * Content from memory, goals, self-model, world-model, and meta-representation
 * competes for limited attention. The winners (broadcast) bias perception
 * and planning — the agent "attends to" what won.
 */
public class AgentCoreLoop {
    
    private final Supplier<de.metis.kernel.goal.Goal> idleGoalSupplier;
    private final CausalModel causalModel = new CausalModel();

    private static final Logger LOG = Logger.getLogger(AgentCoreLoop.class.getName());

    /** Categories the CoreLoop can execute. Others (speech-loop, wikipedia-learn, java-learn)
     * are handled by specialized schedulers. */
    private static final Set<String> CORE_CATEGORIES = Set.of(
            "shell", "http", "filesystem", "webscrape", "linux-explore",
            "api-explore", "hw-profile", "deepnetts", "tornadovm",
            "exploration", "meta", "chat", "mqtt-event", "ha-event",
            "weather", "weather-trend", "webcam", "adsb", "adsb-unusual",
            "adsb-proximity", "shadow", "unknown", "analysis"
    );

    private static final int CONSOLIDATION_INTERVAL = 10;
    private static final int ADAPTATION_INTERVAL = 15;
    private static final int MUTATION_INTERVAL = 50;

    private final GoalManager goals;
    private final Planner planner;
    private final PlanValidator planValidator;
    private final ActionExecutor executor;
    private final ShortTermMemory stm;
    private final MemoryConsolidator consolidator;
    private final MetaCognition meta;
    private final PerformanceMetrics metrics;
    private final HyperparameterMutator hyperMutator;

    // ── Phase 3 additions ─────────────────────────────────────
    private final GlobalWorkspace workspace;
    private final SelfModel selfModel;
    private final WorldModel worldModel;
    private final MetaRepresentation metaRepr;

    // ── Evolution (Phase 4) ───────────────────────────────────
    private final EvolutionManager evolutionManager;
    /** Trigger evolution check every N ticks. */
    private static final int EVOLUTION_CHECK_INTERVAL = 100;
    /**
     * Maximum auto-approval level. Actions at or below this level
     * execute automatically; actions above require human approval.
     * <p>
     * Default: NOTIFY (AUTO + NOTIFY auto-execute, CONFIRM + FORBIDDEN blocked)
     */
    private Action.ApprovalLevel maxAutoApprovalLevel = Action.ApprovalLevel.NOTIFY;

    /** @deprecated use {@link #setMaxAutoApprovalLevel(Action.ApprovalLevel)} */
    @Deprecated
    private boolean requireApprovalForWrite = true;

    private long tickCount = 0;
    // ── Phase 12a: Self-healing exception handler ─────────────
    private Consumer<Throwable> exceptionHandler = null;

    private CognitiveCycle currentPhase = CognitiveCycle.PERCEIVE;
    private HyperparameterMutator.Configuration activeConfig;

    /** Multi-step plan cache: goal id -> remaining actions */
    private final java.util.Map<String, java.util.List<String>> pendingPlans = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_PLAN_PERSIST_TICKS = 3;
    /** Consecutive goal failures for self-reflection trigger (Point 4). */
    private int consecutiveGoalFailures = 0;

    public AgentCoreLoop(GoalManager goals, Planner planner, PlanValidator planValidator,
                         ActionExecutor executor,
                         MemoryConsolidator consolidator, MetaCognition meta,
                         PerformanceMetrics metrics, HyperparameterMutator hyperMutator,
                         GlobalWorkspace workspace, SelfModel selfModel,
                         WorldModel worldModel, MetaRepresentation metaRepr,
                         EvolutionManager evolutionManager,
                         Supplier<Goal> idleGoalSupplier) {
        this.goals = goals;
        this.planner = planner;
        this.planValidator = planValidator;
        this.executor = executor;
        this.stm = consolidator.stm();
        this.consolidator = consolidator;
        this.meta = meta;
        this.metrics = metrics;
        this.hyperMutator = hyperMutator;
        this.activeConfig = hyperMutator.current();

        this.workspace = workspace;
        this.selfModel = selfModel;
        this.worldModel = worldModel;
        this.metaRepr = metaRepr;
        this.evolutionManager = evolutionManager;
        this.idleGoalSupplier = idleGoalSupplier;

        this.selfModel.bind(meta, metrics);
    }

    /**
     * Execute one full cognitive cycle with Global Workspace attention routing.
     */
    public ActionResult tick() {
    try {
        tickCount++;

        // Periodic goal cleanup: prevent unbounded accumulation from MQTT floods
        if (tickCount % 30 == 0) {
            goals.purgeExpired(java.time.Duration.ofMinutes(5));
            // Also clean stale plan cache entries
            if (pendingPlans.size() > 20) {
                pendingPlans.clear();
                LOG.fine("Plan cache cleared (size exceeded 20)");
            }
        }

        // ── Phase 3: GLOBAL WORKSPACE BROADCAST ─────────────────
        // Collect content from all subsystems and run the competition.
        // Winners bias perception and planning in this tick.
        List<ContentItem> allContent = collectAllContent();
        List<ContentItem> broadcast = workspace.broadcast(allContent);

        // ── PERCEIVE ────────────────────────────────────────────
        currentPhase = CognitiveCycle.PERCEIVE;

        // ── Goal Selection: Kanban pull-first (WIP limits), legacy fallback ──
        // Promote backlog goals to READY for pull consideration
        if (goals.kanbanBoard() != null) {
            goals.kanbanBoard().promoteReady();
        }

        // 1. Try Kanban pull (respects WIP limits, service classes, resource types)
        //    Only pull goals the CoreLoop can execute (shell, http, filesystem, exploration, meta)
        //    Specialized goals (speech-loop, wikipedia-learn, java-learn) are handled by their schedulers
        Goal kanbanResult = null;
        if (goals.kanbanBoard() != null) {
            var board = goals.kanbanBoard();
            // Find first READY goal in a category the CoreLoop can handle
            Goal readyGoal = board.snapshot().ready().stream()
                    .filter(g -> CORE_CATEGORIES.contains(g.category()))
                    .findFirst().orElse(null);
            if (readyGoal != null) {
                kanbanResult = board.pull();
            }
        }
        if (kanbanResult != null) {
            final Goal kg = kanbanResult;
            LOG.fine(() -> "Kanban pull: " + kg.description()
                    + " [svc=" + kg.serviceClass() + " res=" + kg.resourceType() + "]"
                    + " WIP=" + goals.kanbanBoard().totalWip()
                    + "/" + goals.kanbanBoard().totalWipPercent() + "%");
        }

        // 2. Legacy fallback: workspace-biased selection (when Kanban returns null)
        Goal legacyResult = null;
        if (kanbanResult == null) {
            List<String> attentionKeywords = broadcast.stream()
                    .map(ContentItem::summary)
                    .flatMap(s -> java.util.Arrays.stream(s.toLowerCase().split("\\s+")))
                    .filter(w -> w.length() > 3)
                    .distinct()
                    .toList();
            legacyResult = goals.nextGoalBiased(attentionKeywords, 25);
        }

        final Goal goal = kanbanResult != null ? kanbanResult : legacyResult;
        Goal resolvedGoal = goal;

        // Idle exploration: generate curiosity goal if nothing to do
        if (resolvedGoal == null) {
            if (tickCount % 5 == 0 && idleGoalSupplier != null) {
                var idleGoal = idleGoalSupplier.get();
                if (idleGoal != null) {
                    resolvedGoal = goals.add(idleGoal);
                    // With Kanban: add to board and try to pull right away
                    if (goals.kanbanBoard() != null) {
                        goals.kanbanBoard().add(resolvedGoal);
                        goals.kanbanBoard().promoteReady();
                        resolvedGoal = goals.pullFromBoard();
                    }
                    LOG.fine("Idle: curiosity-generated goal");
                }
            }
        }
        if (resolvedGoal == null) {
            LOG.fine("No active goals — idle tick");
            metrics.recordTick(null, null, meta);
            return null;
        }
        final Goal currentGoal = resolvedGoal;

        // ── Point 2: Knowledge Retrieval Priority ──
        // Before planning, check if this goal can be answered from beliefs
        String goalDesc = currentGoal.description().toLowerCase();
        boolean isKnowledgeGoal = goalDesc.contains("what do i")
                || goalDesc.contains("what does metis")
                || goalDesc.contains("what does the")
                || goalDesc.contains("what is")
                || goalDesc.contains("tell me")
                || goalDesc.contains("explain")
                || goalDesc.contains("answer based")
                || goalDesc.contains("from your stored beliefs");
        if (isKnowledgeGoal && worldModel != null) {
            var beliefs = worldModel.query(currentGoal.description(), 5);
            if (beliefs.size() >= 3) {
                double avgConf = beliefs.stream().mapToDouble(de.metis.kernel.world.Belief::confidence).average().orElse(0);
                if (avgConf > 0.6) {
                    LOG.info(() -> "Knowledge retrieval: " + beliefs.size()
                            + " relevant beliefs found (avg conf=" + String.format("%.2f", avgConf)
                            + ") for: " + currentGoal.description());
                    // High-confidence beliefs exist — let planner include them, skip LLM fallback
                }
            }
        }

        // Attention bias: if self/world/meta content won, adjust perception
        Optional<ContentItem> focus = workspace.focus();
        String attentionSummary = focus.map(ContentItem::summary).orElse("none");

        List<Experience> recentHistory = stm.recent(20);
        LOG.fine(() -> "Perceive: goal=" + currentGoal.description()
                + " history=" + recentHistory.size()
                + " attention=" + attentionSummary
                + " conf=" + String.format("%.2f", meta.confidence()));

        // ── PLAN ─────────────────────────────────────────────────
        currentPhase = CognitiveCycle.PLAN;
        // Fix #1: planner receives broadcast (causal workspace → planning)
        // Phase 1bis: Multi-Step Plan Cache — execute cached steps before re-planning
        java.util.List<String> plan = null;
        if (pendingPlans.containsKey(currentGoal.id())) {
            java.util.List<String> cached = pendingPlans.get(currentGoal.id());
            if (cached != null && !cached.isEmpty()) {
                plan = cached;
                LOG.fine(() -> "Multi-step: reusing cached plan (" + cached.size() + " steps remaining) for: " + currentGoal.description());
            } else {
                pendingPlans.remove(currentGoal.id());
            }
        }
        if (plan == null) {
            plan = planner.plan(currentGoal, recentHistory, broadcast, meta);
        }
        if (plan.isEmpty()) {
            LOG.warning(() -> "No plan for goal: " + currentGoal.description() + " — deferring");
            goals.recordOutcome(currentGoal, false);
            if (goals.kanbanBoard() != null) {
                goals.requeueOnBoard(currentGoal);
            } else {
                goals.complete(currentGoal.id());
            }
            metrics.recordTick(currentGoal, null, meta);
            worldModel.observe("goal:" + currentGoal.description() + " has no plan", false);
            metaRepr.recordStrategy("keyword-match", false);
            return null;
        }

        // Huyen Kap. 6 + 1.4: Plan validieren vor Ausführung
        // Enhanced with context-aware validation (goal relevance, confidence gate)
        PlanValidator.ValidationResult validation = planValidator.validateWithContext(plan, currentGoal, meta);
        if (!validation.valid()) {
            LOG.warning("Plan rejected by validator: " + validation.reason());
            goals.recordOutcome(currentGoal, false);
            if (goals.kanbanBoard() != null) {
                goals.requeueOnBoard(currentGoal);
            } else {
                goals.complete(currentGoal.id());
            }
            metrics.recordTick(currentGoal, null, meta);
            return null;
        }
        LOG.fine(() -> "Plan validated: " + validation.reason());

        String actionName = plan.getFirst();

        // ── Human-in-the-Loop: Tiered Approval-Gate (Huyen Kap.6) ──
        // Read vs. Write Differenzierung: vier Approval-Level statt binär
        Action.ApprovalLevel level = executor.getApprovalLevel(actionName);
        if (level.ordinal() > maxAutoApprovalLevel.ordinal()) {
            String reason = switch (level) {
                case CONFIRM -> "requires human confirmation";
                case FORBIDDEN -> "FORBIDDEN — never auto-executed";
                default -> "above auto-approval threshold";
            };
            String emoji = level == Action.ApprovalLevel.FORBIDDEN ? "🚫" : "⛔";
            LOG.warning(() -> emoji + " " + level + " action blocked (" + reason + "): "
                    + actionName + " for goal: " + currentGoal.description());
            goals.recordOutcome(currentGoal, false);
            if (goals.kanbanBoard() != null) {
                goals.requeueOnBoard(currentGoal);
            } else {
                goals.complete(currentGoal.id());
            }
            metrics.recordTick(currentGoal, null, meta);
            return ActionResult.fail(actionName, "Blocked: " + reason, java.time.Instant.now());
        }
        if (level == Action.ApprovalLevel.NOTIFY) {
            LOG.info(() -> "📝 Auto-executing NOTIFY action: " + actionName
                    + " (maxAuto=" + maxAutoApprovalLevel + ")");
        }

        // ── EXECUTE ──────────────────────────────────────────────
        currentPhase = CognitiveCycle.EXECUTE;
        ActionResult result = executor.execute(actionName);

        // ── OBSERVE ──────────────────────────────────────────────
        currentPhase = CognitiveCycle.OBSERVE;
        // Fix #2: prediction error = |expectedSuccess - actual|
        double expectedSuccess = planner.expectedSuccess(currentGoal, actionName);
        double actualOutcome = result.success() ? 1.0 : 0.0;
        double predictionError = Math.abs(expectedSuccess - actualOutcome);

        // ── Point 4: Self-Reflection on failure ──
        // Track consecutive failures for self-reflection trigger
        if (result != null && !result.success()) {
            consecutiveGoalFailures++;
            if (consecutiveGoalFailures >= 3 && tickCount > 20) {
                LOG.warning("META: " + consecutiveGoalFailures + " consecutive failures —"
                        + " goal: " + currentGoal.description() + " action: " + actionName
                        + " | triggering self-reflection");
                // Log failure pattern for metacognition
                meta.observe(0.9); // self-reflection signal
                consecutiveGoalFailures = 0; // reset after triggering
            }
        } else {
            consecutiveGoalFailures = 0;
        }

        // Fix #5: enriched feature vector (context + action type + world state)
        double[] vector = {
                currentGoal.priority() / 100.0,          // goal urgency
                currentGoal.expectedReward(),             // expected utility
                actualOutcome,                     // actual result
                predictionError,                   // how wrong we were
                currentGoal.resourceCost() / 10.0,        // normalized cost
                actionName.equals("shell") ? 1.0 : 0.0,  // action type: shell
                actionName.equals("http") ? 1.0 : 0.0,   // action type: http
                meta.confidence(),                 // pre-action confidence
                meta.isSurprised() ? 1.0 : 0.0,    // surprise state
                goals.activeCount() / 10.0         // goal load
        };
        Experience exp = new Experience(
                currentGoal.description(), actionName, result.success(),
                result.body(), predictionError, vector);

        // ── LEARN ─────────────────────────────────────────────────
        currentPhase = CognitiveCycle.LEARN;

        // Phase 1–2: core learning
        stm.add(exp);
        meta.observe(predictionError);
        planner.learnFromOutcome(goal, plan, result);
        goals.recordOutcome(goal, result.success());
        metrics.recordTick(goal, result, meta);
        causalModel.observe("action:" + result.name(), "goal:" + currentGoal.description().substring(0, Math.min(currentGoal.description().length(), 50)), result.success() ? "success" : "failure", result.success());

        // Phase 3: update world model from observation
        worldModel.observe(
                "action:" + actionName + " for goal:" + currentGoal.description() + " → "
                        + (result.success() ? "success" : "failure"),
                result.success());

        // Phase 3: calibrate self-model with |predicted - actual|
        selfModel.calibrate(expectedSuccess > 0.5, result.success());

        // Fix #6: SelfModel forward-model — track expected vs actual performance
        selfModel.recordForwardPrediction(actionName, expectedSuccess, actualOutcome, predictionError);

        // Phase 3: take self-state snapshot for history
        selfModel.fullSnapshot(goals.activeCount(), stm.size(),
                consolidator.ltm().size(), attentionSummary);

        // Phase 3: record strategy outcome
        String strategy = focus.map(f -> f.source() + "-bias").orElse("default");
        metaRepr.recordStrategy(strategy, result.success());

        // Periodic maintenance
        if (tickCount % CONSOLIDATION_INTERVAL == 0) {
            consolidator.maybeConsolidate(true);
        } else {
            consolidator.maybeConsolidate(false);
        }
        if (tickCount % ADAPTATION_INTERVAL == 0) {
            goals.adaptPriorities();
        }
        if (tickCount % MUTATION_INTERVAL == 0) {
            evaluateAndMutateHyperparameters();
        }
        // Evolution check: trigger self-modification on stagnation
        if (tickCount % EVOLUTION_CHECK_INTERVAL == 0) {
            checkEvolution();
        }

        if (result.success()) {
            // Multi-step: don't mark complete if plan has more steps
            int planSize = plan.size();
            if (plan.size() > 1) {
                LOG.fine(() -> "Multi-step: step completed, " + (planSize-1) + " steps remaining for: " + currentGoal.description());
                // Goal continues — will pick up next step from cache
            } else {
                pendingPlans.remove(currentGoal.id());
                if (goals.kanbanBoard() != null) {
                    var flowMetrics = goals.completeOnBoard(currentGoal.id());
                    if (flowMetrics != null) {
                        LOG.info(() -> "Kanban flow: " + flowMetrics);
                    }
                } else {
                    goals.complete(currentGoal.id());
                }
            }
        } else {
            // Requeue failed goals on Kanban board for retry
            if (goals.kanbanBoard() != null) {
                goals.requeueOnBoard(currentGoal);
            }
        }

        LOG.info(() -> String.format("Tick %d: %s → %s [%s] err=%.2f conf=%.2f attn=%s ent=%.2f | %s",
                tickCount, currentGoal.description(), actionName,
                result.success() ? "OK" : "FAIL",
                predictionError, meta.confidence(),
                attentionSummary, workspace.attentionEntropy(), metrics));

                return result;
        } catch (Exception e) {
            LOG.severe("Uncaught exception in tick: " + e.getMessage()
                    + " (" + e.getClass().getSimpleName() + ")");
            if (exceptionHandler != null) {
                try {
                    exceptionHandler.accept(e);
                } catch (Exception handlerEx) {
                    LOG.severe("Exception handler itself failed: " + handlerEx.getMessage());
                }
            }
            return null;
        }
    }

    /**
     * Phase 3: Collect content items from all subsystems for the
     * Global Workspace competition.
     */
    private List<ContentItem> collectAllContent() {
        List<ContentItem> all = new ArrayList<>();

        // 1. Self-model content (health, surprise, trends)
        all.addAll(selfModel.generateSelfContent(
                goals.activeCount(), stm.size(), consolidator.ltm().size()));

        // 2. World model content (beliefs about the environment)
        all.addAll(worldModel.generateWorldContent());

        // 3. Meta-representation content (strategy reflection)
        all.addAll(metaRepr.generateMetaContent(
                goals.activeCount(), stm.size(), consolidator.ltm().size()));

        // 4. Goal content — active goals compete for attention
        for (Goal g : goals.all()) {
            if (!g.active()) continue;
            double salience = g.priority() / 100.0;
            double relevance = g.expectedReward();
            double novelty = 1.0 - relevance; // known goals are less novel
            all.add(new ContentItem("goal",
                    "Goal: " + g.description(),
                    salience, novelty, relevance,
                    "goal priority=" + g.priority() + " reward=" + g.expectedReward()));
        }

        // 5. Memory content — recent high-salience experiences
        for (Experience exp : stm.recent(5)) {
            if (exp.salience() > 0.4) {
                all.add(new ContentItem("memory",
                        "Memory: " + exp.actionName() + " "
                                + (exp.success() ? "✓" : "✗") + " " + exp.goalDescription(),
                        exp.salience(), exp.predictionError(), 0.3,
                        "exp salience=" + String.format("%.2f", exp.salience())));
            }
        }

        return all;
    }

    /**
     * Fix #3: A/B-isolated hyperparameter evaluation.
     * Evaluates current config. Every MUTATION_INTERVAL×2 ticks,
     * starts an experiment with a mutated config.
     * Degradation check triggers rollback.
     */
    private void evaluateAndMutateHyperparameters() {
        double score = HyperparameterMutator.computeScore(
                metrics.goalSuccessRate(), metrics.planningEfficiency());
        hyperMutator.evaluate(score);

        // Check for degradation (triggers rollback if needed)
        hyperMutator.checkDegradation(score, 0.1);

        // Start experiment every 2×MUTATION_INTERVAL ticks
        if (tickCount % (MUTATION_INTERVAL * 2) == 0 && !hyperMutator.isExperimentRunning()) {
            activeConfig = hyperMutator.startExperiment();
            applyConfiguration(activeConfig);
        }
    }

    /**
     * Phase 4: Check if evolution should be triggered (fitness stagnation).
     * If so, run one evolution cycle with the EvolutionManager.
     */
    private void checkEvolution() {
        double currentFitness = FitnessFunction.evaluate(metrics, workspace.runningEntropy());
        if (evolutionManager.shouldEvolve(tickCount, currentFitness)) {
            LOG.info("Fitness stagnated — triggering evolution at tick " + tickCount);
            var result = evolutionManager.evolve(currentFitness);
            LOG.info("Evolution: " + result);
        }
    }

    private void applyConfiguration(HyperparameterMutator.Configuration config) {
        LOG.info("Applying hyperparameter config: " + config);
    }

    public void run(int maxTicks) {
        LOG.info("AgentCoreLoop starting — maxTicks=" + maxTicks
                + " goals=" + goals.activeCount()
                + " beliefs=" + worldModel.beliefCount());
        for (int i = 0; i < maxTicks; i++) {
            if (goals.activeCount() == 0) {
                LOG.info("All goals completed at tick " + i);
                break;
            }
            tick();
        }
        goals.adaptPriorities();
        LOG.info("AgentCoreLoop finished — " + goals.activeCount()
                + " goals remaining, " + tickCount + " ticks"
                + " | " + metrics
                + " | beliefs=" + worldModel.beliefCount()
                + " strategies=" + metaRepr.strategies().size()
                + " | evolution: " + evolutionManager.acceptedMutations()
                + "/" + evolutionManager.rejectedMutations()
                + " accepted/rejected");
    }

    // ── Accessors ────────────────────────────────────────────────
    public CognitiveCycle currentPhase() { return currentPhase; }
    public long tickCount() { return tickCount; }
    public GoalManager goals() { return goals; }
    public Planner planner() { return planner; }
    public ActionExecutor executor() { return executor; }
    /** @deprecated use {@link #setMaxAutoApprovalLevel(Action.ApprovalLevel)} */
    @Deprecated
    public void setRequireApprovalForWrite(boolean require) {
        this.requireApprovalForWrite = require;
        this.maxAutoApprovalLevel = require ? Action.ApprovalLevel.NOTIFY : Action.ApprovalLevel.FORBIDDEN;
    }
    /** @deprecated use {@link #getMaxAutoApprovalLevel()} */
    @Deprecated
    public boolean isRequireApprovalForWrite() { return requireApprovalForWrite; }

    /**
     * Set the maximum auto-approval level.
     * Actions at or below this level execute automatically.
     * <p>
     * Example: {@link Action.ApprovalLevel#AUTO} means only read-only actions
     * auto-execute; all writes need confirmation.
     */
    public void setMaxAutoApprovalLevel(Action.ApprovalLevel level) {
        this.maxAutoApprovalLevel = level;
        this.requireApprovalForWrite = (level == Action.ApprovalLevel.FORBIDDEN);
    }

    /** Phase 12a: Register handler for uncaught exceptions during tick. */
    public AgentCoreLoop withExceptionHandler(Consumer<Throwable> handler) {
        this.exceptionHandler = handler;
        return this;
    }

    /** @return the maximum auto-approval level */
    public Action.ApprovalLevel getMaxAutoApprovalLevel() { return maxAutoApprovalLevel; }
    public MetaCognition meta() { return meta; }
    public CausalModel causalModel() { return causalModel; }
    public ShortTermMemory stm() { return stm; }
    public MemoryConsolidator consolidator() { return consolidator; }
    public PerformanceMetrics metrics() { return metrics; }
    public HyperparameterMutator hyperMutator() { return hyperMutator; }
    public GlobalWorkspace workspace() { return workspace; }
    public SelfModel selfModel() { return selfModel; }
    public WorldModel worldModel() { return worldModel; }
    public MetaRepresentation metaRepr() { return metaRepr; }
    public EvolutionManager evolutionManager() { return evolutionManager; }
}
