package de.agicore.kernel.evolution;

import de.agicore.kernel.metrics.PerformanceMetrics;
import de.agicore.kernel.planner.EvolvableModule;
import de.agicore.kernel.planner.Planner;
import de.agicore.kernel.safety.SafetyGuard;

import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Controls the self-modification pipeline.
 * <p>
 * Pipeline:
 * <ol>
 *   <li><b>Detect plateau:</b> fitness stagnates → trigger evolution</li>
 *   <li><b>Mutate:</b> generate a variant of an evolvable module</li>
 *   <li><b>Compile:</b> verify the variant compiles</li>
 *   <li><b>Shadow-evaluate:</b> run 300 ticks in isolated environment</li>
 *   <li><b>Compare:</b> mutant fitness vs baseline</li>
 *   <li><b>Accept/Reject:</b> promote or rollback</li>
 * </ol>
 * <p>
 * Phase 1 (current): dummy mutation — reorders a code comment to test
 * the pipeline without LLM integration. The variant is always identical
 * in behavior, serving as a pipeline smoke test.
 * <p>
 * Phase 2 (future): LLM-powered mutation via Ollama API.
 */
public class EvolutionManager {

    private static final Logger LOG = Logger.getLogger(EvolutionManager.class.getName());

    /** Fitness stagnation threshold: trigger evolution if no improvement for N ticks. */
    private static final int STAGNATION_TICKS = 200;

    /** Shadow evaluation length in ticks. */
    private static final int SHADOW_TICKS = 300;

    private final SafetyGuard safety;
    private final ShadowEnvironment shadowEnv;
    private final List<EvolvableModule> evolvableModules = new ArrayList<>();

    private double baselineFitness = 0.0;
    private long lastImprovementTick = 0;
    private int evolutionCycles = 0;
    private int acceptedMutations = 0;
    private int rejectedMutations = 0;

    public EvolutionManager() {
        this(new SafetyGuard(), new ShadowEnvironment());
    }

    public EvolutionManager(SafetyGuard safety, ShadowEnvironment shadowEnv) {
        this.safety = safety;
        this.shadowEnv = shadowEnv;
    }

    /** Register an evolvable module for potential mutation. */
    public void register(EvolvableModule module) {
        evolvableModules.add(module);
        LOG.info("Registered evolvable module: " + module.moduleName() + " v" + module.version());
    }

    /**
     * Check whether evolution should be triggered.
     * Trigger when: fitness hasn't improved for STAGNATION_TICKS.
     *
     * @param tickCount     current tick
     * @param currentFitness current fitness score
     * @return true if evolution should run
     */
    public boolean shouldEvolve(long tickCount, double currentFitness) {
        if (currentFitness > baselineFitness + FitnessFunction.minImprovement()) {
            baselineFitness = currentFitness;
            lastImprovementTick = tickCount;
            return false;
        }
        return (tickCount - lastImprovementTick) >= STAGNATION_TICKS;
    }

    /**
     * Run one evolution cycle: mutate, compile-check, shadow-evaluate, compare.
     *
     * @param baselineFitness current baseline fitness to beat
     * @return true if a mutation was accepted
     */
    public EvolutionResult evolve(double baselineFitness) {
        this.baselineFitness = baselineFitness;
        evolutionCycles++;

        try {
            safety.beginCycle();

            if (evolvableModules.isEmpty()) {
                return new EvolutionResult(false, "No evolvable modules registered");
            }

            // Pick a random module to mutate
            EvolvableModule target = evolvableModules.get(
                    new Random().nextInt(evolvableModules.size()));

            safety.allowMutation();

            // ── Step 1: Generate mutant source ────────────────
            String mutantSource = dummyMutate(target.moduleName());
            LOG.info("Mutation generated for: " + target.moduleName());

            // ── Step 2: Compile check (always passes for dummy) ──
            if (!compileCheck(mutantSource)) {
                safety.recordFailure();
                rejectedMutations++;
                return new EvolutionResult(false, "Compilation failed");
            }

            // ── Step 3: Shadow evaluation ─────────────────────
            ShadowEvalResult shadowResult = runShadowEvaluation(target, SHADOW_TICKS);
            double mutantFitness = shadowResult.fitness();

            // ── Step 4: Compare ───────────────────────────────
            double improvement = mutantFitness - baselineFitness;
            LOG.info(() -> String.format(
                    "Evolution: mutant=%.3f baseline=%.3f delta=%+.3f",
                    mutantFitness, baselineFitness, improvement));

            if (improvement > FitnessFunction.minImprovement()) {
                // Accept
                promote(target.moduleName(), mutantSource);
                safety.recordSuccess();
                acceptedMutations++;
                this.baselineFitness = mutantFitness;
                lastImprovementTick = 0; // reset stagnation counter
                return new EvolutionResult(true,
                        "Accepted: +" + String.format("%.3f", improvement));
            } else {
                // Reject
                rollback(target.moduleName());
                safety.recordFailure();
                rejectedMutations++;
                return new EvolutionResult(false,
                        "Rejected: delta=" + String.format("%.3f", improvement)
                                + " ≤ " + FitnessFunction.minImprovement());
            }

        } catch (SafetyGuard.SafetyViolationException e) {
            LOG.warning("Safety violation: " + e.getMessage());
            return new EvolutionResult(false, "Safety: " + e.getMessage());
        }
    }

    /**
     * Dummy mutation: appends a timestamp comment to the source.
     * This always compiles and has identical behavior — a pipeline
     * smoke test. Replace with LLM call in Phase 2.
     */
    private String dummyMutate(String moduleName) {
        return "// Dummy mutation for " + moduleName
                + " generated at " + java.time.Instant.now()
                + "\n// Evolution cycle: " + evolutionCycles
                + "\n// This is a pipeline smoke test — no behavioral change.\n";
    }

    /** Check if a source compiles (always true for dummy). */
    private boolean compileCheck(String source) {
        // Dummy: always passes. Real implementation would run javac.
        return true;
    }

    /**
     * Run a shadow agent evaluation for N ticks.
     * Creates an isolated agent with the mutant planner, runs it
     * against the ShadowEnvironment, and measures fitness.
     */
    private ShadowEvalResult runShadowEvaluation(EvolvableModule target, int maxTicks) {
        // Shadow agent: simulate goal processing with canned responses
        int successes = 0, failures = 0, plans = 0, attempts = 0;
        double totalError = 0;
        double entropy = 0.5; // simulated entropy

        for (int tick = 0; tick < maxTicks; tick++) {
            attempts++;

            // Simulate goal selection
            String agentAction = tick % 3 == 0 ? "shell" : "http";
            String goalDesc = "Shadow goal " + tick;

            // Execute in shadow environment
            var result = shadowEnv.execute(agentAction, goalDesc);
            plans++;

            if (result.success()) {
                successes++;
            } else {
                failures++;
            }
            totalError += result.success() ? 0.0 : 1.0;

            // Safety: check tick limit
            if (tick >= safety.maxTicks()) break;
        }

        double successRate = (successes + failures) == 0 ? 1.0
                : (double) successes / (successes + failures);
        double planningEff = attempts == 0 ? 1.0 : (double) plans / attempts;
        double avgError = attempts == 0 ? 0 : totalError / attempts;

        // Build a simplified PerformanceMetrics snapshot
        var metrics = new de.agicore.kernel.metrics.PerformanceMetrics();
        // Simulate: record successes and plan efficiency
        // (In real implementation, we'd run the actual agent)
        double fitness = FitnessFunction.evaluate(
                createShadowMetrics(successRate, planningEff, avgError), entropy);

        return new ShadowEvalResult(fitness, successRate, planningEff, attempts);
    }

    /** Create a minimal PerformanceMetrics snapshot from shadow results. */
    private PerformanceMetrics createShadowMetrics(double successRate,
                                                    double planningEff, double avgError) {
        // Hack: directly set internal state via reflection or subclass
        // For simplicity, create a wrapper
        var metrics = new PerformanceMetrics();
        // Simulate ticks to build up metrics
        for (int i = 0; i < 10; i++) {
            var dummyResult = new de.agicore.kernel.action.ActionResult(
                    "shadow", true, "ok", null,
                    java.time.Instant.now(), java.time.Duration.ZERO);
            metrics.recordTick(null, dummyResult,
                    new de.agicore.kernel.meta.MetaCognition());
        }
        return metrics;
    }

    /** Promote a mutation (git merge). Dummy: logs only. */
    private void promote(String moduleName, String source) {
        LOG.info(() -> "PROMOTED: " + moduleName + " (dummy — no git merge)");
    }

    /** Rollback a mutation (git reset). Dummy: logs only. */
    private void rollback(String moduleName) {
        LOG.info(() -> "ROLLBACK: " + moduleName + " (dummy — no git reset)");
    }

    public int evolutionCycles() { return evolutionCycles; }
    public int acceptedMutations() { return acceptedMutations; }
    public int rejectedMutations() { return rejectedMutations; }
    public double baselineFitness() { return baselineFitness; }

    /** Result of a shadow evaluation. */
    public record ShadowEvalResult(double fitness, double successRate,
                                    double planningEff, int ticks) {}

    /** Result of an evolution cycle. */
    public record EvolutionResult(boolean accepted, String message) {
        @Override
        public String toString() {
            return (accepted ? "✓ " : "✗ ") + message;
        }
    }
}
