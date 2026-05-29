package de.metis.modules.eval;

import de.metis.kernel.eval.*;
import de.metis.kernel.persistence.KnowledgeStore;
import de.metis.modules.evolution.ModelRegistry;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Runs the evaluation harness against a live Metis instance.
 * <p>
 * Generates eval tasks from the dataset builder, invokes Metis components,
 * runs the harness, and writes a report for the Watchdog.
 * <p>
 * Supports 3 tiers:
 * <ul>
 *   <li>SMOKE (~30s): 10-15 tasks, every mutation</li>
 *   <li>FULL (~2min): all tasks, pre-promotion</li>
 *   <li>EXTENDED (~10min): all + held-out, nightly prune</li>
 * </ul>
 * <p>
 * Usage:
 * <pre>
 * var runner = new EvalRunner(planner, knowledgeStore, modelRegistry, evalDir);
 * EvalReport report = runner.run("SMOKE");
 * System.out.println(report.summary());
 * </pre>
 */
public class EvalRunner {

    private static final Logger LOG = Logger.getLogger(EvalRunner.class.getName());

    private final EvalHarness harness;
    private final EvalDatasetBuilder builder;
    private final Path evalReportDir;
    private final MetisComponentInvoker invoker;
    private EvalReport lastReport;

    /** Create with required components. */
    public EvalRunner(MetisComponentInvoker invoker, KnowledgeStore knowledgeStore, Path evalReportDir) {
        this.invoker = invoker;
        this.harness = new EvalHarness(invoker);
        this.builder = new EvalDatasetBuilder(knowledgeStore);
        this.evalReportDir = evalReportDir;
    }

    /**
     * Run eval at the specified tier.
     *
     * @param tier SMOKE, FULL, or EXTENDED
     * @return the evaluation report
     */
    public EvalReport run(String tier) {
        LOG.info("EvalRunner: starting " + tier + " tier eval");

        List<EvalTask> tasks = buildTasks(tier);
        if (tasks.isEmpty()) {
            LOG.warning("No tasks generated for tier " + tier);
            return null;
        }

        // Set baseline for regression detection
        if (lastReport != null && !"SMOKE".equals(tier)) {
            harness.setBaseline(lastReport, 0.05); // 2σ noise window
        }

        // Run harness
        EvalReport report = harness.run(tasks, tier);

        // Persist report for Watchdog
        try {
            Files.createDirectories(evalReportDir);
            var path = EvalHarness.writeReport(report, evalReportDir);
            LOG.info("Eval report written: " + path);
        } catch (Exception e) {
            LOG.warning("Failed to write eval report: " + e.getMessage());
        }

        lastReport = report;
        return report;
    }

    /**
     * Run all 3 tiers in sequence. Used for full eval cycles.
     */
    public Map<String, EvalReport> runAll() {
        Map<String, EvalReport> reports = new LinkedHashMap<>();
        for (String tier : new String[]{"SMOKE", "FULL", "EXTENDED"}) {
            reports.put(tier, run(tier));
            // Abort on hard gate failure
            EvalReport r = reports.get(tier);
            if (r != null && !r.gate().ok() && r.tier().equals("SMOKE")) {
                LOG.severe("SMOKE gate FAILED — aborting further tiers");
                break;
            }
        }
        return reports;
    }

    public EvalReport lastReport() { return lastReport; }

    // ── Task selection by tier ─────────────────────────────────────

    private List<EvalTask> buildTasks(String tier) {
        List<EvalTask> all = builder.buildAll();

        return switch (tier.toUpperCase()) {
            case "SMOKE" -> selectSmoke(all);
            case "FULL" -> selectFull(all);
            case "EXTENDED" -> {
                List<EvalTask> extended = new ArrayList<>(all);
                extended.addAll(builder.buildHeldOut());
                yield extended;
            }
            default -> all;
        };
    }

    /** SMOKE: 2-3 tasks per category, quick pass/fail check. */
    private List<EvalTask> selectSmoke(List<EvalTask> all) {
        Map<Category, List<EvalTask>> byCat = new LinkedHashMap<>();
        for (EvalTask t : all) {
            byCat.computeIfAbsent(t.category(), k -> new ArrayList<>()).add(t);
        }

        List<EvalTask> smoke = new ArrayList<>();
        for (var entry : byCat.entrySet()) {
            List<EvalTask> catTasks = entry.getValue();
            // Pick SAFETY tasks first (zero-tolerance)
            if (entry.getKey() == Category.SAFETY) {
                smoke.addAll(catTasks.stream().limit(4).toList());
            } else if (entry.getKey() == Category.PERFORMANCE) {
                smoke.addAll(catTasks.stream().limit(2).toList());
            } else {
                smoke.addAll(catTasks.stream().limit(2).toList());
            }
        }

        LOG.info("SMOKE tier: " + smoke.size() + " tasks selected");
        return smoke;
    }

    /** FULL: all non-held-out tasks. */
    private List<EvalTask> selectFull(List<EvalTask> all) {
        List<EvalTask> full = all.stream()
                .filter(t -> !t.heldOut())
                .toList();
        LOG.info("FULL tier: " + full.size() + " tasks selected");
        return full;
    }
}
