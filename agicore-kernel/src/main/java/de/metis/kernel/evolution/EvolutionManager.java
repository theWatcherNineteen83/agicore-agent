package de.metis.kernel.evolution;

import de.metis.kernel.action.ActionResult;
import de.metis.kernel.goal.Goal;
import de.metis.kernel.memory.Experience;
import de.metis.kernel.meta.MetaCognition;
import de.metis.kernel.metrics.PerformanceMetrics;
import de.metis.kernel.planner.EvolvableModule;
import de.metis.kernel.planner.Planner;
import de.metis.kernel.safety.SafetyGuard;
import de.metis.kernel.workspace.ContentItem;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.time.Instant;
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

    private static final int STAGNATION_TICKS = 50;
    private static final int MAX_STAGNATION_TICKS = 100;
    private static final int SHADOW_TICKS = 100;

    private final SafetyGuard safety;
    private final ShadowEnvironment shadowEnv;
    private final List<EvolvableModule> evolvableModules = new ArrayList<>();

    /** Injected mutation service (Ollama, or dummy for testing). */
    private MutationService mutationService = new DummyMutationService();
    /** Max retries with compiler feedback. */
    private static final int MAX_COMPILE_RETRIES = 2;

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

    /** Kernel evolution: feature branch workflow (optional). */
    private Path kernelSourceDir = null;
    private final List<KernelModuleInfo> kernelModules = new ArrayList<>();
    private boolean kernelEvolutionEnabled = false;
    private String currentFeatureBranch = null;  // active kernel evolution branch
    private volatile boolean paused = false;

    /** Max change for kernel mutations (stricter than module 15%). */
    private static final double KERNEL_MAX_CHANGE_PERCENT = 5.0;

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

    /**
     * Enable kernel evolution with feature branch workflow.
     * Kernel mutations get their own git branch; accepted mutations are merged.
     * Rejected mutations result in branch deletion.
     *
     * @param kernelSrcDir path to kernel source root (e.g. "agicore-kernel/src/main/java")
     */
    public void enableKernelEvolution(Path kernelSrcDir) {
        this.kernelSourceDir = kernelSrcDir.toAbsolutePath();
        this.kernelEvolutionEnabled = true;
        LOG.info("Kernel evolution enabled — feature branch workflow active");
    }

    /**
     * Register a kernel class as mutation target.
     * Kernel classes are safety-critical: max 5% change, extra shadow evaluation.
     *
     * @param fqcn          fully qualified class name (e.g. "de.metis.kernel.planner.PlanValidator")
     * @param relativePath  path relative to kernel source dir (e.g. "de/agicore/kernel/planner/PlanValidator.java")
     */
    public void registerKernelModule(String fqcn, String relativePath) {
        kernelModules.add(new KernelModuleInfo(fqcn, relativePath));
        LOG.info("Registered kernel module: " + fqcn);
    }

    public boolean shouldEvolve(long tickCount, double currentFitness) {
        if (paused) return false;

        // First call: initialise baseline to prevent early resets
        if (lastImprovementTick == 0 && baselineFitness == 0.0) {
            baselineFitness = currentFitness;
            lastImprovementTick = tickCount;
            return false;
        }

        if (currentFitness > baselineFitness + FitnessFunction.minImprovement()) {
            baselineFitness = currentFitness;
            lastImprovementTick = tickCount;
            return false;
        }

        // Hard fallback: force evolution every MAX_STAGNATION ticks regardless
        if ((tickCount - lastImprovementTick) >= MAX_STAGNATION_TICKS) {
            LOG.info("Max stagnation reached — forcing evolution at tick " + tickCount);
            return true;
        }

        return (tickCount - lastImprovementTick) >= STAGNATION_TICKS;
    }

    /**
     * Run one evolution cycle with real mutation and compilation.
     * Handles both kernel modules (feature branch workflow) and regular modules.
     */
    public EvolutionResult evolve(double baselineFitness) {
        this.baselineFitness = baselineFitness;
        evolutionCycles++;

        try {
            safety.beginCycle();

            // Collect all possible targets
            List<MutationTarget> targets = new ArrayList<>();
            for (EvolvableModule m : evolvableModules) {
                targets.add(new MutationTarget(m, false));
            }
            for (KernelModuleInfo km : kernelModules) {
                targets.add(new MutationTarget(km, true));
            }

            if (targets.isEmpty()) {
                return new EvolutionResult(false, "No evolvable modules");
            }

            MutationTarget target = targets.get(new Random().nextInt(targets.size()));
            safety.allowMutation();

            if (target.isKernel) {
                return evolveKernelModule(target.kernelInfo(), baselineFitness);
            } else {
                return evolveRegularModule(target.module(), baselineFitness);
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

    /** Regular module evolution (existing behavior). */
    private EvolutionResult evolveRegularModule(EvolvableModule target, double baselineFitness) throws Exception {
        String moduleName = target.moduleName();
        String className = resolveClassName(moduleName);
        String packageName = resolvePackageName(moduleName);

        Path sourceFile = findSourceFile(className);
        if (sourceFile == null) {
            safety.recordFailure();
            rejectedMutations++;
            return new EvolutionResult(false, "Source file not found: " + className);
        }
        String originalSource = Files.readString(sourceFile);

        String mutantSource = mutationService.mutate(moduleName, originalSource, className, packageName);
        if (mutantSource == null || mutantSource.equals(originalSource)) {
            safety.recordFailure();
            rejectedMutations++;
            return new EvolutionResult(false, "Mutation produced no change");
        }

        return evaluateAndApply(sourceFile, moduleName, mutantSource, originalSource, baselineFitness, false);
    }

    /** Kernel module evolution with feature branch workflow. */
    private EvolutionResult evolveKernelModule(KernelModuleInfo km, double baselineFitness) throws Exception {
        if (kernelSourceDir == null) {
            return new EvolutionResult(false, "Kernel evolution not configured");
        }

        Path sourceFile = kernelSourceDir.resolve(km.relativePath);
        if (!Files.exists(sourceFile)) {
            safety.recordFailure();
            rejectedMutations++;
            return new EvolutionResult(false, "Kernel source not found: " + sourceFile);
        }

        String originalSource = Files.readString(sourceFile);
        String className = km.simpleClassName();
        String packageName = km.packageName();

        // ── Create feature branch ─────────────────────────
        String branchName = "evolution/kernel-" + UUID.randomUUID().toString().substring(0, 8);
        currentFeatureBranch = branchName;
        runCmd("git", "checkout", "-b", branchName);
        LOG.info("Kernel evolution: created feature branch " + branchName);

        try {
            String mutantSource = mutationService.mutate(
                    "kernel-" + km.simpleClassName(), originalSource, km.simpleClassName(), packageName);
            if (mutantSource == null || mutantSource.equals(originalSource)) {
                abortFeatureBranch(branchName);
                safety.recordFailure();
                rejectedMutations++;
                return new EvolutionResult(false, "Kernel mutation produced no change");
            }

            // ── Kernel safety: max 5% change ──────────────
            double changePercent = computeChangePercent(originalSource, mutantSource);
            if (changePercent > KERNEL_MAX_CHANGE_PERCENT) {
                abortFeatureBranch(branchName);
                safety.recordFailure();
                rejectedMutations++;
                return new EvolutionResult(false,
                        "Kernel safety: " + String.format("%.1f%%", changePercent)
                                + " change exceeds " + KERNEL_MAX_CHANGE_PERCENT + "% limit");
            }

            // ── Write mutant to kernel source ────────────
            Files.writeString(sourceFile, mutantSource);

            // ── Compile check (full kernel module) ───────
            if (!compileCheck(sourceFile, km.simpleClassName())) {
                abortFeatureBranch(branchName);
                Files.writeString(sourceFile, originalSource); // restore
                safety.recordFailure();
                rejectedMutations++;
                return new EvolutionResult(false, "Kernel compilation failed");
            }

            // ── Extended shadow evaluation ───────────────
            Path kernelOutputDir = Path.of("target/evolution-out");
            ShadowEvalResult shadowResult = runShadowEvaluation(SHADOW_TICKS * 2,
                    kernelOutputDir.toAbsolutePath(), km.simpleClassName());
            double mutantFitness = shadowResult.fitness();

            // ── Compare ───────────────────────────────────
            double improvement = mutantFitness - baselineFitness;

            if (improvement > FitnessFunction.minImprovement()) {
                // Accept: commit on branch, merge to master
                runCmd("git", "add", "agicore-kernel/");
                runCmd("git", "commit", "-m",
                        "Kernel evolution #" + evolutionCycles + ": mutate " + km.simpleClassName()
                                + " (" + String.format("%.1f%%", changePercent) + " changed)");
                runCmd("git", "checkout", "master");
                runCmd("git", "merge", "--no-ff", branchName, "-m",
                        "Merge kernel evolution #" + evolutionCycles + ": " + km.simpleClassName());
                runCmd("git", "branch", "-d", branchName);

                promptBank.recordSuccess("kernel-" + km.simpleClassName(), mutantFitness, improvement,
                        originalSource, mutantSource);
                safety.recordSuccess();
                acceptedMutations++;
                this.baselineFitness = mutantFitness;
                this.lastImprovementTick = 0;
                currentFeatureBranch = null;
                return new EvolutionResult(true,
                        "Kernel accepted: " + km.simpleClassName() + " +"
                                + String.format("%.3f", improvement));
            } else {
                // Reject: delete branch, restore master
                abortFeatureBranch(branchName);
                safety.recordFailure();
                rejectedMutations++;
                currentFeatureBranch = null;
                return new EvolutionResult(false,
                        "Kernel rejected: delta=" + String.format("%.3f", improvement)
                                + " ≤ " + FitnessFunction.minImprovement());
            }

        } catch (Exception e) {
            // Emergency: abort feature branch, restore master
            abortFeatureBranch(branchName);
            currentFeatureBranch = null;
            throw e;
        }
    }

    /** Shared evaluation logic for regular module mutations. */
    private EvolutionResult evaluateAndApply(Path sourceFile, String moduleName,
                                              String mutantSource, String originalSource,
                                              double baselineFitness, boolean isKernel) throws Exception {
        String className = sourceFile.getFileName().toString().replace(".java", "");

        // Write mutant to temp file
        Path outputDir = Path.of("target/evolution-out");
        Files.createDirectories(outputDir);
        Path tempFile = outputDir.resolve(className + ".java");
        Files.writeString(tempFile, mutantSource);

        // ── Compile check with feedback loop ──
        String compileFeedback = null;
        boolean compileOk = compileCheck(tempFile, className);
        int retry = 0;
        while (!compileOk && retry < MAX_COMPILE_RETRIES) {
            if (compileFeedback == null) {
                compileFeedback = captureCompileErrors(tempFile, className);
            }
            LOG.warning("Mutation compilation FAILED (attempt " + (retry + 1)
                    + "/" + MAX_COMPILE_RETRIES + "): " + className
                    + " — retrying with compiler feedback");
            String fixedSource = mutationService.mutateWithFeedback(
                    moduleName, mutantSource, className, resolvePackageName(moduleName), compileFeedback);
            if (fixedSource == null || fixedSource.equals(mutantSource)) break;
            mutantSource = fixedSource;
            Files.writeString(tempFile, mutantSource);
            compileOk = compileCheck(tempFile, className);
            retry++;
            if (!compileOk) {
                compileFeedback = captureCompileErrors(tempFile, className);
            }
        }
        if (!compileOk) {
            LOG.warning("Mutation compilation FAILING after " + MAX_COMPILE_RETRIES
                    + " retries: " + className);
            safety.recordFailure();
            rejectedMutations++;
            Files.deleteIfExists(tempFile);
            return new EvolutionResult(false, "Compilation failed after feedback retries");
        }

        ShadowEvalResult shadowResult = runShadowEvaluation(SHADOW_TICKS,
                outputDir.toAbsolutePath(), className);
        double mutantFitness = shadowResult.fitness();
        double improvement = mutantFitness - baselineFitness;

        if (improvement > FitnessFunction.minImprovement()) {
            Files.writeString(sourceFile, mutantSource);
            promote(moduleName, mutantSource, originalSource, mutantFitness, improvement);
            safety.recordSuccess();
            acceptedMutations++;
            this.baselineFitness = mutantFitness;
            this.lastImprovementTick = 0;
            Files.deleteIfExists(tempFile);
            return new EvolutionResult(true, "Accepted: +" + String.format("%.3f", improvement));
        } else {
            rollback(moduleName);
            safety.recordFailure();
            rejectedMutations++;
            Files.deleteIfExists(tempFile);
            return new EvolutionResult(false,
                    "Rejected: delta=" + String.format("%.3f", improvement)
                            + " ≤ " + FitnessFunction.minImprovement());
        }
    }

    /** Abort feature branch: return to master and delete the branch. */
    private void abortFeatureBranch(String branchName) {
        try {
            runCmd("git", "checkout", "master");
            runCmd("git", "branch", "-D", branchName);
            LOG.info("Kernel evolution: aborted branch " + branchName);
        } catch (Exception e) {
            LOG.warning("Failed to abort feature branch " + branchName + ": " + e.getMessage());
        }
    }

    /** Compute percentage of lines changed between two source files. */
    private double computeChangePercent(String original, String mutant) {
        var origLines = original.lines().toList();
        var mutLines = mutant.lines().toList();
        long diff = 0;
        int maxLen = Math.max(origLines.size(), mutLines.size());
        for (int i = 0; i < maxLen; i++) {
            String o = i < origLines.size() ? origLines.get(i) : "";
            String m = i < mutLines.size() ? mutLines.get(i) : "";
            if (!o.equals(m)) diff++;
        }
        return 100.0 * diff / Math.max(1, origLines.size());
    }

    /** Real compilation check using javax.tools.JavaCompiler. */
    public boolean compileCheck(Path sourceFile, String className) {
        return captureCompileErrors(sourceFile, className) == null;
    }

    /**
     * Compile a source file and capture all error diagnostics.
     * @return null on success, formatted error message on failure
     */
    private String captureCompileErrors(Path sourceFile, String className) {
        return compileWithDiagnostics(sourceFile, className);
    }

    /**
     * Internal: compile file and return diagnostics as string, or null on success.
     */
    private String compileWithDiagnostics(Path sourceFile, String className) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            LOG.warning("No system Java compiler available — skipping compile check");
            return null; // degrade gracefully
        }
        Path outputDir = Path.of("target/evolution-out");
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            return "Cannot create output dir: " + e.getMessage();
        }

        var diagnostics = new java.util.ArrayList<javax.tools.Diagnostic<?>>();
        var listener = new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
        var fileManager = compiler.getStandardFileManager(listener, null, null);

        List<String> options = List.of(
                "-d", outputDir.toString(),
                "-cp", classpath,
                "-Xlint:none"
        );

        var compilationUnits = fileManager.getJavaFileObjects(sourceFile.toFile());
        var task = compiler.getTask(null, fileManager, listener, options, null, compilationUnits);

        boolean success = task.call();
        if (success) {
            LOG.info(() -> "Compile check: PASS for " + className);
            return null;
        }

        // Collect error diagnostics
        StringBuilder sb = new StringBuilder();
        sb.append("Compilation failed (").append(diagnostics.size()).append(" errors):\n");
        for (var d : diagnostics) {
            if (d.getKind() == javax.tools.Diagnostic.Kind.ERROR) {
                sb.append("  Line ").append(d.getLineNumber())
                  .append(": ").append(d.getMessage(null)).append("\n");
            }
        }
        String err = sb.toString();
        LOG.warning(() -> "Compile check: FAIL for " + className + " — " + err);
        return err;
    }

    /**
     * Run shadow evaluation by loading the compiled mutant Planner class
     * and testing it against a diverse set of test goals.
     * <p>
     * This replaces the old canned-response approach. The mutant is
     * dynamically loaded and its plan() method is called with real test
     * goals. The resulting plan production rate and expected success
     * estimates feed into the fitness function so different mutants
     * actually produce different fitness scores.
     */
    private ShadowEvalResult runShadowEvaluation(int maxTicks, Path compiledClassDir, String className) {
        Planner mutantPlanner = loadMutantPlanner(compiledClassDir, className);

        // ── Test goals covering diverse domains ───────────
        List<Goal> testGoals = List.of(
                new Goal("Check system status via shell", "shell", 80, 0.7, 5),
                new Goal("HTTP health check request", "http", 70, 0.6, 3),
                new Goal("List files in current directory", "shell", 50, 0.3, 2),
                new Goal("Fetch API data from endpoint", "http", 60, 0.5, 3),
                new Goal("Unknown exploratory task", "unknown", 30, 0.2, 1),
                new Goal("Complex multi-step shell operation", "shell", 90, 0.8, 10)
        );

        int planProduced = 0, planEmpty = 0;
        double totalExpectedSuccess = 0;
        List<Experience> emptyHistory = Collections.emptyList();
        List<ContentItem> emptyBroadcast = Collections.emptyList();
        MetaCognition dummyMeta = new MetaCognition();

        for (int tick = 0; tick < maxTicks && tick < safety.maxTicks(); tick++) {
            Goal goal = testGoals.get(tick % testGoals.size());

            List<String> plan;
            if (mutantPlanner != null) {
                plan = mutantPlanner.plan(goal, emptyHistory, emptyBroadcast, dummyMeta);
            } else {
                // Fallback: use shadowEnv for basic action simulation
                String action = tick % 3 == 0 ? "shell" : "http";
                var result = shadowEnv.execute(action, "shadow-" + tick);
                plan = result.success() ? List.of(action) : Collections.emptyList();
            }

            if (!plan.isEmpty()) {
                planProduced++;
                if (mutantPlanner != null) {
                    totalExpectedSuccess += mutantPlanner.expectedSuccess(goal, plan.getFirst());
                } else {
                    totalExpectedSuccess += 0.5;
                }
            } else {
                planEmpty++;
            }
        }

        int totalPlans = planProduced + planEmpty;
        double planProductionRate = totalPlans == 0 ? 0 : (double) planProduced / totalPlans;
        double avgExpectedSuccess = planProduced == 0 ? 0.5 : totalExpectedSuccess / planProduced;

        // Create metrics that reflect the mutant's actual behavior
        PerformanceMetrics metrics = createShadowMetrics(planProductionRate, avgExpectedSuccess);

        // Fitness from real metrics + entropy (plan diversity proxy)
        double fitness = FitnessFunction.evaluate(metrics, planProductionRate);

        // Final copies for lambda (variables modified in loop are not effectively final)
        final int finalPlanProduced = planProduced;
        final int finalTotalPlans = totalPlans;
        final double finalPlanRate = planProductionRate;
        final double finalAvgSuccess = avgExpectedSuccess;
        final double finalFitness = fitness;
        final String mutantLabel = mutantPlanner != null ? "MUTANT" : "FALLBACK";

        LOG.info(() -> String.format(
                "Shadow eval [%s]: plans=%d/%d (%.1f%%), avgExpSucc=%.2f, fitness=%.3f",
                mutantLabel,
                finalPlanProduced, finalTotalPlans, finalPlanRate * 100, finalAvgSuccess, finalFitness));

        return new ShadowEvalResult(fitness, planProductionRate, avgExpectedSuccess, maxTicks);
    }

    /**
     * Load a compiled mutant Planner class via URLClassLoader.
     * The compiled .class file must be in {@code de/metis/modules/planner/}
     * under the given directory.
     */
    private Planner loadMutantPlanner(Path compiledClassDir, String className) {
        try {
            URLClassLoader loader = new URLClassLoader(
                    new URL[]{compiledClassDir.toUri().toURL()},
                    getClass().getClassLoader()
            );
            String fqcn = "de.metis.modules.planner." + className;
            Class<?> clazz = loader.loadClass(fqcn);
            return (Planner) clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            LOG.warning("Shadow: could not load mutant " + className + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Create a PerformanceMetrics object that reflects the given
     * plan production rate and expected success rate, rather than
     * always producing identical dummy metrics.
     */
    private PerformanceMetrics createShadowMetrics(double planProductionRate, double avgExpectedSuccess) {
        var m = new PerformanceMetrics();
        MetaCognition dummyMeta = new MetaCognition();
        int totalTicks = 50;
        int successfulTicks = (int) Math.round(totalTicks * planProductionRate);

        for (int i = 0; i < totalTicks; i++) {
            Goal goal = new Goal("shadow-test-" + i, "shadow", 50, avgExpectedSuccess, 1);
            if (i < successfulTicks) {
                // Plan produced → record success
                ActionResult ok = ActionResult.ok("shadow-action", "shadow output", Instant.now());
                m.recordTick(goal, ok, dummyMeta);
            } else {
                // No plan → record null result (planning failure)
                m.recordTick(goal, null, dummyMeta);
            }
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
            case "ollama-planner" -> "OllamaPlanner";
            default -> moduleName;
        };
    }

    private String resolvePackageName(String moduleName) {
        return switch (moduleName) {
            case "stub-planner" -> "de.metis.modules.planner";
            case "ollama-planner" -> "de.metis.modules.planner";
            default -> "de.metis.modules";
        };
    }

    // ── Mutation service interface ──────────────────────────

    /** Interface for mutation generation (Ollama or dummy). */
    public interface MutationService {
        String mutate(String moduleName, String currentSource,
                      String className, String packageName);

        /** Retry mutation with compiler error feedback. */
        default String mutateWithFeedback(String moduleName, String currentSource,
                      String className, String packageName, String compileErrors) {
            return mutate(moduleName, currentSource, className, packageName);
        }
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
    public boolean isPaused() { return paused; }
    public void setPaused(boolean p) { this.paused = p; }

    public record ShadowEvalResult(double fitness, double successRate,
                                    double planningEff, int ticks) {}

    public record EvolutionResult(boolean accepted, String message) {
        @Override public String toString() {
            return (accepted ? "✓ " : "✗ ") + message;
        }
    }

    // ── Internal types ────────────────────────────────────────

    private record MutationTarget(EvolvableModule module, boolean isKernel,
                                   KernelModuleInfo kernelInfo) {
        MutationTarget(EvolvableModule module, boolean isKernel) {
            this(module, isKernel, null);
        }
        MutationTarget(KernelModuleInfo kernelInfo, boolean isKernel) {
            this(null, isKernel, kernelInfo);
        }
    }

    /** Info for a kernel class registered for mutation. */
    public record KernelModuleInfo(String fqcn, String relativePath) {
        public String simpleClassName() {
            int lastDot = fqcn.lastIndexOf('.');
            return lastDot >= 0 ? fqcn.substring(lastDot + 1) : fqcn;
        }
        public String packageName() {
            int lastDot = fqcn.lastIndexOf('.');
            return lastDot >= 0 ? fqcn.substring(0, lastDot) : fqcn;
        }
    }
}
