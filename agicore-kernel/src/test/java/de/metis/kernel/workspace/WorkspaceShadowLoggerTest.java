package de.metis.kernel.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 7.x — WorkspaceShadowLogger.
 *
 * <p>Read-only Schattenmodus: schreibt JSONL, ändert nichts, schluckt Fehler.
 * Kein Netz, kein LLM.
 */
class WorkspaceShadowLoggerTest {

    @Test
    void writesOneJsonLinePerBroadcast(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("workspace_log.jsonl");
        var logger = new WorkspaceShadowLogger(log);
        assertTrue(logger.isEnabled());

        var items = List.of(
                new ContentItem("goal", "Wikipedia lernen", 0.8, 0.5, 0.7, "body-a"),
                new ContentItem("self", "Erfolgsquote stabil", 0.6, 0.3, 0.4, "body-b"));

        logger.log(items, 0.42, false);
        logger.log(items, 0.10, true);

        assertEquals(2, logger.written());
        List<String> lines = Files.readAllLines(log);
        // Header gibt es nicht — genau 2 JSONL-Zeilen
        assertEquals(2, lines.size());

        String first = lines.get(0);
        assertTrue(first.startsWith("{") && first.endsWith("}"));
        assertTrue(first.contains("\"focus_source\":\"goal\""));
        assertTrue(first.contains("\"focus\":\"Wikipedia lernen\""));
        assertTrue(first.contains("\"n\":2"));
        assertTrue(first.contains("\"entropy\":0.420"));
        assertTrue(first.contains("\"stuck\":false"));

        assertTrue(lines.get(1).contains("\"stuck\":true"));
    }

    @Test
    void emptyOrNullBroadcastIsIgnoredGracefully(@TempDir Path dir) {
        var logger = new WorkspaceShadowLogger(dir.resolve("x.jsonl"));
        logger.log(null, 0.0, false);          // null → no-op
        logger.log(List.of(), 0.0, false);     // empty → still writes a line (n:0) by design
        // null darf nicht schreiben:
        assertTrue(logger.written() <= 1);
    }

    @Test
    void escapesQuotesAndNewlinesInSummary(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("esc.jsonl");
        var logger = new WorkspaceShadowLogger(log);
        var items = List.of(
                new ContentItem("external", "sagte \"hallo\"\nund tschüss", 0.5, 0.5, 0.5, "b"));
        logger.log(items, 0.0, false);

        String line = Files.readString(log).strip();
        assertTrue(line.contains("\\\"hallo\\\""), "Quotes müssen escaped sein");
        assertTrue(line.contains("\\n"), "Newline muss escaped sein");
        // genau eine JSONL-Zeile: kein echtes Newline im (gestrippten) Inhalt
        assertFalse(line.contains("\n"), "keine echten Newlines in der JSONL-Zeile");
    }

    @Test
    void disabledLoggerWritesNothing(@TempDir Path dir) {
        var logger = new WorkspaceShadowLogger(dir.resolve("d.jsonl"));
        logger.setEnabled(false);
        logger.log(List.of(new ContentItem("goal", "x", 0.5, 0.5, 0.5, "y")), 0.0, false);
        assertEquals(0, logger.written());
    }
}
