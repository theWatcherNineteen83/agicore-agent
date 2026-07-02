package de.metis.kernel.self;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Phase8NarrativeSelfTest {

    @Test
    void episodeRecordEnforcesInvariants() {
        Instant t0 = Instant.now();
        Instant t1 = t0.plusSeconds(60);
        Episode ok = new Episode("e1", t0, t1, "x", "y",
                List.of(), List.of(), List.of(), List.of(), "", Map.of(),
                10, 5, 3, 1, null, null);
        assertEquals("e1", ok.id());
        assertEquals("GENESIS", ok.previousHash());

        assertThrows(IllegalArgumentException.class,
                () -> new Episode(null, t0, t1, "x", "y",
                        List.of(), List.of(), List.of(), List.of(), "", Map.of(),
                        1, 0, 0, 0, "G", "h"));
        assertThrows(IllegalArgumentException.class,
                () -> new Episode("e1", t1, t0, "x", "y",
                        List.of(), List.of(), List.of(), List.of(), "", Map.of(),
                        1, 0, 0, 0, "G", "h"));
    }

    @Test
    void episodicMemoryAppendsAndChains(@TempDir Path tmp) {
        Path file = tmp.resolve("episodes.jsonl");
        EpisodicMemory em = new EpisodicMemory(file);
        Instant t0 = Instant.now();

        Episode e1 = em.append(makeEp("e1", t0, t0.plusSeconds(60)));
        Episode e2 = em.append(makeEp("e2", t0.plusSeconds(60), t0.plusSeconds(120)));

        assertNotNull(e1);
        assertNotNull(e2);
        assertEquals("GENESIS", e1.previousHash());
        assertEquals(e1.hash(), e2.previousHash());
        assertNotEquals(e1.hash(), e2.hash());

        EpisodicMemory reload = new EpisodicMemory(file);
        assertEquals(2, reload.size());
        assertEquals(e2.hash(), reload.chainHead());
        assertTrue(reload.verify());
    }

    private static Episode makeEp(String id, Instant start, Instant end) {
        return new Episode(id, start, end, id + " title", id + " body",
                List.of("event"), List.of("insight"), List.of("question"),
                List.of("Georg"), "265324594", Map.of("satisfaction", 0.8),
                10, 5, 3, 1, null, null);
    }

    @Test
    void selfNarrativeAppendsAndTruncates(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("self.md");
        SelfNarrative sn = new SelfNarrative(file);
        sn.append("test", "Heute war ein guter Tag.");
        sn.append("test", "Morgen lerne ich Phase 9.");
        String body = Files.readString(file);
        assertTrue(body.contains("guter Tag"));
        assertTrue(body.contains("Phase 9"));
        assertTrue(body.contains("## "));  // markdown header per entry
    }

    @Test
    void moodSignalEMAStaysInBounds() {
        MoodSignal m = new MoodSignal();
        for (int i = 0; i < 200; i++) {
            m.update(null, 0.9, 1.0, 0.5, 0.7);
        }
        var snap = m.snapshot();
        assertTrue(snap.get("satisfaction") > 0.7);
        assertTrue(snap.get("curiosity") <= 1.0);
        assertTrue(snap.get("energy") <= 1.0);
        assertNotNull(m.label());
        assertTrue(m.label().length() > 0);
    }

    @Test
    void personalityAnchorSelfSeedsAndPinsHash(@TempDir Path tmp) {
        Path file = tmp.resolve("anchor.md");
        Path hash = tmp.resolve("anchor.sha256");
        PersonalityAnchor pa = new PersonalityAnchor(file, hash);
        assertFalse(pa.isTampered());
        assertNotNull(pa.expectedHash());
        assertTrue(pa.text().contains("Metis"));
        // second load with same files: still verified
        PersonalityAnchor pa2 = new PersonalityAnchor(file, hash);
        assertFalse(pa2.isTampered());
        assertEquals(pa.expectedHash(), pa2.expectedHash());
    }

    @Test
    void personalityAnchorDetectsTampering(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("anchor.md");
        Path hash = tmp.resolve("anchor.sha256");
        PersonalityAnchor pa = new PersonalityAnchor(file, hash);
        // Tamper: overwrite content
        Files.writeString(file, "EVIL CONTENT");
        PersonalityAnchor pa2 = new PersonalityAnchor(file, hash);
        assertTrue(pa2.isTampered(), "tampering must be detected");
    }

    @Test
    void dreamConsolidationProducesEpisodeAndNarrative(@TempDir Path tmp) throws Exception {
        EpisodicMemory em = new EpisodicMemory(tmp.resolve("e.jsonl"));
        SelfNarrative sn = new SelfNarrative(tmp.resolve("s.md"));
        MoodSignal mood = new MoodSignal();
        mood.update(null, 0.95, 1.0, 0.4, 0.8);

        DreamConsolidation dc = new DreamConsolidation(em, sn, mood);
        Instant end = Instant.now();
        Instant start = end.minusSeconds(3600 * 12);
        Episode ep = dc.consolidate(new DreamConsolidation.DayStats(
                start, end, 1000, 250, 30, 5, 0.86, 1.0,
                List.of("HA-Sensor flackerte um 03:14"),
                List.of("Warum verzögerte sich der Wiki-Feed?"),
                List.of("Georg"),
                null
        ));
        assertNotNull(ep);
        assertEquals(1, em.size());
        String body = Files.readString(sn.file());
        assertTrue(body.contains("Heute habe ich"));
        assertTrue(body.contains("Episode-Hash"));
    }
}
