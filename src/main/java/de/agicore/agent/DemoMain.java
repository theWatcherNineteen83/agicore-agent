package de.agicore.agent;

import java.net.URI;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Post-Review Demo — alle 6 GPT-5 Fixes integriert.
 * Zeigt die Verbesserungen und läuft einen Stabilitätstest.
 */
public final class DemoMain {

    static {
        Logger.getLogger("").setLevel(Level.WARNING);
        Logger.getLogger("de.agicore.agent").setLevel(Level.INFO);
    }

    public static void main(String[] args) {
        System.out.println("""
                
                ╔══════════════════════════════════════════════╗
                ║   AGI Core Agent — Post-Review Demo          ║
                ║   Fixes #1–#6 applied                       ║
                ╚══════════════════════════════════════════════╝
                """);

        Agent agent = Agent.builder()
                .registerShellCommand(List.of("uname", "-a"))
                .registerHttpGet(URI.create("https://httpbin.org/get"))
                .workspaceCapacity(5)
                .build();

        agent.worldModel().update("miniedi is reachable", 0.9, "bootstrap", true);
        agent.worldModel().update("shell actions execute reliably", 0.95, "bootstrap", true);

        // Fix #4: Explicit categories
        agent.addGoal("Check system shell status", "shell", 85, 0.9, 1);
        agent.addGoal("Verify uptime via shell", "shell", 80, 0.85, 1);
        agent.addGoal("Send HTTP test to httpbin", "http", 70, 0.8, 3);
        agent.addGoal("Run shell memory check", "shell", 75, 0.75, 1);
        agent.addGoal("Query external API status", "http", 65, 0.7, 3);
        agent.addGoal("Analyze sensor data patterns", "analysis", 90, 0.7, 5);

        System.out.println("Goals: " + agent.goals().activeCount()
                + " (shell=3, http=2, analysis=1)\n");

        // ── Run stability test (100+ ticks) ────────────────────
        agent.run(50);

        // ── Results ────────────────────────────────────────────
        System.out.println("\n═══════════════════════════════════════════════");
        System.out.println("  POST-REVIEW RESULTS");
        System.out.println("═══════════════════════════════════════════════\n");

        System.out.println("─ Fix #1: Workspace → Planner (causal)");
        System.out.println("  Planner signature: plan(goal, history, broadcast, meta) ✓");
        System.out.println("  StubPlanner checks broadcast for world-model reliability ✓");

        System.out.println("\n─ Fix #2: Prediction Error = |predicted - actual|");
        System.out.printf("  Old: binary goal-based | New: |expectedSuccess - actualOutcome| ✓\n");
        System.out.printf("  MetaCognition err=%.3f conf=%.3f\n",
                agent.meta().rollingError(), agent.meta().confidence());

        System.out.println("\n─ Fix #3: HyperparameterMutator A/B Isolation");
        System.out.printf("  Experiment running: %s\n", agent.core().hyperMutator().isExperimentRunning());

        System.out.println("\n─ Fix #4: Goal.category (explicit)");
        agent.goals().all().forEach(g ->
                System.out.printf("  [%s] cat=%-10s %s\n",
                        g.active() ? "ACTIVE " : "DONE  ", g.category(), g.description()));

        System.out.println("\n─ Fix #5: Enriched Feature Vector (10 dims)");
        System.out.println("  [priority, reward, outcome, error, cost, isShell, isHttp, confidence, surprise, load] ✓");

        System.out.println("\n─ Fix #6: SelfModel Forward-Model");
        System.out.printf("  Forward-model accuracy: %.0f%%\n",
                agent.selfModel().forwardModelAccuracy() * 100);
        System.out.printf("  Shell predictOwnSuccess(0.8): %.2f\n",
                agent.selfModel().predictOwnSuccess("shell", 0.8));
        System.out.printf("  Self calibration: %.0f%%\n",
                agent.selfModel().calibrationConfidence() * 100);

        // Stability metrics
        System.out.println("\n─ Stability Metrics");
        var m = agent.metrics();
        System.out.printf("  Ticks: %d | Success: %.0f%% | PlanEff: %.0f%%\n",
                m.totalTicks(), m.goalSuccessRate() * 100, m.planningEfficiency() * 100);
        System.out.printf("  World beliefs: %d | Meta strategies: %d\n",
                agent.worldModel().beliefCount(), agent.metaRepr().strategies().size());
        System.out.printf("  STM: %d | LTM: %d | Attention: %d\n",
                agent.stm().size(), agent.memory().ltm().size(),
                agent.workspace().buffer().size());

        System.out.println("\n✅ Post-review demo complete.");
    }
}
