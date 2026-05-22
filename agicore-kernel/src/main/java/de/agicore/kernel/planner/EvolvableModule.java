package de.agicore.kernel.planner;

/**
 * Marks a module as evolvable by the EvolutionManager.
 * <p>
 * The kernel never mutates. Evolvable modules implement this interface
 * so the EvolutionManager can discover, version, and mutate them.
 * <p>
 * Mutation pipeline:
 * <ol>
 *   <li>LLM generates a variant of the module source</li>
 *   <li>Shadow agent evaluates the variant</li>
 *   <li>FitnessFunction compares variant vs baseline</li>
 *   <li>Accept (git merge) or Reject (git reset)</li>
 * </ol>
 */
public interface EvolvableModule {

    /** Human-readable module identifier (e.g. "planner", "goal-resolver"). */
    String moduleName();

    /** Semantic version string (e.g. "1.0.0"). */
    String version();

    /** Last measured fitness score (0.0–1.0). Updated by EvolutionManager. */
    double lastFitness();
}
