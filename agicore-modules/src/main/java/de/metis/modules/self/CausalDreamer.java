package de.metis.modules.self;

import de.metis.kernel.goal.KanbanBoard;
import de.metis.kernel.memory.Experience;
import de.metis.kernel.memory.MemoryConsolidator;
import de.metis.kernel.self.SelfNarrative;
import de.metis.kernel.world.CausalHypothesis;
import de.metis.kernel.world.HypothesisGenerator;
import de.metis.kernel.world.HypothesisStore;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Phase 10.5 — CausalDreamer im Leerlauf.
 *
 * <p>Wenn Metis wenig zu tun hat (Kanban-WIP &lt; 2), nutzt es die freie
 * Kapazität, um aus vergangenen Experiences kausale Hypothesen abzuleiten.
 * Das ist der erste Schritt zu einem "Science-as-a-Service"-Modus, in dem
 * der Agent aktiv Experimente plant statt nur zu reagieren.
 *
 * <p><b>Ablauf (deterministisch, kein LLM):</b>
 * <ol>
 *   <li>Prüft KanbanBoard.inProgress.size() &lt; 2 (Idle-Guard).</li>
 *   <li>Nimmt eine zufällige Experience der letzten N aus dem STM.</li>
 *   <li>Extrahiert cause (actionName), condition (goalDescription),
 *       effect (success/failure), rationale (body) — alles aus der Experience.</li>
 *   <li>Erzeugt via {@link HypothesisGenerator} eine {@link CausalHypothesis}
 *       mit Status PROPOSED und speichert sie im {@link HypothesisStore}.</li>
 *   <li>Protokolliert in {@link SelfNarrative} als "dream"-Eintrag.</li>
 * </ol>
 *
 * <p>Bewusst in {@code modules}, nicht im Kernel: nutzt LLM/Storytelling
 * für den Narrative-Eintrag, aber die Hypothese selbst ist deterministisch.
 * Später kann ein kleiner LLM-Call (granite4.1:3b) den Traum-Narrativ verschönern.
 */
public final class CausalDreamer {

    private static final Logger LOG = Logger.getLogger(CausalDreamer.class.getName());

    private final MemoryConsolidator memory;
    private final KanbanBoard kanbanBoard;
    private final HypothesisGenerator hypothesisGenerator;
    private final HypothesisStore hypothesisStore;
    private final SelfNarrative narrative;

    /** Wieviele recent experiences in die Auswahl einbezogen werden. */
    private final int lookback;

    /** Maximale Hypothesen, die insgesamt im Store liegen dürfen (Schutz vor Inflation). */
    private final int maxOpenHypotheses;

    private long dreamsRun = 0;
    private long hypothesesCreated = 0;

    public CausalDreamer(MemoryConsolidator memory, KanbanBoard kanbanBoard,
                         HypothesisGenerator hypothesisGenerator,
                         HypothesisStore hypothesisStore,
                         SelfNarrative narrative) {
        this(memory, kanbanBoard, hypothesisGenerator, hypothesisStore,
                narrative, 30, 50);
    }

    public CausalDreamer(MemoryConsolidator memory, KanbanBoard kanbanBoard,
                         HypothesisGenerator hypothesisGenerator,
                         HypothesisStore hypothesisStore,
                         SelfNarrative narrative,
                         int lookback, int maxOpenHypotheses) {
        this.memory = memory;
        this.kanbanBoard = kanbanBoard;
        this.hypothesisGenerator = hypothesisGenerator;
        this.hypothesisStore = hypothesisStore;
        this.narrative = narrative;
        this.lookback = Math.max(5, lookback);
        this.maxOpenHypotheses = Math.max(1, maxOpenHypotheses);
    }

    /**
     * Ein Traum-Zyklus. Best-effort, schluckt alle Fehler.
     *
     * @return true, wenn eine Hypothese erzeugt wurde
     */
    public boolean dreamOnce() {
        try {
            // Idle-Guard: nur träumen wenn wenig zu tun ist
            int wip = kanbanBoard.snapshot().inProgress().size();
            if (wip >= 2) {
                LOG.fine("CausalDreamer: WIP=" + wip + " ≥ 2 — skipping dream");
                return false;
            }
            // Überlauf-Schutz: nicht zu viele offene Hypothesen
            int openCount = hypothesisStore.open().size();
            if (openCount >= maxOpenHypotheses) {
                LOG.fine("CausalDreamer: " + openCount + " open hypotheses ≥ "
                        + maxOpenHypotheses + " — max reached, skipping");
                return false;
            }
            List<Experience> recent = memory.stm().recent(lookback);
            if (recent == null || recent.isEmpty()) {
                return false;
            }

            dreamsRun++;

            // Zufällige Experience wählen
            Experience ex = recent.get(ThreadLocalRandom.current().nextInt(recent.size()));

            // Kausal-Felder aus der Experience extrahieren
            String cause = ex.actionName();
            String condition = ex.goalDescription();
            String effect = ex.success() ? "success (" + ex.body().substring(0,
                    Math.min(80, ex.body().length())) + "...)"
                    : "failure (predictionError=" + String.format("%.2f", ex.predictionError()) + ")";
            String rationale = "Derived from experience #" + dreamsRun
                    + ": action='" + cause + "' under goal='" + condition + "'";

            // Hypothese generieren und speichern (Status PROPOSED — kein Safety-Gate nötig,
            // das greift erst bei tryStart → TESTING über den InterventionRunner)
            CausalHypothesis h = hypothesisGenerator.propose(cause, condition, effect, rationale);
            hypothesisStore.upsert(h);
            hypothesesCreated++;

            // Narrative-Eintrag (deterministisch, kein LLM)
            String dream = "CausalDream: weil [" + cause + "] unter ["
                    + condition + "] → " + (ex.success() ? "Erfolg" : "Fehlschlag")
                    + ". Neue Hypothese #" + h.id().toString().substring(0, 8);
            narrative.append("dream", dream);

            LOG.info("CausalDreamer #" + dreamsRun + ": hypothesis="
                    + h.id().toString().substring(0, 8)
                    + " cause=" + cause + " wip=" + wip
                    + " open=" + hypothesisStore.open().size());
            return true;
        } catch (Exception e) {
            LOG.warning("CausalDreamer failed (non-fatal): " + e.getMessage());
            return false;
        }
    }

    public long dreamsRun() { return dreamsRun; }
    public long hypothesesCreated() { return hypothesesCreated; }
}
