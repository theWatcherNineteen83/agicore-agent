package de.metis.modules;

import de.metis.modules.evolution.OllamaMutationService;
import de.metis.modules.planner.OllamaPlanner;

import java.net.URI;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Demonstrates the self-evolving agent with:
 * <ul>
 *   <li>OllamaPlanner — LLM-powered planning (Step D)</li>
 *   <li>OllamaMutationService — LLM-powered self-modification (Step C)</li>
 * </ul>
 */
public final class DemoMain {
    static {
        Logger.getLogger("").setLevel(Level.WARNING);
        Logger.getLogger("de.metis").setLevel(Level.INFO);
    }

    public static void main(String[] args) {
        System.out.println("""
                
                ╔══════════════════════════════════════════════╗
                ║   AGI Core — LLM-Powered Self-Evolution   ║
                ║   Planner: Ollama (reasoning)              ║
                ║   Mutation: Ollama (code generation)        ║
                ║   Kernel (immutable) + Modules (evolvable)  ║
                ╚══════════════════════════════════════════════╝
                """);

        // Ollama mutation service for self-modification
        var ollamaMutator = new OllamaMutationService();

        Agent agent = Agent.builder()
                .registerShellCommand(List.of("uname", "-a"))
                .registerHttpGet(URI.create("https://httpbin.org/get"))
                .workspaceCapacity(5)
                .build();

        // Inject mutation service into EvolutionManager
        agent.core().evolutionManager().setMutationService(ollamaMutator);

        // Bootstrap world model
        agent.worldModel().update("shell actions execute reliably", 0.95, "bootstrap", true);
        agent.worldModel().update("http actions work for health checks", 0.9, "bootstrap", true);

        // Goals
        agent.addGoal("Check system status via shell", "shell", 85, 0.9, 1);
        agent.addGoal("HTTP health check request", "http", 70, 0.8, 2);
        agent.addGoal("Analyze unknown data", "analysis", 60, 0.5, 3);

        System.out.println("Planner:  OllamaPlanner (mistral-small3.1:24b @ miniedi:11434)");
        System.out.println("Mutator:  OllamaMutationService (mistral-small3.1:24b)");
        System.out.println("Fallback: Learned mapping → Keyword heuristic");
        System.out.println("Evolution trigger: fitness stagnation ≥ 200 ticks\n");

        agent.run(30);

        System.out.println("\n═══ Results ═══");
        var planner = agent.core().planner();
        var evo = agent.core().evolutionManager();

        System.out.printf("Ticks: %d | Success: %.0f%% | PlanEff: %.0f%%\n",
                agent.metrics().totalTicks(),
                agent.metrics().goalSuccessRate() * 100,
                agent.metrics().planningEfficiency() * 100);

        if (planner instanceof OllamaPlanner op) {
            System.out.printf("Planner: LLM calls=%d (%.0f%% success) | Fallbacks=%d\n",
                    op.llmCalls(), op.llmSuccessRate() * 100, op.fallbackUses());
            System.out.println("Learned mappings: " + op.learnedSuccessRates());
        }

        System.out.printf("Evolution cycles: %d | Accepted: %d | Rejected: %d\n",
                evo.evolutionCycles(), evo.acceptedMutations(), evo.rejectedMutations());

        System.out.println("\n✅ Step D demo complete — LLM planner integrated.");
    }
}
