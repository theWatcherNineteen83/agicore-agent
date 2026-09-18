package de.metis.modules;

import de.metis.kernel.goal.Goal;
import de.metis.kernel.goal.GoalManager;
import de.metis.kernel.memory.Experience;
import de.metis.kernel.world.Belief;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Bridge zwischen dem externen {@link UserGoalBoard} und Metis' internem
 * Goal-Zyklus.
 * <p>
 * Periodischer Tick (alle 15 s):
 * <ol>
 *   <li><b>Aufnahme:</b> OFFENE User-Goals (Cooldown abgelaufen) werden als
 *       interne Metis-Goals mit ServiceClass {@code EXPEDITE} angelegt —
 *       damit der CoreLoop sie garantiert als nächstes zieht (Pull-Reihenfolge
 *       1). Max. {@link #MAX_PARALLEL} User-Goals gleichzeitig in Arbeit.</li>
 *   <li><b>Überwachung:</b> IN_ARBEIT-Goals werden gegen den GoalManager
 *       geprüft. Ist das interne Goal inaktiv (= erfolgreich completed),
 *       läuft der <b>maschinelle Vorabtest</b>:
 *       <ul>
 *         <li>AUFGABE: Goal completed = bestanden.</li>
 *         <li>WISSEN_ANEIGNEN: Goal completed <i>und</i> Belief-Zuwachs
 *             (beliefCount höher als bei Aufnahme) = bestanden.</li>
 *       </ul>
 *       Bestanden → Status ZU_TESTEN (Review durch Georg).
 *       Nicht bestanden → zurück auf OFFEN mit Kommentar + Backoff-Cooldown
 *       (15 min × Versuch, max 24 h). Timeout/verschwunden → ebenfalls zurück.</li>
 * </ol>
 */
public class UserGoalBridge {

    private static final Logger LOG = Logger.getLogger(UserGoalBridge.class.getName());

    /** Max. User-Goals gleichzeitig in Arbeit (EXPEDITE-Slots). */
    private static final int MAX_PARALLEL = 2;
    /** Timeout für ein internes Metis-Goal, bevor wir aufgeben. */
    private static final Duration GOAL_TIMEOUT = Duration.ofHours(6);
    /** Basis-Cooldown nach Fehlschlag (× Versuch). */
    private static final Duration BACKOFF_BASE = Duration.ofMinutes(15);
    /** Max. Cooldown. */
    private static final Duration BACKOFF_MAX = Duration.ofHours(24);

    private final Agent agent;
    private final UserGoalBoard board;
    private final ScheduledExecutorService scheduler;
    /** userGoalId → Belief-Stand bei Aufnahme (für Wissen-Aneignen-Test). */
    private final Map<String, Integer> startBeliefs = new HashMap<>();
    /** userGoalId → Zeitpunkt der Aufnahme (Timeout). */
    private final Map<String, Instant> startedAt = new HashMap<>();

    public UserGoalBridge(Agent agent, UserGoalBoard board) {
        this.agent = agent;
        this.board = board;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "usergoal-bridge");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::tick, 5, 15, TimeUnit.SECONDS);
        LOG.info("UserGoalBridge aktiv — externe Goals werden in den Metis-Zyklus übernommen (alle 15s)");
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    void tick() {
        try {
            int inArbeit = board.byStatus(UserGoalBoard.Status.IN_ARBEIT).size();
            if (inArbeit < MAX_PARALLEL) {
                aufnehmen();
            }
            for (var ug : board.byStatus(UserGoalBoard.Status.IN_ARBEIT)) {
                ueberwachen(ug);
            }
            aufraeumen();
        } catch (Exception e) {
            LOG.warning("UserGoalBridge tick fehlgeschlagen: " + e.getMessage());
        }
    }

    /** OFFEN-Goals (Cooldown abgelaufen) als Metis-Goals anlegen. */
    private void aufnehmen() {
        GoalManager goals = agent.core().goals();
        for (var ug : board.byStatus(UserGoalBoard.Status.OFFEN)) {
            if (!board.retryReady(ug)) continue;
            String cat = ug.category == UserGoalBoard.Category.WISSEN_ANEIGNEN
                    ? "wissen-aneignen" : "aufgabe";
            // EXPEDITE: wird vom CoreLoop als erstes gepullt (Pull-Reihenfolge 1)
            Goal internal = new Goal(ug.description, cat, ug.priority,
                    0.8, 2,
                    Goal.ServiceClass.EXPEDITE,
                    Goal.ResourceType.LIGHT, null);
            Goal stored = goals.add(internal);
            ug.attempts++;
            board.markInArbeit(ug, stored.id().toString());
            startedAt.put(ug.id, Instant.now());
            startBeliefs.put(ug.id, agent.worldModel().beliefCount());
            LOG.info("UserGoalBridge: aufgenommen [" + cat + "] " + ug.id
                    + " → Metis-Goal " + stored.id());
            if (board.byStatus(UserGoalBoard.Status.IN_ARBEIT).size() >= MAX_PARALLEL) break;
        }
    }

    /** IN_ARBEIT-Goals: Abschluss erkennen + maschinellen Test ausführen. */
    private void ueberwachen(UserGoalBoard.UserGoal ug) {
        if (ug.metisGoalId == null) {
            // Metis neu gestartet o.ä. — Goal erneut einreihen
            board.markZurueck(ug, "Metis-Goal-Referenz fehlt — Goal wird erneut eingereiht.", 0L);
            startedAt.remove(ug.id);
            startBeliefs.remove(ug.id);
            return;
        }
        Goal internal = findInternal(ug.metisGoalId);
        if (internal == null) {
            board.markZurueck(ug, "Internes Metis-Goal nicht mehr vorhanden (Neustart?) — erneut eingereiht.", 0L);
            startedAt.remove(ug.id);
            startBeliefs.remove(ug.id);
            return;
        }
        if (internal.active()) {
            Instant start = startedAt.getOrDefault(ug.id, Instant.now());
            if (Duration.between(start, Instant.now()).compareTo(GOAL_TIMEOUT) > 0) {
                board.markZurueck(ug, "Timeout: Metis-Goal nach "
                        + GOAL_TIMEOUT.toHours() + "h nicht abgeschlossen — Goal zurückgesetzt.", 0L);
                startedAt.remove(ug.id);
                startBeliefs.remove(ug.id);
                goals().complete(internal.id());
            }
            return;
        }
        // Internes Goal ist inaktiv = completed (nur Erfolg führt zu complete)
        maschinellerTest(ug, internal);
    }

    /** Maschineller Vorabtest nach Abschluss des internen Goals. */
    private void maschinellerTest(UserGoalBoard.UserGoal ug, Goal internal) {
        String report;
        boolean bestanden;
        String learnedSummary = null;
        String result = null;
        String testProtocol = null;
        Instant since = startedAt.get(ug.id);
        if (ug.category == UserGoalBoard.Category.WISSEN_ANEIGNEN) {
            int vorher = startBeliefs.getOrDefault(ug.id, -1);
            int jetzt = agent.worldModel().beliefCount();
            int delta = vorher >= 0 ? jetzt - vorher : 0;
            bestanden = vorher >= 0 && delta > 0;
            report = "Wissen-Aneignen: Metis-Goal abgeschlossen, Belief-Zuwachs "
                    + (delta > 0 ? "+" + delta : "keiner")
                    + " (" + vorher + " → " + jetzt + ") "
                    + (bestanden ? "— bestanden." : "— NICHT bestanden (kein neues Wissen).");
            if (bestanden) {
                learnedSummary = buildLearnedSummary(since);
            }
        } else {
            bestanden = true;
            report = "Aufgabe: Metis-Goal erfolgreich abgeschlossen (Aktion ok) — bestanden.";
            result = buildTaskResult(ug.description);
            testProtocol = buildTestProtocol(ug.description);
        }
        startedAt.remove(ug.id);
        startBeliefs.remove(ug.id);
        if (bestanden) {
            board.markZuTesten(ug, report, learnedSummary, result, testProtocol);
        } else {
            // Nicht bestanden → zurück auf OFFEN mit Backoff, damit Georg es
            // präzisieren kann und Metis nicht in einer Schleife hängt.
            long backoff = Math.min(BACKOFF_BASE.toMillis() * Math.max(1, ug.attempts),
                    BACKOFF_MAX.toMillis());
            board.setRetryAfter(ug, System.currentTimeMillis() + backoff);
            board.markZurueck(ug, report, System.currentTimeMillis() + backoff);
        }
    }

    /** WISSEN_ANEIGNEN: Überblick über die seit Aufnahme neu gelernten Beliefs. */
    private String buildLearnedSummary(Instant since) {
        if (since == null) {
            return "Keine Startzeit erfasst — kein Lern-Überblick möglich.";
        }
        List<Belief> neu = agent.worldModel().beliefsSince(since.toString(), 15);
        if (neu.isEmpty()) {
            return "Keine neuen Beliefs seit Aufnahme gefunden.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(neu.size()).append(" neue Belief(s):");
        for (Belief b : neu) {
            sb.append("\n• ").append(truncate(b.statement(), 90))
              .append(" (conf ").append(String.format("%.2f", b.confidence())).append(")");
        }
        return sb.toString();
    }

    /** AUFGABE: Ergebnis der Aktionen zu diesem Goal (letzter erfolgreicher Action-Body). */
    private String buildTaskResult(String goalDesc) {
        List<Experience> exps = experiencesFor(goalDesc);
        if (exps.isEmpty()) {
            return "Keine Aktionen zu diesem Goal im Kurzzeitgedächtnis gefunden.";
        }
        for (int i = exps.size() - 1; i >= 0; i--) {
            Experience e = exps.get(i);
            if (e.success() && e.body() != null && !e.body().isBlank()) {
                return truncate(e.body(), 400);
            }
        }
        return "Aktionen liefen, aber ohne Ergebnis-Ausgabe.";
    }

    /** AUFGABE: Testprotokoll — welche Aktionen liefen, mit Erfolg/Misserfolg. */
    private String buildTestProtocol(String goalDesc) {
        List<Experience> exps = experiencesFor(goalDesc);
        if (exps.isEmpty()) {
            return "Keine Aktionen protokolliert.";
        }
        int ok = 0;
        int fail = 0;
        for (Experience e : exps) {
            if (e.success()) ok++; else fail++;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(ok).append(" ok / ").append(fail).append(" fehlgeschlagen:");
        for (Experience e : exps) {
            sb.append("\n• ").append(e.success() ? "✓ " : "✗ ").append(e.actionName());
        }
        return sb.toString();
    }

    /** Experiences zu einer Goal-Beschreibung aus dem Kurzzeitgedächtnis. */
    private List<Experience> experiencesFor(String goalDesc) {
        List<Experience> out = new ArrayList<>();
        for (Experience e : agent.stm().all()) {
            if (goalDesc.equals(e.goalDescription())) out.add(e);
        }
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    /** Verwaiste Maps-Einträge entfernen. */
    private void aufraeumen() {
        List<String> aktiveIds = board.byStatus(UserGoalBoard.Status.IN_ARBEIT)
                .stream().map(g -> g.id).toList();
        startedAt.keySet().retainAll(aktiveIds);
        startBeliefs.keySet().retainAll(aktiveIds);
    }

    // ── Helfer ──────────────────────────────────────────────────

    private Goal findInternal(String metisGoalId) {
        for (Goal g : goals().all()) {
            if (g.id().toString().equals(metisGoalId)) return g;
        }
        return null;
    }

    private GoalManager goals() {
        return agent.core().goals();
    }
}
