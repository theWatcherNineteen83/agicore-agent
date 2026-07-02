package de.metis.kernel.self;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SystemPromptBuilderTest {

    @Test
    void handlesAllNullComponents() {
        SystemPromptBuilder b = new SystemPromptBuilder(null, null, null, null);
        assertEquals("", b.buildPromptHeader());
        assertEquals("BASE", b.wrap("BASE"));
        assertEquals("", b.wrap(null));
    }

    @Test
    void includesAnchorTextWhenPresent(@TempDir Path tmp) {
        PersonalityAnchor a = new PersonalityAnchor(
                tmp.resolve("anchor.md"), tmp.resolve("anchor.sha"));
        SystemPromptBuilder b = new SystemPromptBuilder(a, null, null, null);
        String header = b.buildPromptHeader();
        assertTrue(header.contains("UNVERÄNDERLICHER KERN"));
        assertTrue(header.contains("Metis"));
        assertFalse(header.contains("MANIPULIERT"));
    }

    @Test
    void warnsWhenAnchorTampered(@TempDir Path tmp) throws Exception {
        PersonalityAnchor a = new PersonalityAnchor(
                tmp.resolve("anchor.md"), tmp.resolve("anchor.sha"));
        // Tamper, then load again
        java.nio.file.Files.writeString(a.file(), "EVIL");
        PersonalityAnchor tampered = new PersonalityAnchor(
                a.file(), tmp.resolve("anchor.sha"));
        SystemPromptBuilder b = new SystemPromptBuilder(tampered, null, null, null);
        assertTrue(b.buildPromptHeader().contains("MANIPULIERT"));
    }

    @Test
    void includesMoodLabelAndAxes() {
        MoodSignal m = new MoodSignal();
        m.update(null, 0.95, 1.0, 0.7, 0.8);
        SystemPromptBuilder b = new SystemPromptBuilder(null, null, m, null);
        String header = b.buildPromptHeader();
        assertTrue(header.contains("STIMMUNG"));
        assertTrue(header.contains("energy="));
        assertTrue(header.contains("satisfaction="));
    }

    @Test
    void includesRecentEpisodes(@TempDir Path tmp) {
        EpisodicMemory em = new EpisodicMemory(tmp.resolve("e.jsonl"));
        Instant t0 = Instant.now();
        em.append(new Episode("e1", t0, t0.plusSeconds(60),
                "Tag A", "body A",
                List.of(), List.of(), List.of(), List.of(), "", Map.of(),
                100, 25, 5, 1, null, null));
        em.append(new Episode("e2", t0.plusSeconds(60), t0.plusSeconds(120),
                "Tag B", "body B",
                List.of(), List.of(), List.of(), List.of(), "", Map.of(),
                200, 50, 10, 2, null, null));
        SystemPromptBuilder b = new SystemPromptBuilder(null, null, null, em);
        String header = b.buildPromptHeader();
        assertTrue(header.contains("LETZTE EPISODEN"));
        assertTrue(header.contains("Tag A"));
        assertTrue(header.contains("Tag B"));
    }

    @Test
    void wrapsAroundBasePrompt(@TempDir Path tmp) {
        PersonalityAnchor a = new PersonalityAnchor(
                tmp.resolve("anchor.md"), tmp.resolve("anchor.sha"));
        SystemPromptBuilder b = new SystemPromptBuilder(a, null, null, null);
        String wrapped = b.wrap("YOU ARE EDI");
        assertTrue(wrapped.startsWith("YOU ARE EDI"));
        assertTrue(wrapped.contains("UNVERÄNDERLICHER KERN"));
    }
}
