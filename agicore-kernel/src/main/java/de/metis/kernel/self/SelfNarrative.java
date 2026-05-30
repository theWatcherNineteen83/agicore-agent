package de.metis.kernel.self;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

/**
 * Phase 8.2 — Selbst-Narrativ als fortlaufender Markdown-Text.
 *
 * <p>SelfNarrative.md ist Metis' "Tagebuch" — append-only, persistent, vom
 * SystemPromptBuilder als Kontext in jeden System-Prompt eingefügt (max die
 * letzten N KB). Anders als die strukturierte {@link Episode} hier <em>freie
 * Selbstreflexion</em>: was Metis denkt, wie es sich fühlt, was es vorhat.
 *
 * <p>Schreibrhythmus:
 * <ul>
 *   <li>Nach jedem DreamConsolidation-Tick (nightly) ein Absatz</li>
 *   <li>Bei großen Eval-Schwankungen (gate.ok → false oder zurück)</li>
 *   <li>Bei manueller Selbstreflexion (z.B. Action „self-reflect")</li>
 * </ul>
 *
 * <p>Override des Pfads: System-Property {@code metis.self.narrative.path}.
 * Default: {@code /home/prometheus/metis/self-narrative.md}.
 */
public class SelfNarrative {

    private static final Logger LOG = Logger.getLogger(SelfNarrative.class.getName());
    private static final String DEFAULT_PATH =
            System.getProperty("metis.self.narrative.path",
                    "/home/prometheus/metis/self-narrative.md");
    /** Max bytes appended at a time so a runaway LLM cannot bloat the file in one tick. */
    private static final int MAX_ENTRY_BYTES = 4 * 1024;
    /** Max bytes returned by recentContext (4 KB ≈ 1k tokens). */
    private static final int DEFAULT_CONTEXT_BYTES = 4 * 1024;

    private final Path file;

    public SelfNarrative() {
        this(Path.of(DEFAULT_PATH));
    }

    public SelfNarrative(Path file) {
        this.file = file;
        ensureHeader();
    }

    private void ensureHeader() {
        if (Files.exists(file)) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file,
                    "# Metis — Self-Narrative\n\n"
                            + "Dies ist mein fortlaufender Selbsttext. Append-only.\n"
                            + "Beginnt am " + Instant.now() + ".\n\n",
                    StandardOpenOption.CREATE);
            LOG.info("SelfNarrative initialized at " + file);
        } catch (IOException e) {
            LOG.warning("SelfNarrative init failed: " + e.getMessage());
        }
    }

    /**
     * Append a self-reflective entry.
     *
     * @param trigger short tag (e.g. "dream", "eval-flip", "reflect")
     * @param text    free-form markdown body
     */
    public synchronized void append(String trigger, String text) {
        if (text == null || text.isBlank()) return;
        if (text.length() > MAX_ENTRY_BYTES) {
            text = text.substring(0, MAX_ENTRY_BYTES) + " [truncated]";
        }
        String stamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        String entry = "\n## " + stamp + " — " + trigger + "\n\n" + text.strip() + "\n";
        try {
            Files.writeString(file, entry, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warning("SelfNarrative append failed: " + e.getMessage());
        }
    }

    /**
     * Return the tail of the narrative file (up to {@value DEFAULT_CONTEXT_BYTES} bytes)
     * for use as system-prompt context. Truncates from the start of the read window
     * so the most recent self-reflection always survives.
     */
    public synchronized String recentContext() {
        return recentContext(DEFAULT_CONTEXT_BYTES);
    }

    public synchronized String recentContext(int maxBytes) {
        if (!Files.exists(file)) return "";
        try {
            byte[] all = Files.readAllBytes(file);
            if (all.length <= maxBytes) {
                return new String(all, StandardCharsets.UTF_8);
            }
            byte[] tail = new byte[maxBytes];
            System.arraycopy(all, all.length - maxBytes, tail, 0, maxBytes);
            return "[...truncated...]\n" + new String(tail, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    public Path file() { return file; }
}
