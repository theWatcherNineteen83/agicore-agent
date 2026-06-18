package de.metis.modules.self;

import de.metis.kernel.goal.KanbanBoard;
import de.metis.kernel.memory.Experience;
import de.metis.kernel.memory.MemoryConsolidator;
import de.metis.kernel.self.SelfNarrative;
import de.metis.kernel.world.CausalHypothesis;
import de.metis.kernel.world.CausalModel;
import de.metis.kernel.world.HypothesisGenerator;
import de.metis.kernel.world.HypothesisStore;
import de.metis.kernel.world.InterventionRunner;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Phase 10 — CausalDreamer im Leerlauf mit echtem Intervention→Observe→Update-Loop.
 *
 * <p>Wenn Metis wenig zu tun hat (Kanban-WIP &lt; 3), erledigt der Dreamer drei Aufgaben:
 * <ol>
 *   <li>Reife PROPOSED-Hypothesen in TESTING versetzen</li>
 *   <li>Testing-Hypothesen nach {@link #TEST_OBSERVATION_TICKS} Ticks via CausalModel-Daten abschließen</li>
 *   <li>Neue Hypothesen aus aktuellen Experiences generieren</li>
 * </ol>
 * Das schließt den Interventions→Observe→Update-Regelkreis.
 */
public final class CausalDreamer {

    private static final Logger LOG = Logger.getLogger(CausalDreamer.class.getName());

    /** Wartezeit in Dreamer-Aufrufen zwischen TESTING und CONCLUDE. */
    private static final long TEST_OBSERVATION_TICKS = 10;

    private final MemoryConsolidator memory;
    private final KanbanBoard kanbanBoard;
    private final HypothesisGenerator hypothesisGenerator;
    private final HypothesisStore hypothesisStore;
    private final CausalModel causalModel;
    private final SelfNarrative narrative;
    private final InterventionRunner interventionRunner;

    /** UUID → vergangene Ticks seit Testing-Start. */
    private final Map<UUID, Long> testingSince = new ConcurrentHashMap<>();

    private final int lookback;
    private final int maxOpenHypotheses;

    private long dreamsRun = 0;
    private long hypothesesCreated = 0;
    private long interventionsRun = 0;
    private long hypothesesCompleted = 0;

    public CausalDreamer(MemoryConsolidator memory, KanbanBoard kanbanBoard,
                         HypothesisGenerator hypothesisGenerator,
                         HypothesisStore hypothesisStore,
                         CausalModel causalModel,
                         SelfNarrative narrative,
                         InterventionRunner interventionRunner) {
        this(memory, kanbanBoard, hypothesisGenerator, hypothesisStore,
                causalModel, narrative, interventionRunner, 30, 50);
    }

    public CausalDreamer(MemoryConsolidator memory, KanbanBoard kanbanBoard,
                         HypothesisGenerator hypothesisGenerator,
                         HypothesisStore hypothesisStore,
                         CausalModel causalModel,
                         SelfNarrative narrative,
                         InterventionRunner interventionRunner,
                         int lookback, int maxOpenHypotheses) {
        this.memory = memory;
        this.kanbanBoard = kanbanBoard;
        this.hypothesisGenerator = hypothesisGenerator;
        this.hypothesisStore = hypothesisStore;
        this.causalModel = causalModel;
        this.narrative = narrative;
        this.interventionRunner = interventionRunner;
        this.lookback = Math.max(5, lookback);
        this.maxOpenHypotheses = Math.max(1, maxOpenHypotheses);
    }

    /**
     * Ein Dream-Zyklus mit echtem Intervention→Observe→Update.
     * Best-effort, wirft nie.
     */
    public boolean dreamOnce() {
        try {
            if (kanbanBoard != null) {
                int wip = kanbanBoard.snapshot().inProgress().size();
                if (wip >= 3) {
                    LOG.fine("CausalDreamer: WIP=" + wip + " >= 3 — skipping");
                    return false;
                }
            }
            boolean didWork = false;

            // ── Schritt 1: Reife PROPOSED-Hypothesen in TESTING versetzen ──
            for (CausalHypothesis h : hypothesisStore.open()) {
                if (h.status() == CausalHypothesis.Status.PROPOSED) {
                    if (testingSince.containsKey(h.id())) continue; // bereits in Warteschleife
                    CausalHypothesis tested = interventionRunner.startTesting(h);
                    if (tested != null) {
                        testingSince.put(h.id(), 0L);
                        didWork = true;
                        LOG.info("CausalDreamer: started testing " + h.id().toString().substring(0, 8)
                                + " — " + h.cause() + " -> " + h.effect());
                    }
                }
            }

            // ── Schritt 2: Testing-Hypothesen nach Beobachtungszeit abschließen ──
            List<UUID> toRemove = new java.util.ArrayList<>();
            for (var entry : testingSince.entrySet()) {
                long ticks = entry.getValue() + 1;
                testingSince.put(entry.getKey(), ticks);
                if (ticks >= TEST_OBSERVATION_TICKS) {
                    var opt = hypothesisStore.get(entry.getKey());
                    if (opt.isPresent() && opt.get().status() == CausalHypothesis.Status.TESTING) {
                        // Messung: CausalModel-Konfidenz für den Cause-Effect-Link
                        double pre = 0.5;
                        double post = 0.5;
                        if (causalModel != null) {
                            var preds = causalModel.predict(opt.get().cause(), opt.get().condition(), 1);
                            if (!preds.isEmpty()) {
                                post = preds.get(0).confidence();
                            }
                        }
                        CausalHypothesis concluded = interventionRunner.conclude(opt.get(), pre, post);
                        if (concluded != null) {
                            hypothesesCompleted++;
                            LOG.info("CausalDreamer: completed " + concluded.id().toString().substring(0, 8)
                                    + " -> " + concluded.status()
                                    + " (pre=" + String.format(java.util.Locale.ROOT, "%.2f", pre)
                                    + " post=" + String.format(java.util.Locale.ROOT, "%.2f", post) + ")");
                            narrative.append("causal",
                                    concluded.status() + ": " + concluded.cause()
                                    + " -> " + concluded.effect()
                                    + " (conf=" + String.format(java.util.Locale.ROOT, "%.2f", post) + ")");
                        }
                    }
                    toRemove.add(entry.getKey());
                }
            }
            toRemove.forEach(testingSince::remove);
            if (!toRemove.isEmpty()) didWork = true;

            // ── Schritt 3: Neue Hypothesen aus Experiences (nur wenn Platz) ──
            int openCount = hypothesisStore.open().size();
            if (openCount < maxOpenHypotheses) {
                List<Experience> recent = memory.stm().recent(lookback);
                if (recent != null && !recent.isEmpty()) {
                    dreamsRun++;
                    Experience ex = recent.get(ThreadLocalRandom.current().nextInt(recent.size()));
                    String cause = ex.actionName();
                    String condition = ex.goalDescription();
                    String effect = ex.success() ? "success" : "failure";
                    String rationale = "Experience #" + dreamsRun + ": " + cause;
                    CausalHypothesis h = hypothesisGenerator.propose(cause, condition, effect, rationale);
                    if (h != null) {
                        hypothesesCreated++;
                        LOG.fine("CausalDreamer: new hypothesis #" + dreamsRun
                                + " — " + cause + " -> " + effect);
                    }
                }
            }

            if (didWork) {
                LOG.info("CausalDreamer: open=" + hypothesisStore.open().size()
                        + " testing=" + testingSince.size()
                        + " completed=" + hypothesesCompleted);
            }
            return didWork;
        } catch (Exception e) {
            LOG.warning("CausalDreamer failed (non-fatal): " + e.getMessage());
            return false;
        }
    }

    public long dreamsRun() { return dreamsRun; }
    public long hypothesesCreated() { return hypothesesCreated; }
    public long interventionsRun() { return interventionsRun; }
    public long hypothesesCompleted() { return hypothesesCompleted; }
    public int testingCount() { return testingSince.size(); }
}
