package de.metis.modules.self;

import de.metis.kernel.memory.Experience;
import de.metis.kernel.self.SelfNarrative;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 8.6 — SelfReflector.
 *
 * <p>Kein echter Ollama-Call: wir zeigen auf einen unerreichbaren Host, sodass
 * {@link SelfReflector#reflectOnce()} graceful {@code false} liefert (best-effort)
 * und keinen Narrative-Eintrag schreibt. Damit wird die Fehler-Robustheit und
 * der Idle-Guard verifiziert, nicht die LLM-Antwort.
 */
class SelfReflectorTest {

    private static final String DEAD_URL = "http://127.0.0.1:1"; // refused

    private static Experience exp(String goal, String action, boolean ok) {
        return new Experience(goal, action, ok, "body", 0.1, new double[0]);
    }

    @Test
    void emptyExperiencesProducesNoEntry(@TempDir Path dir) {
        var narrative = new SelfNarrative(dir.resolve("n.md"));
        var r = new SelfReflector(DEAD_URL, "granite4.1:3b", narrative,
                List::of, () -> 0.9);
        assertFalse(r.reflectOnce());
    }

    @Test
    void unreachableLlmIsHandledGracefully(@TempDir Path dir) {
        var narrative = new SelfNarrative(dir.resolve("n.md"));
        List<Experience> data = List.of(
                exp("Wikipedia lernen", "http", true),
                exp("VRAM prüfen", "shell", false));
        var r = new SelfReflector(DEAD_URL, "granite4.1:3b", narrative,
                () -> data, () -> 0.75);
        // LLM nicht erreichbar → kein Eintrag, aber kein Throw
        assertFalse(r.reflectOnce());
        // Narrative enthält nur den Header, keinen reflect-Eintrag
        String content = narrative.recentContext();
        assertFalse(content.contains("[reflect]"), "kein reflect-Eintrag bei totem LLM");
    }

    @Test
    void idleGuardSkipsWhenNoNewActivity(@TempDir Path dir) {
        var narrative = new SelfNarrative(dir.resolve("n.md"));
        // konstante, gleich große Liste → nach erstem Lauf gilt der Idle-Guard
        List<Experience> data = List.of(exp("idle", "noop", true));
        // minNewActivity=1, window=20 — erster Lauf setzt lastSeenCount,
        // weitere Läufe ohne Wachstum sollen skippen (false), egal ob LLM tot.
        var r = new SelfReflector(DEAD_URL, "granite4.1:3b", narrative,
                () -> data, () -> 0.5, 20, 1);
        // erster Lauf: versucht LLM (tot) → false
        assertFalse(r.reflectOnce());
        // zweiter Lauf: gleiche Größe → Idle-Guard greift → false ohne Versuch
        assertFalse(r.reflectOnce());
    }
}
