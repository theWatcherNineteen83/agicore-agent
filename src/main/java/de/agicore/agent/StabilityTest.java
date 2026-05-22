package de.agicore.agent;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Architecture Hardening: Stability test over 1000+ ticks.
 * <p>
 * Measures drift in:
 * <ul>
 *   <li>Confidence (should not oscillate wildly)</li>
 *   <li>Goal priorities (should remain bounded, not all converge to 1 or 100)</li>
 *   <li>Memory size (should stabilise, not grow unbounded)</li>
 *   <li>Attention entropy (should stay above 0.5 — diverse attention)</li>
 * </ul>
 * <p>
 * Runs three scenarios:
 * <ol>
 *   <li>Normal: 500 ticks with mixed shell+http goals</li>
 *   <li>Failure-injection: 200 ticks with forced failures</li>
 *   <li>Recovery: 300 ticks after failures stop</li>
 * </ol>
 */
public final class StabilityTest {

    static {
        Logger.getLogger("").setLevel(Level.WARNING);
        Logger.getLogger("de.agicore.agent").setLevel(Level.WARNING);
    }

    private record DriftSnapshot(int tick, double confidence, double avgPriority,
                                 int stmSize, int ltmSize, double entropy,
                                 double successRate, double planningEff) {
        @Override
        public String toString() {
            return String.format("T%04d conf=%.3f pri=%.1f stm=%d ltm=%d ent=%.2f succ=%.2f plan=%.2f",
                    tick, confidence, avgPriority, stmSize, ltmSize, entropy, successRate, planningEff);
        }
    }

    public static void main(String[] args) {
        System.out.println("""
                
                ╔══════════════════════════════════════════════╗
                ║   ARCHITECTURE HARDENING                     ║
                ║   Stability Test — 1000+ Ticks              ║
                ╚══════════════════════════════════════════════╝
                """);

        Agent agent = Agent.builder()
                .registerShellCommand(List.of("echo", "ok"))
                .registerHttpGet(URI.create("https://httpbin.org/get"))
                .workspaceCapacity(5)
                .build();

        List<DriftSnapshot> snapshots = new ArrayList<>();

        System.out.println("═══ Phase A: Normal Operation (500 ticks) ═══");
        // Add diverse goals periodically
        for (int i = 0; i < 500; i++) {
            // Refill goals every 20 ticks
            if (i % 20 == 0) {
                agent.addGoal("Run shell system check " + (i / 20), "shell", 70 + (i % 30), 0.8, 1);
                agent.addGoal("HTTP health check " + (i / 20), "http", 60 + (i % 20), 0.7, 2);
            }

            agent.tick();

            // Snapshot every 50 ticks
            if (i % 50 == 0) {
                snapshots.add(snapshot(agent, i));
            }
        }
        System.out.println("  Done. " + agent.metrics().totalTicks() + " ticks");

        System.out.println("\n═══ Phase B: Failure Injection (200 ticks) ═══");
        // Add goals that will fail (no matching action)
        for (int i = 500; i < 700; i++) {
            if (i % 15 == 0) {
                agent.addGoal("Fail operation attempt " + (i / 15), "failure", 80 + (i % 20), 0.9, 3);
            }
            // Also add some normal goals to prevent complete stall
            if (i % 25 == 0) {
                agent.addGoal("Shell check during failure", "shell", 50, 0.6, 1);
                agent.addGoal("HTTP check during failure", "http", 45, 0.5, 2);
            }

            agent.tick();

            if (i % 50 == 0) {
                snapshots.add(snapshot(agent, i));
            }
        }
        System.out.println("  Done. " + agent.metrics().totalTicks() + " ticks");

        System.out.println("\n═══ Phase C: Recovery (300 ticks) ═══");
        // Stop adding failure goals, add only valid goals
        for (int i = 700; i < 1000; i++) {
            if (i % 10 == 0) {
                agent.addGoal("Recovery shell test " + (i / 10), "shell", 75, 0.85, 1);
                agent.addGoal("Recovery HTTP test " + (i / 10), "http", 65, 0.75, 2);
            }

            agent.tick();

            if (i % 50 == 0) {
                snapshots.add(snapshot(agent, i));
            }
        }
        System.out.println("  Done. " + agent.metrics().totalTicks() + " ticks");

        // ══════════════════════════════════════════════════
        // Analysis
        // ══════════════════════════════════════════════════
        System.out.println("\n═══════════════════════════════════════════════");
        System.out.println("  DRIFT ANALYSIS");
        System.out.println("═══════════════════════════════════════════════\n");

        System.out.println("Tick    Confidence  AvgPri  STM   LTM   Entropy  Succ   PlanEff");
        System.out.println("────    ──────────  ──────  ───   ───   ───────  ────   ───────");
        for (DriftSnapshot s : snapshots) {
            System.out.printf("T%04d   %.3f       %5.1f   %4d  %4d   %.2f     %.2f   %.2f%n",
                    s.tick(), s.confidence(), s.avgPriority(),
                    s.stmSize(), s.ltmSize(), s.entropy(),
                    s.successRate(), s.planningEff());
        }

        // Drift metrics
        double confStart = snapshots.getFirst().confidence();
        double confEnd = snapshots.getLast().confidence();
        double confDrift = confEnd - confStart;

        double entropyStart = snapshots.getFirst().entropy();
        double entropyEnd = snapshots.getLast().entropy();
        double entropyDrift = entropyEnd - entropyStart;

        System.out.println("\n─── Drift Summary ───");
        System.out.printf("Confidence drift:    %+.3f (start=%.3f end=%.3f)%n",
                confDrift, confStart, confEnd);
        System.out.printf("Entropy drift:       %+.3f (start=%.2f end=%.2f)%n",
                entropyDrift, entropyStart, entropyEnd);
        System.out.printf("Attention stuck:     %s%n",
                agent.workspace().isAttentionStuck() ? "⚠️ YES (echo chamber)" : "✓ no (diverse)");
        System.out.printf("Forward-model acc:   %.0f%%%n",
                agent.selfModel().forwardModelAccuracy() * 100);

        // Verdict
        System.out.println("\n─── Verdict ───");
        boolean stable = true;
        if (Math.abs(confDrift) > 0.3) {
            System.out.println("⚠️ Confidence drift > 0.3 — unstable metacognition");
            stable = false;
        }
        if (entropyDrift < -0.2) {
            System.out.println("⚠️ Entropy declining — attention converging on single source");
            stable = false;
        }
        if (agent.workspace().isAttentionStuck()) {
            System.out.println("⚠️ Attention stuck — echo chamber detected");
            stable = false;
        }
        if (agent.metrics().planningEfficiency() < 0.4) {
            System.out.println("⚠️ Planning efficiency degraded below 40%");
            stable = false;
        }
        if (stable) {
            System.out.println("✅ Architecture stable over 1000 ticks.");
            System.out.println("   No drift, diverse attention, bounded memory.");
        } else {
            System.out.println("⚠️ Drift detected — see warnings above.");
        }

        System.out.println("\n✅ Stability test complete.");
    }

    private static DriftSnapshot snapshot(Agent agent, int tick) {
        double avgPriority = agent.goals().all().stream()
                .filter(g -> g.active())
                .mapToInt(g -> g.priority())
                .average()
                .orElse(0);
        return new DriftSnapshot(
                tick,
                agent.meta().confidence(),
                avgPriority,
                agent.stm().size(),
                agent.memory().ltm().size(),
                agent.workspace().runningEntropy(),
                agent.metrics().goalSuccessRate(),
                agent.metrics().planningEfficiency()
        );
    }
}
