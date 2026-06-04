package de.metis.kernel.self;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Phase 12a — Sammelt Runtime-Fehler und erstellt BugFix-Goals.
 *
 * <p>Jeder uncaught Exception wird in einer BugReport-Struktur festgehalten.
 * Metis kann daraus eigenständige Fix-Versuche starten, ohne manuelles
 * Eingreifen.
 *
 * <p>Die Liste der Bugs ist auf {@link #MAX_STORED} begrenzt (Ringpuffer).
 * Fix-Versuche werden dedupliziert: gleicher Stacktrace → gleicher Bug.
 */
public class BugTracker {

    private static final Logger LOG = Logger.getLogger(BugTracker.class.getName());

    /** Maximale Anzahl gespeicherter Bug-Reports (Ringpuffer). */
    private static final int MAX_STORED = 20;

    /** Maximale Fix-Versuche pro Bug, bevor manuelle Eskalation. */
    private static final int MAX_FIX_ATTEMPTS = 3;

    /** Cooldown zwischen Fix-Versuchen (Millisekunden). */
    private static final long FIX_COOLDOWN_MS = 300_000; // 5 min

    private final Deque<BugReport> bugs = new ArrayDeque<>(MAX_STORED);
    private final Map<String, BugReport> bySignature = new LinkedHashMap<>();

    /** Callback zum Erzeugen von BugFix-Goals. Kann null sein (nur Logging). */
    private java.util.function.Consumer<String> fixGoalTrigger = null;
    private Runnable rollbackTrigger = null;

    public BugTracker() {
    }

    /**
     * Setzt einen Callback, der bei kritischen Bugs einen Fix-Goal
     * im Agenten anlegt. Das String-Argument ist der Stacktrace.
     */
    public BugTracker withRollbackTrigger(Runnable trigger) {
        this.rollbackTrigger = trigger;
        return this;
    }

    public BugTracker withFixGoalTrigger(java.util.function.Consumer<String> trigger) {
        this.fixGoalTrigger = trigger;
        return this;
    }

    /**
     * Nimmt einen Fehler auf und entscheidet, ob ein Fix-Versuch
     * gestartet werden soll.
     *
     * @param source  Komponenten-Name (z.B. "tick", "telegram", "planner")
     * @param error   die geworfene Exception
     * @return true wenn ein Fix-Versuch empfohlen wird
     */
    public boolean report(String source, Throwable error) {
        String signature = signature(error);
        String stacktrace = stacktrace(error);

        BugReport existing = bySignature.get(signature);
        if (existing != null) {
            // Bekannter Bug: Zähler erhöhen, ggf. Cooldown prüfen
            existing = existing.withAnotherOccurrence();
            if (existing.fixAttempts >= MAX_FIX_ATTEMPTS) {
                LOG.warning("BugTracker: Bug exhausted " + MAX_FIX_ATTEMPTS
                        + " fix attempts — manual escalation needed: "
                        + existing.summary());
                bySignature.put(signature, existing);
                return false;
            }
            if (System.currentTimeMillis() - existing.timestamp().toEpochMilli() < FIX_COOLDOWN_MS) {
                LOG.fine("BugTracker: Bug still in cooldown: " + existing.summary());
                // Even in cooldown, count the fix attempt
                existing = existing.incrementFixAttempts();
                bySignature.put(signature, existing);
                if (existing.fixAttempts() >= MAX_FIX_ATTEMPTS) {
                    LOG.warning("BugTracker: Bug exhausted " + MAX_FIX_ATTEMPTS
                            + " fix attempts (cooldown) -- triggering rollback: "
                            + existing.summary());
                    if (rollbackTrigger != null) {
                        try { rollbackTrigger.run(); } catch (Exception ex) {
                            LOG.warning("Rollback trigger failed: " + ex.getMessage());
                        }
                    }
                }
                return false;
            }
            bySignature.put(signature, existing);
        }

        // Neuer oder cooldown-abgelaufener Bug
        BugReport report = new BugReport(
                UUID.randomUUID().toString(),
                source,
                stacktrace,
                signature,
                error.getMessage() != null ? error.getMessage() : "No message",
                error.getClass().getSimpleName(),
                Instant.now(),
                1,
                0
        );

        // Ringpuffer-Verwaltung
        if (bySignature.size() >= MAX_STORED) {
            String oldestKey = bySignature.keySet().iterator().next();
            bySignature.remove(oldestKey);
        }
        bySignature.put(signature, report);
        bugs.addLast(report);
        if (bugs.size() > MAX_STORED) bugs.removeFirst();

        LOG.info("BugTracker: new bug [" + source + "] " + report.summary());

        // Fix-Goal auslösen
        if (fixGoalTrigger != null) {
            String goalDesc = "fix: " + report.shortSource()
                    + " — " + report.exceptionType + ": " + report.message;
            LOG.info("BugTracker: triggering fix goal: " + goalDesc);
            try {
                fixGoalTrigger.accept(goalDesc);
                report = report.withFixAttempt();
                bySignature.put(signature, report);
            } catch (Exception e) {
                LOG.warning("BugTracker: fix goal trigger failed: " + e.getMessage());
            }
        }

        return true;
    }

    /**
     * Alle offenen Bug-Reports.
     */
    public List<BugReport> all() {
        return List.copyOf(bugs);
    }

    /**
     * Anzahl der Bugs, die noch Fix-Versuche übrig haben.
     */
    public int openCount() {
        return (int) bySignature.values().stream()
                .filter(b -> b.fixAttempts < MAX_FIX_ATTEMPTS)
                .count();
    }

    /**
     * Anzahl der Bugs insgesamt.
     */
    public int size() { return bugs.size(); }

    // ── Helpers ───────────────────────────────────────────────

    private String signature(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        String trace = sw.toString();
        // Nur die ersten 10 Zeilen des Stacktrace für die Signatur
        String[] lines = trace.split("\n");
        StringBuilder sig = new StringBuilder();
        int limit = Math.min(lines.length, 10);
        for (int i = 0; i < limit; i++) {
            sig.append(lines[i].trim()).append("\n");
        }
        return sig.toString();
    }

    private String stacktrace(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        return sw.toString();
    }

    // ── BugReport ─────────────────────────────────────────────

    /**
     * Strukturierte Fehlerbeschreibung für einen BugFix-Goal.
     */
    public record BugReport(
            String id,
            String source,
            String stacktrace,
            String signature,
            String message,
            String exceptionType,
            Instant timestamp,
            int occurrenceCount,
            int fixAttempts
    ) {
        public String summary() {
            return "[" + exceptionType + "] " + message + " (" + occurrenceCount + "x)";
        }
        public String shortSource() {
            // Extrahiere den kürzesten sinnvollen Source-Bezeichner
            if (source.length() > 40) {
                return source.substring(source.length() - 40);
            }
            return source;
        }
        public BugReport withAnotherOccurrence() {
            return new BugReport(id, source, stacktrace, signature, message,
                    exceptionType, java.time.Instant.now(), occurrenceCount + 1, fixAttempts);
        }
        public BugReport withFixAttempt() {
            return new BugReport(id, source, stacktrace, signature, message,
                    exceptionType, timestamp, occurrenceCount, fixAttempts + 1);
        }
        public BugReport incrementFixAttempts() {
            return new BugReport(id, source, stacktrace, signature, message,
                    exceptionType, timestamp, occurrenceCount, fixAttempts + 1);
        }
    }
}
