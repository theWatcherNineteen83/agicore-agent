package de.metis.kernel.goal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Phase 9.4 — Versprechen an Personen als first-class Goal.
 *
 * <p>Wenn Metis sagt „ich melde mich um 18:00", soll das nicht im
 * Conversation-Log verschwinden, sondern als {@link LongHorizonGoal} mit
 * Status PROPOSED → ACTIVE → DONE laufen — überprüfbar, mit Deadline,
 * und mit Owner = der versprochenen Person.
 *
 * <p>Bewusst dünn (~3 Methoden). Die strukturierte Speicherung erfolgt
 * in {@link GoalHierarchy}; hier nur Convenience für Conversational-
 * Pfade (HTTP/Telegram), die einen Commitment-Datensatz erzeugen wollen.
 */
public class CommitmentRegister {

    private static final Logger LOG = Logger.getLogger(CommitmentRegister.class.getName());

    private final GoalHierarchy hierarchy;

    public CommitmentRegister(GoalHierarchy hierarchy) {
        this.hierarchy = hierarchy;
    }

    /**
     * Record a promise made to a person.
     *
     * @param toWhom    person identifier (e.g. "Georg", "telegram:265324594")
     * @param what      what was promised, free text
     * @param due       when it must be fulfilled
     * @return the persisted commitment goal
     */
    public LongHorizonGoal record(String toWhom, String what, Instant due) {
        String title = "Versprechen an " + toWhom + ": " + what;
        LongHorizonGoal g = new LongHorizonGoal(
                null, title,
                "Commitment, eingelöst zu " + due,
                GoalHorizon.OPERATIONAL,
                LongHorizonGoal.Status.ACTIVE,
                null, List.of(),
                Instant.now(),
                due,
                null, null,
                0.0,
                85,
                toWhom,
                List.of("commitment", "person:" + toWhom)
        );
        LongHorizonGoal saved = hierarchy.upsert(g);
        LOG.info("CommitmentRegister: recorded commitment to " + toWhom + " (" + saved.id() + ")");
        return saved;
    }

    /** All open commitments, regardless of person. */
    public List<LongHorizonGoal> openCommitments() {
        return hierarchy.all().stream()
                .filter(g -> g.tags().contains("commitment") && g.isOpen())
                .collect(Collectors.toList());
    }

    /** Open commitments to a specific person. */
    public List<LongHorizonGoal> openFor(String toWhom) {
        String tag = "person:" + toWhom;
        return hierarchy.all().stream()
                .filter(g -> g.tags().contains(tag) && g.isOpen())
                .collect(Collectors.toList());
    }

    /** Commitments past their deadline. */
    public List<LongHorizonGoal> overdue() {
        return openCommitments().stream()
                .filter(LongHorizonGoal::isOverdue)
                .collect(Collectors.toList());
    }

    public LongHorizonGoal markDone(UUID id) {
        return hierarchy.get(id)
                .map(g -> hierarchy.upsert(g.withStatus(LongHorizonGoal.Status.DONE)))
                .orElse(null);
    }
}
