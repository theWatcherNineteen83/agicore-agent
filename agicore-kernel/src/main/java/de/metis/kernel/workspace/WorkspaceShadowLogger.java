package de.metis.kernel.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Phase 7.x — Read-only Schattenmodus für den {@link GlobalWorkspace}.
 *
 * <p>Konvergente Empfehlung der externen KI-Reviews (Gemini 2.5, GLM-5.1, Qwen,
 * 2026-05-31): bevor der {@code AgentCoreLoop} auf ein Blackboard/GWT-Modell
 * umgebaut wird, soll der Workspace-Stream <em>beobachtbar</em> gemacht werden —
 * „Den Workspace-Stream als {@code workspace_log.jsonl} mitschreiben (read-only)".
 *
 * <p>Dieser Logger verändert <b>nichts</b> am Verhalten. Er hängt pro Broadcast
 * eine JSON-Zeile (JSONL) an eine Datei an, damit Georg/Prometheus offline
 * auswerten können, ob die Aufmerksamkeitskonkurrenz kohärente Inhalte
 * priorisiert (und ob „Attention Hijacking" auftritt — eine der Modell-Warnungen).
 *
 * <p>Eigenschaften:
 * <ul>
 *   <li>Append-only, eine JSON-Zeile pro Broadcast.</li>
 *   <li>Best-effort: I/O-Fehler werden geloggt, nie propagiert.</li>
 *   <li>Größen-Cap pro Zeile, damit ein Ausreißer-Item die Datei nicht sprengt.</li>
 *   <li>Pfad via System-Property {@code metis.workspace.shadow.path}.</li>
 * </ul>
 *
 * <p>Rein deterministisch, nur File-I/O — daher im Kernel (analog
 * {@code SelfNarrative}). Keine LLM-Calls.
 */
public final class WorkspaceShadowLogger {

    private static final Logger LOG = Logger.getLogger(WorkspaceShadowLogger.class.getName());

    private static final String DEFAULT_PATH =
            System.getProperty("metis.workspace.shadow.path",
                    "/home/prometheus/metis/workspace_log.jsonl");

    /** Max Zeichen pro JSON-Zeile (Schutz vor Runaway-Content). */
    private static final int MAX_LINE_CHARS = 8 * 1024;

    private final Path file;
    private volatile boolean enabled = true;
    private long written = 0;

    public WorkspaceShadowLogger() {
        this(Path.of(DEFAULT_PATH));
    }

    public WorkspaceShadowLogger(Path file) {
        this.file = file;
        ensureParent();
    }

    private void ensureParent() {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
        } catch (IOException e) {
            LOG.warning("WorkspaceShadowLogger: cannot create dir, disabling: " + e.getMessage());
            enabled = false;
        }
    }

    /**
     * Hängt einen Broadcast als JSONL-Zeile an. Best-effort.
     *
     * @param broadcast Gewinner-Items des aktuellen Broadcasts
     * @param entropy   normalisierte Attention-Entropie (0..1), zum Hijacking-Monitoring
     * @param stuck     true, wenn die Aufmerksamkeit „festhängt"
     */
    public void log(List<ContentItem> broadcast, double entropy, boolean stuck) {
        if (!enabled || broadcast == null) return;
        try {
            String line = render(broadcast, entropy, stuck);
            if (line.length() > MAX_LINE_CHARS) {
                line = line.substring(0, MAX_LINE_CHARS - 2) + "\"}";
            }
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            written++;
        } catch (Exception e) {
            // Schattenmodus darf den Agenten niemals stören.
            LOG.fine("WorkspaceShadowLogger append failed (non-fatal): " + e.getMessage());
        }
    }

    private static String render(List<ContentItem> broadcast, double entropy, boolean stuck) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        sb.append("\"ts\":\"").append(Instant.now()).append('"');
        sb.append(",\"entropy\":").append(fmt(entropy));
        sb.append(",\"stuck\":").append(stuck);
        sb.append(",\"n\":").append(broadcast.size());
        ContentItem focus = broadcast.isEmpty() ? null : broadcast.get(0);
        if (focus != null) {
            sb.append(",\"focus_source\":\"").append(esc(focus.source())).append('"');
            sb.append(",\"focus\":\"").append(esc(focus.summary())).append('"');
            sb.append(",\"focus_score\":").append(fmt(focus.attentionScore()));
        }
        sb.append(",\"items\":[");
        for (int i = 0; i < broadcast.size(); i++) {
            ContentItem c = broadcast.get(i);
            if (i > 0) sb.append(',');
            sb.append('{')
              .append("\"source\":\"").append(esc(c.source())).append('"')
              .append(",\"summary\":\"").append(esc(c.summary())).append('"')
              .append(",\"score\":").append(fmt(c.attentionScore()))
              .append(",\"salience\":").append(fmt(c.salience()))
              .append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.3f", d);
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public long written() { return written; }
    public Path file() { return file; }
}
