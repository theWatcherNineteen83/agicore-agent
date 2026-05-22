package de.agicore.modules;

import de.agicore.modules.evolution.OllamaMutationService;

import java.net.URI;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Demonstrates the self-evolving agent with Ollama-powered mutation.
 */
public final class DemoMain {
    static {
        Logger.getLogger("").setLevel(Level.WARNING);
        Logger.getLogger("de.agicore").setLevel(Level.INFO);
    }

    public static void main(String[] args) {
        System.out.println("""
                
                ╔══════════════════════════════════════════════╗
                ║   AGI Core — Ollama-Powered Evolution       ║
                ║   Kernel (immutable) + Modules (evolvable)  ║
                ╚══════════════════════════════════════════════╝
                """);

        // Ollama mutation service (miniedi:11434)
        var ollama = new OllamaMutationService();

        Agent agent = Agent.builder()
                .registerShellCommand(List.of("uname", "-a"))
                .registerHttpGet(URI.create("https://httpbin.org/get"))
                .workspaceCapacity(5)
                .build();

        // Inject Ollama into EvolutionManager
        agent.core().evolutionManager().setMutationService(ollama);

        agent.worldModel().update("shell actions execute reliably", 0.95, "bootstrap", true);
        agent.addGoal("Check system status", "shell", 85, 0.9, 1);
        agent.addGoal("HTTP health check", "http", 70, 0.8, 2);
        agent.addGoal("Analyze unknown data", "analysis", 60, 0.5, 3);

        System.out.println("Ollama endpoint: 192.168.22.204:11434");
        System.out.println("Mutation service: OllamaMutationService (qwen3.6:27b)");
        System.out.println("Evolution trigger: fitness stagnation ≥ 200 ticks\n");

        agent.run(50);

        System.out.println("\n═══ Results ═══");
        var evo = agent.core().evolutionManager();
        System.out.printf("Ticks: %d | Success: %.0f%% | PlanEff: %.0f%%\n",
                agent.metrics().totalTicks(),
                agent.metrics().goalSuccessRate() * 100,
                agent.metrics().planningEfficiency() * 100);
        System.out.printf("Evolution cycles: %d | Accepted: %d | Rejected: %d\n",
                evo.evolutionCycles(), evo.acceptedMutations(), evo.rejectedMutations());
        System.out.printf("Ollama mutations: %d\n", ollama.mutationCount());

        System.out.println("\n✅ Ollama-powered evolution demo complete.");
    }
}
