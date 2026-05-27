package de.metis.kernel.core;

import de.metis.kernel.evolution.EvolutionManager;
import de.metis.kernel.evolution.FitnessFunction;
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
import java.util.logging.Logger;

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
    /** Enable human-in-the-loop approval for write-category actions. */
    private boolean requireApprovalForWrite = true;

    private long tickCount = 0;
    private CognitiveCycle currentPhase = CognitiveCycle.PERCEIVE;
    private HyperparameterMutator.Configuration activeConfig;

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
        tickCount++;

        // Periodic goal cleanup: prevent unbounded accumulation from MQTT floods
        if (tickCount % 30 == 0) {
            goals.purgeExpired(java.time.Duration.ofMinutes(5));
        }

        // ── Phase 3: GLOBAL WORKSPACE BROADCAST ─────────────────
        // Collect content from all subsystems and run the competition.
        // Winners bias perception and planning in this tick.
        List<ContentItem> allContent = collectAllContent();
        List<ContentItem> broadcast = workspace.broadcast(allContent);

        // ── PERCEIVE ────────────────────────────────────────────
        currentPhase = CognitiveCycle.PERCEIVE;

        // Hardening: extract attention keywords from broadcast
        List<String> attentionKeywords = broadcast.stream()
                .map(ContentItem::summary)
                .flatMap(s -> java.util.Arrays.stream(s.toLowerCase().split("\\s+")))
                .filter(w -> w.length() > 3)
                .distinct()
                .toList();

        // Hardening: workspace-biased goal selection
        Goal goal = goals.nextGoalBiased(attentionKeywords, 25);
        if (goal == null) {
            // Hardening: idle exploration — create a curiosity goal
            if (tickCount % 5 == 0 && idleGoalSupplier != null) {
                var idleGoal = idleGoalSupplier.get();
                if (idleGoal != null) {
                    goal = goals.add(idleGoal);
                    LOG.fine("Idle: curiosity-generated goal");
                }
            }
        }
        if (goal == null) {
            LOG.fine("No active goals — idle tick");
            metrics.recordTick(null, null, meta);
            return null;
        }
        // Effectively final copy for lambda capture
        final Goal currentGoal = goal;

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
        List<String> plan = planner.plan(goal, recentHistory, broadcast, meta);
        if (plan.isEmpty()) {
            LOG.warning(() -> "No plan for goal: " + currentGoal.description() + " — unachievable");
            goals.recordOutcome(currentGoal, false);
            goals.complete(currentGoal.id());
            metrics.recordTick(currentGoal, null, meta);
            worldModel.observe("goal:" + currentGoal.description() + " has no plan", false);
            metaRepr.recordStrategy("keyword-match", false);
            return null;
        }

        // Huyen Kap. 6 + 1.4: Plan validieren vor Ausführung
        // Enhanced with context-aware validation (goal relevance, confidence gate)
        PlanValidator.ValidationResult validation = planValidator.validateWithContext(plan, goal, meta);
        if (!validation.valid()) {
            LOG.warning("Plan rejected by validator: " + validation.reason());
            goals.recordOutcome(currentGoal, false);
            goals.complete(currentGoal.id());
            metrics.recordTick(currentGoal, null, meta);
            return null;
        }
        LOG.fine(() -> "Plan validated: " + validation.reason());

        String actionName = plan.getFirst();

        // ── Human-in-the-Loop: Approval-Gate für Write-Aktionen ──
        if (requireApprovalForWrite && executor.requiresApproval(actionName)) {
            LOG.warning(() -> "⛔ Write action blocked (requires human approval): " + actionName
                    + " for goal: " + currentGoal.description());
            goals.recordOutcome(currentGoal, false);
            goals.complete(currentGoal.id());
            metrics.recordTick(currentGoal, null, meta);
            return ActionResult.fail(actionName, "Blocked: requires human approval", java.time.Instant.now());
        }

        // ── EXECUTE ──────────────────────────────────────────────
        currentPhase = CognitiveCycle.EXECUTE;
        ActionResult result = executor.execute(actionName);

        // ── OBSERVE ──────────────────────────────────────────────
        currentPhase = CognitiveCycle.OBSERVE;
        // Fix #2: prediction error = |expectedSuccess - actual|
        double expectedSuccess = planner.expectedSuccess(goal, actionName);
        double actualOutcome = result.success() ? 1.0 : 0.0;
        double predictionError = Math.abs(expectedSuccess - actualOutcome);

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
        causalModel.observe("action:" + result.name(), "goal:" + goal.description().substring(0, Math.min(goal.description().length(), 50)), result.success() ? "success" : "failure", result.success());

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
            goals.complete(currentGoal.id());
        }

        LOG.info(() -> String.format("Tick %d: %s → %s [%s] err=%.2f conf=%.2f attn=%s ent=%.2f | %s",
                tickCount, currentGoal.description(), actionName,
                result.success() ? "OK" : "FAIL",
                predictionError, meta.confidence(),
                attentionSummary, workspace.attentionEntropy(), metrics));

        return result;
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
    public void setRequireApprovalForWrite(boolean require) { this.requireApprovalForWrite = require; }
    public boolean isRequireApprovalForWrite() { return requireApprovalForWrite; }
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
