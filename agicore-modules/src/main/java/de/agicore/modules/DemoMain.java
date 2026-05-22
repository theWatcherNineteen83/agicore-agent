package de.agicore.modules;

import java.net.URI;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Demonstrates the self-evolving agent architecture.
 * <p>
 * Kernel + Modules + EvolutionManager + ShadowEvaluation.
 */
public final class DemoMain {
    static {
        Logger.getLogger("").setLevel(Level.WARNING);
        Logger.getLogger("de.agicore").setLevel(Level.INFO);
    }

    public static void main(String[] args) {
        System.out.println("""
                
                ╔══════════════════════════════════════════════╗
                ║   AGI Core — Self-Evolving Agent            ║
                ║   Kernel (immutable) + Modules (evolvable)  ║
                ╚══════════════════════════════════════════════╝
                """);

        Agent agent = Agent.builder()
                .registerShellCommand(List.of("uname", "-a"))
                .registerHttpGet(URI.create("https://httpbin.org/get"))
                .workspaceCapacity(5)
                .build();

        agent.worldModel().update("shell actions execute reliably", 0.95, "bootstrap", true);
        agent.addGoal("Check system status", "shell", 85, 0.9, 1);
        agent.addGoal("HTTP health check", "http", 70, 0.8, 2);
        agent.addGoal("Analyze unknown data", "analysis", 60, 0.5, 3);

        // Run enough ticks to trigger evolution check (100 ticks)
        agent.run(120);

        // Results
        System.out.println("\n═══ Results ═══");
        System.out.printf("Ticks: %d | Success: %.0f%% | PlanEff: %.0f%%\n",
                agent.metrics().totalTicks(),
                agent.metrics().goalSuccessRate() * 100,
                agent.metrics().planningEfficiency() * 100);

        var evo = agent.core().evolutionManager();
        System.out.printf("Evolution cycles: %d | Accepted: %d | Rejected: %d\n",
                evo.evolutionCycles(), evo.acceptedMutations(), evo.rejectedMutations());

        System.out.printf("Kernel fitness baseline: %.3f\n", evo.baselineFitness());

        System.out.println("\n═══ Architecture ═══");
        System.out.println("""
                ┌────────────────────────────┐
                │  agicore-kernel (immutable) │
                │  - CoreLoop, Fitness,       │
                │    EvolutionManager, Safety │
                │  - Interfaces only          │
                └──────────┬─────────────────┘
                           │ depends on
                           ▼
                ┌────────────────────────────┐
                │  agicore-modules (evolvable)│
                │  - StubPlanner v1.0.0       │
                │  - LLM-mutierbar (Phase 2)  │
                └────────────────────────────┘
                """);

        System.out.println("✅ Modular self-evolving agent demo complete.");
    }
}
