package de.metis.modules.self;

import de.metis.kernel.goal.GoalManager;

import java.io.StringWriter;
import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Phase 12a — RuntimeExceptionHandler.
 *
 * <p>Fangt uncaught exceptions im CoreLoop, logged Stacktrace
 * und triggert ein Fix-Goal.
 */
public class RuntimeExceptionHandler {

    private static final Logger LOG = Logger.getLogger(RuntimeExceptionHandler.class.getName());

    private final AtomicInteger recentFixes = new AtomicInteger(0);
    private long lastFixTime = 0;
    private static final long COOLDOWN_MS = 120_000; // 2 min between auto-fixes

    /**
     * Wird vom CoreLoop-Throwable-Hook aufgerufen.
     */
    public void handle(Throwable t, GoalManager goals) {
        // Stacktrace as string
        var sw = new StringWriter();
        var pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        pw.flush();
        String trace = sw.toString();

        LOG.warning("RuntimeExceptionHandler: caught " + t.getClass().getSimpleName()
                + " - " + t.getMessage());

        // Extract source location from stack trace
        String source = extractSource(trace);
        String goalDesc = extractGoalDescription(t, source);

        // Rate limit
        long now = System.currentTimeMillis();
        if (now - lastFixTime < COOLDOWN_MS) {
            LOG.info("RuntimeExceptionHandler: cooldown active, skipping auto-fix");
            return;
        }

        if (goals != null && source != null) {
            goals.add("fix: " + goalDesc, 80, 0.6, 4);
            LOG.info("RuntimeExceptionHandler: created fix-goal: " + goalDesc);
            lastFixTime = now;
            recentFixes.incrementAndGet();
        }
    }

    private String extractGoalDescription(Throwable t, String source) {
        String cls = t.getClass().getSimpleName();
        String msg = t.getMessage() != null ? t.getMessage() : "";
        String shortMsg = msg.length() > 60 ? msg.substring(0, 60) : msg;
        return cls + " in " + (source != null ? source : "unknown") + ": " + shortMsg;
    }

    private String extractSource(String trace) {
        for (String line : trace.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("at de.metis.")) {
                int paren = trimmed.indexOf('(');
                if (paren > 0) {
                    String method = trimmed.substring(3, paren);
                    return method.substring(0, method.lastIndexOf('.'));
                }
            }
        }
        return null;
    }

    public int recentFixCount() { return recentFixes.get(); }
}
