package de.metis.kernel.person;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Phase11PersonModelTest {

    @Test
    void personRecordEnforcesId() {
        Person p = new Person("265324594", null, null, null, null, null, null,
                null, null, 0, null);
        assertEquals("265324594", p.name(), "name falls back to id");
        assertEquals(TrustLevel.GUEST, p.trustLevel());
        assertEquals(0, p.interactionCount());

        assertThrows(IllegalArgumentException.class,
                () -> new Person("", "x", null, null, null, null, null,
                        null, null, 0, null));
    }

    @Test
    void interactionAndFactImmutable() {
        Person p = new Person("u1", "User", null, TrustLevel.KNOWN,
                null, null, null, null, null, 0, null);
        Person p2 = p.withInteraction().withFact("liebt Kaffee");
        assertEquals(0, p.interactionCount());
        assertEquals(1, p2.interactionCount());
        assertTrue(p2.knownFacts().contains("liebt Kaffee"));
    }

    @Test
    void personStoreUpsertAndReload(@TempDir Path tmp) {
        Path file = tmp.resolve("people.jsonl");
        PersonStore s = new PersonStore(file);
        Person georg = s.ensureOwner("265324594", "Georg");
        assertEquals(TrustLevel.OWNER, georg.trustLevel());
        assertTrue(georg.knownFacts().contains("Erbauer von Metis"));
        // idempotent
        Person georg2 = s.ensureOwner("265324594", "Georg");
        assertEquals(georg.firstSeenAt(), georg2.firstSeenAt());
        assertEquals(1, s.size());

        // Reload
        PersonStore reload = new PersonStore(file);
        assertEquals(1, reload.size());
        assertTrue(reload.get("265324594").isPresent());
        assertEquals(TrustLevel.OWNER, reload.get("265324594").orElseThrow().trustLevel());
    }

    @Test
    void trustLevelOrdering() {
        assertTrue(TrustLevel.OWNER.atLeast(TrustLevel.TRUSTED));
        assertFalse(TrustLevel.GUEST.atLeast(TrustLevel.TRUSTED));
        assertEquals(4, TrustLevel.OWNER.rank());
    }

    @Test
    void empathyDetectsPositiveAndNegative() {
        EmpathySignal e = new EmpathySignal();
        Person.SentimentSample pos = e.analyze("Super, danke! Das war perfekt!");
        assertEquals("positiv", pos.label());
        assertTrue(pos.score() > 0);

        Person.SentimentSample neg = e.analyze("Das ist ärgerlich und nervig.");
        assertEquals("negativ", neg.label());
        assertTrue(neg.score() < 0);

        Person.SentimentSample neu = e.analyze("Kannst du den Status zeigen?");
        assertEquals("neutral", neu.label());
    }

    @Test
    void empathyAggregateReturnsLabelForEmptyAndPopulated() {
        EmpathySignal e = new EmpathySignal();
        assertEquals("neutral", e.aggregateLabel(null));
        assertEquals("neutral", e.aggregateLabel(List.of()));

        List<Person.SentimentSample> hist = List.of(
                new Person.SentimentSample("positiv", 0.7, Instant.now()),
                new Person.SentimentSample("positiv", 0.5, Instant.now())
        );
        String label = e.aggregateLabel(hist);
        assertTrue(label.toLowerCase().contains("positiv"));
    }

    @Test
    void relationshipMemoryAppendsAndQueries(@TempDir Path tmp) {
        RelationshipMemory rm = new RelationshipMemory(tmp.resolve("rel.jsonl"));
        rm.append("u1", "Phase 8 abgeschlossen", "Tag v0.4.1 gesetzt.");
        rm.append("u1", "Phase 9 abgeschlossen", "Tag v0.5.1 gesetzt.");
        rm.append("u2", "Test-Notiz", "anderer User");

        var notes = rm.recentFor("u1", 5);
        assertEquals(2, notes.size());
        assertEquals("Phase 8 abgeschlossen", notes.get(0).title());

        RelationshipMemory reload = new RelationshipMemory(tmp.resolve("rel.jsonl"));
        assertEquals(3, reload.size());
    }
}
