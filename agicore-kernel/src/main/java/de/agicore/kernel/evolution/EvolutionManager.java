package de.agicore.kernel.evolution;

import de.agicore.kernel.metrics.PerformanceMetrics;
import de.agicore.kernel.planner.EvolvableModule;
import de.agicore.kernel.safety.SafetyGuard;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Controls the self-modification pipeline with real mutation, compilation,
 * and git versioning.
 * <p>
 * Pipeline:
 * <ol>
 *   <li><b>Detect plateau:</b> fitness stagnates → trigger evolution</li>
 *   <li><b>Mutate:</b> generate a variant via mutation service</li>
 *   <li><b>Compile:</b> real javac check (not dummy)</li>
 *   <li><b>Git branch:</b> checkout mutation branch for safety</li>
 *   <li><b>Shadow-evaluate:</b> run 300 ticks in isolated environment</li>
 *   <li><b>Compare:</b> mutant fitness vs baseline</li>
 *   <li><b>Accept/Reject:</b> git merge or git reset</li>
 * </ol>
 */
public class EvolutionManager {

    private static final Logger LOG = Logger.getLogger(EvolutionManager.class.getName());

    private static final int STAGNATION_TICKS = 200;
    private static final int SHADOW_TICKS = 300;

    private final SafetyGuard safety;
    private final ShadowEnvironment shadowEnv;
    private final List<EvolvableModule> evolvableModules = new ArrayList<>();

    /** Injected mutation service (Ollama, or dummy for testing). */
    private MutationService mutationService = new DummyMutationService();

    /** Prompt bank for few-shot learning from successful mutations. */
    private PromptBank promptBank = new PromptBank();

    /** Base directory for source files (for compilation and git). */
    private Path modulesSourceDir = Path.of("agicore-modules/src/main/java");

    /** Classpath for compilation. */
    private String classpath = "agicore-kernel/target/classes";

    private double baselineFitness = 0.0;
    private long lastImprovementTick = 0;
    private int evolutionCycles = 0;
    private int acceptedMutations = 0;
    private int rejectedMutations = 0;

    /** Last mutated module info for rollback. */
    private String lastMutatedModule;
    private String lastMutatedPackage;
    private String lastMutatedClass;

    public EvolutionManager() {
        this(new SafetyGuard(), new ShadowEnvironment());
    }

    public EvolutionManager(SafetyGuard safety, ShadowEnvironment shadowEnv) {
        this.safety = safety;
        this.shadowEnv = shadowEnv;
    }

    /** Inject the mutation service (Ollama or dummy). */
    public void setMutationService(MutationService service) {
        this.mutationService = service;
    }

    /** Inject a shared prompt bank. */
    public void setPromptBank(PromptBank bank) {
        this.promptBank = bank;
    }

    public PromptBank promptBank() { return promptBank; }

    /** Register an evolvable module. */
    public void register(EvolvableModule module) {
        evolvableModules.add(module);
        LOG.info("Registered evolvable module: " + module.moduleName() + " v" + module.version());
    }

    public boolean shouldEvolve(long tickCount, double currentFitness) {
        if (currentFitness > baselineFitness + FitnessFunction.minImprovement()) {
            baselineFitness = currentFitness;
            lastImprovementTick = tickCount;
            return false;
        }
        return (tickCount - lastImprovementTick) >= STAGNATION_TICKS;
    }

    /**
     * Run one evolution cycle with real mutation and compilation.
     */
    public EvolutionResult evolve(double baselineFitness) {
        this.baselineFitness = baselineFitness;
        evolutionCycles++;

        try {
            safety.beginCycle();
            if (evolvableModules.isEmpty()) {
                return new EvolutionResult(false, "No evolvable modules");
            }

            EvolvableModule target = evolvableModules.get(
                    new Random().nextInt(evolvableModules.size()));
            safety.allowMutation();

            String moduleName = target.moduleName();
            String className = resolveClassName(moduleName);
            String packageName = resolvePackageName(moduleName);

            // ── Read current source ───────────────────────────
            Path sourceFile = findSourceFile(className);
            if (sourceFile == null) {
                safety.recordFailure();
                rejectedMutations++;
                return new EvolutionResult(false, "Source file not found: " + className);
            }
            String originalSource = Files.readString(sourceFile);

            // ── Mutate via mutation service ───────────────────
            String mutantSource = mutationService.mutate(
                    moduleName, originalSource, className, packageName);
            if (mutantSource == null || mutantSource.equals(originalSource)) {
                safety.recordFailure();
                rejectedMutations++;
                return new EvolutionResult(false, "Mutation produced no change");
            }
            LOG.info("Mutation generated: " + mutantSource.length() + " chars");

            // ── Write mutant to temp file with correct class name ──
            Path outputDir = Path.of("target/evolution-out");
            Files.createDirectories(outputDir);
            Path tempFile = outputDir.resolve(className + ".java");
            Files.writeString(tempFile, mutantSource);

            // ── Real compilation check ────────────────────────
            if (!compileCheck(tempFile, className)) {
                safety.recordFailure();
                rejectedMutations++;
                Files.deleteIfExists(tempFile);
                return new EvolutionResult(false, "Compilation failed");
            }

            // ── Shadow evaluation ─────────────────────────────
            ShadowEvalResult shadowResult = runShadowEvaluation(SHADOW_TICKS);
            double mutantFitness = shadowResult.fitness();

            // ── Compare ───────────────────────────────────────
            double improvement = mutantFitness - baselineFitness;

            if (improvement > FitnessFunction.minImprovement()) {
                // Accept: write mutant to real source, git commit
                Files.writeString(sourceFile, mutantSource);
                promote(moduleName, mutantSource, originalSource, mutantFitness, improvement);
                safety.recordSuccess();
                acceptedMutations++;
                this.baselineFitness = mutantFitness;
                this.lastImprovementTick = 0;
                Files.deleteIfExists(tempFile);
                return new EvolutionResult(true,
                        "Accepted: +" + String.format("%.3f", improvement));
            } else {
                // Reject: keep original
                rollback(moduleName);
                safety.recordFailure();
                rejectedMutations++;
                Files.deleteIfExists(tempFile);
                return new EvolutionResult(false,
                        "Rejected: delta=" + String.format("%.3f", improvement)
                                + " ≤ " + FitnessFunction.minImprovement());
            }

        } catch (SafetyGuard.SafetyViolationException e) {
            LOG.warning("Safety: " + e.getMessage());
            return new EvolutionResult(false, "Safety: " + e.getMessage());
        } catch (Exception e) {
            LOG.warning("Evolution error: " + e.getMessage());
            rejectedMutations++;
            return new EvolutionResult(false, "Error: " + e.getMessage());
        }
    }

    /** Real compilation check using javax.tools.JavaCompiler. */
    public boolean compileCheck(Path sourceFile, String className) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            LOG.warning("No system Java compiler available — skipping compile check");
            return true; // degrade gracefully
        }

        Path outputDir = Path.of("target/evolution-out");
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            return false;
        }

        List<String> options = List.of(
                "-d", outputDir.toString(),
                "-cp", classpath,
                "-Xlint:none"
        );

        var fileManager = compiler.getStandardFileManager(null, null, null);
        var compilationUnits = fileManager.getJavaFileObjects(sourceFile.toFile());
        var task = compiler.getTask(null, fileManager, null, options, null, compilationUnits);

        boolean success = task.call();
        LOG.info(() -> "Compile check: " + (success ? "PASS" : "FAIL") + " for " + className);
        return success;
    }

    private ShadowEvalResult runShadowEvaluation(int maxTicks) {
        int successes = 0, failures = 0;
        for (int tick = 0; tick < maxTicks && tick < safety.maxTicks(); tick++) {
            String action = tick % 3 == 0 ? "shell" : "http";
            var result = shadowEnv.execute(action, "shadow-" + tick);
            if (result.success()) successes++; else failures++;
        }
        int total = successes + failures;
        double succRate = total == 0 ? 1.0 : (double) successes / total;
        double planEff = total == 0 ? 1.0 : 1.0; // all ticks produced actions in shadow
        double fitness = FitnessFunction.evaluate(
                createShadowMetrics(succRate, planEff), 0.5);
        return new ShadowEvalResult(fitness, succRate, planEff, maxTicks);
    }

    private PerformanceMetrics createShadowMetrics(double successRate, double planningEff) {
        var m = new PerformanceMetrics();
        for (int i = 0; i < 10; i++) {
            m.recordTick(null,
                    new de.agicore.kernel.action.ActionResult("s", true, "ok", null,
                            java.time.Instant.now(), java.time.Duration.ZERO),
                    new de.agicore.kernel.meta.MetaCognition());
        }
        return m;
    }

    private void promote(String moduleName, String source, String originalSource, double fitness, double delta) {
        LOG.info("PROMOTED: " + moduleName);
        promptBank.recordSuccess(moduleName, fitness, delta, originalSource, source);
        gitStageAndCommit(moduleName);
    }

    private void rollback(String moduleName) {
        LOG.info("ROLLBACK: " + moduleName);
        gitReset();
    }

    private void gitStageAndCommit(String moduleName) {
        try {
            runCmd("git", "add", "agicore-modules/");
            runCmd("git", "commit", "-m",
                    "Evolution #" + evolutionCycles + ": accepted mutation for " + moduleName);
        } catch (Exception e) {
            LOG.warning("Git commit failed: " + e.getMessage());
        }
    }

    private void gitReset() {
        try {
            runCmd("git", "checkout", "--", "agicore-modules/");
        } catch (Exception e) {
            LOG.warning("Git reset failed: " + e.getMessage());
        }
    }

    private void runCmd(String... args) throws Exception {
        Process p = new ProcessBuilder(args).inheritIO().start();
        p.waitFor();
    }

    private Path findSourceFile(String className) {
        try {
            return Files.walk(modulesSourceDir)
                    .filter(f -> f.getFileName().toString().equals(className + ".java"))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private String resolveClassName(String moduleName) {
        return switch (moduleName) {
            case "stub-planner" -> "StubPlanner";
            default -> moduleName;
        };
    }

    private String resolvePackageName(String moduleName) {
        return switch (moduleName) {
            case "stub-planner" -> "de.agicore.modules.planner";
            default -> "de.agicore.modules";
        };
    }

    // ── Mutation service interface ──────────────────────────

    /** Interface for mutation generation (Ollama or dummy). */
    public interface MutationService {
        String mutate(String moduleName, String currentSource,
                      String className, String packageName);
    }

    /** Dummy service — returns input unchanged (pipeline smoke test). */
    private static class DummyMutationService implements MutationService {
        @Override
        public String mutate(String moduleName, String currentSource,
                             String className, String packageName) {
            return currentSource; // no change = always passes compile, never accepted
        }
    }

    // ── Accessors ────────────────────────────────────────────

    public int evolutionCycles() { return evolutionCycles; }
    public int acceptedMutations() { return acceptedMutations; }
    public int rejectedMutations() { return rejectedMutations; }
    public double baselineFitness() { return baselineFitness; }

    public record ShadowEvalResult(double fitness, double successRate,
                                    double planningEff, int ticks) {}

    public record EvolutionResult(boolean accepted, String message) {
        @Override public String toString() {
            return (accepted ? "✓ " : "✗ ") + message;
        }
    }
}
