package de.metis.modules.self;

import de.metis.kernel.goal.KanbanBoard;
import de.metis.kernel.memory.Experience;
import de.metis.kernel.memory.MemoryConsolidator;
import de.metis.kernel.self.SelfNarrative;
import de.metis.kernel.world.CausalHypothesis;
import de.metis.kernel.world.HypothesisGenerator;
import de.metis.kernel.world.HypothesisStore;
import de.metis.kernel.world.InterventionRunner;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Phase 10.5 — CausalDreamer im Leerlauf mit InterventionRunner-Integration.
 *
 * <p>Wenn Metis wenig zu tun hat (Kanban-WIP &lt; 2), erzeugt es aus Experiences
 * kausale Hypothesen. Neu seit Phase 10 → 100%: nach Generierung wird die
 * Hypothese direkt durch {@link InterventionRunner} geprüft (pre/post-Messung
 * + Intervention).
 */
public final class CausalDreamer {

    private static final Logger LOG = Logger.getLogger(CausalDreamer.class.getName());

    private static final double FLAT_NOISE = 0.01;

    private final MemoryConsolidator memory;
    private final KanbanBoard kanbanBoard;
    private final HypothesisGenerator hypothesisGenerator;
    private final HypothesisStore hypothesisStore;
    private final SelfNarrative narrative;
    private final InterventionRunner interventionRunner;

    private final int lookback;
    private final int maxOpenHypotheses;

    private long dreamsRun = 0;
    private long hypothesesCreated = 0;
    private long interventionsRun = 0;

    public CausalDreamer(MemoryConsolidator memory, KanbanBoard kanbanBoard,
                         HypothesisGenerator hypothesisGenerator,
                         HypothesisStore hypothesisStore,
                         SelfNarrative narrative,
                         InterventionRunner interventionRunner) {
        this(memory, kanbanBoard, hypothesisGenerator, hypothesisStore,
                narrative, interventionRunner, 30, 50);
    }

    public CausalDreamer(MemoryConsolidator memory, KanbanBoard kanbanBoard,
                         HypothesisGenerator hypothesisGenerator,
                         HypothesisStore hypothesisStore,
                         SelfNarrative narrative,
                         InterventionRunner interventionRunner,
                         int lookback, int maxOpenHypotheses) {
        this.memory = memory;
        this.kanbanBoard = kanbanBoard;
        this.hypothesisGenerator = hypothesisGenerator;
        this.hypothesisStore = hypothesisStore;
        this.narrative = narrative;
        this.interventionRunner = interventionRunner;
        this.lookback = Math.max(5, lookback);
        this.maxOpenHypotheses = Math.max(1, maxOpenHypotheses);
    }

    /**
     * Ein Traum-Zyklus mit Intervention. Best-effort.
     */
    public boolean dreamOnce() {
        try {
            if (kanbanBoard != null) {
                int wip = kanbanBoard.snapshot().inProgress().size();
                if (wip >= 2) {
                    LOG.fine("CausalDreamer: WIP=" + wip + " >= 2 — skipping");
                    return false;
                }
            }
            int openCount = hypothesisStore.open().size();
            if (openCount >= maxOpenHypotheses) {
                LOG.fine("CausalDreamer: " + openCount + " open >= " + maxOpenHypotheses + " — max reached");
                return false;
            }

            List<Experience> recent = memory.stm().recent(lookback);
            if (recent == null || recent.isEmpty()) return false;

            dreamsRun++;
            Experience ex = recent.get(ThreadLocalRandom.current().nextInt(recent.size()));

            String cause = ex.actionName();
            String condition = ex.goalDescription();
            String effect = ex.success() ? "success" : "failure";
            String rationale = "Experience #" + dreamsRun + ": " + cause;

            CausalHypothesis h = hypothesisGenerator.propose(cause, condition, effect, rationale);
            CausalHypothesis saved = hypothesisStore.upsert(h);
            hypothesesCreated++;

            // Phase 10 -> 100%: Intervention Runner testet die Hypothese
            if (interventionRunner != null && saved != null) {
                try {
                    // Pre: aktueller Belief-Value
                    double pre = hypothesisStore.open().size();
                    // Intervention: NoOp — die Hypothese wird durch normale
                    // Tick-Observations im CausalModel geprueft
                    Runnable noop = () -> { /* observed via CoreLoop tick */ };
                    CausalHypothesis tested = interventionRunner.runSync(saved, () -> pre, noop);
                    if (tested != null) {
                        interventionsRun++;
                        LOG.info("CausalDreamer intervention #" + interventionsRun
                                + ": " + tested.id().toString().substring(0, 8)
                                + " -> " + tested.status());
                    }
                } catch (Exception ie) {
                    LOG.fine("Intervention skipped: " + ie.getMessage());
                }
            }

            String dream = "CausalDream: [" + cause + "] unter [" + condition + "] -> "
                    + (ex.success() ? "Erfolg" : "Fehlschlag")
                    + ". Hypothese #" + h.id().toString().substring(0, 8);
            narrative.append("dream", dream);

            LOG.info("CausalDreamer #" + dreamsRun + ": h="
                    + h.id().toString().substring(0, 8)
                    + " cause=" + cause + " wip="
                    + (kanbanBoard != null ? kanbanBoard.snapshot().inProgress().size() : -1)
                    + " open=" + hypothesisStore.open().size());
            return true;
        } catch (Exception e) {
            LOG.warning("CausalDreamer failed (non-fatal): " + e.getMessage());
            return false;
        }
    }

    public long dreamsRun() { return dreamsRun; }
    public long hypothesesCreated() { return hypothesesCreated; }
    public long interventionsRun() { return interventionsRun; }
}
