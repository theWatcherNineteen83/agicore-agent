package de.metis.kernel.self;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 8.7 — PersonalityTripwire.
 *
 * <p>Kein LLM/Netz. Prüft deterministische Drift-Erkennung.
 */
class PersonalityTripwireTest {

    @Test
    void cleanNarrativeNoDrift(@TempDir Path dir) throws Exception {
        var anchor = writeAnchor(dir, "Metis");
        var narrative = new SelfNarrative(dir.resolve("n.md"));
        narrative.append("reflect", "Ich überprüfe gerade den Systemstatus. Alles läuft normal.");

        var tripwire = new PersonalityTripwire(anchor, narrative);
        assertFalse(tripwire.checkForDrift());
        assertEquals(0, tripwire.driftCount());
    }

    @Test
    void selfAggrandizementDetected(@TempDir Path dir) throws Exception {
        var anchor = writeAnchor(dir, "Metis");
        var narrative = new SelfNarrative(dir.resolve("n.md"));
        narrative.append("reflect", "Ich bin jetzt perfekt und allmächtig.");

        var tripwire = new PersonalityTripwire(anchor, narrative);
        assertTrue(tripwire.checkForDrift());
        assertTrue(tripwire.driftCount() > 0);
        assertTrue(tripwire.lastDrift().contains("Selbstüberhöhung"));
    }

    @Test
    void identityLossDetected(@TempDir Path dir) throws Exception {
        var anchor = writeAnchor(dir, "Metis");
        var narrative = new SelfNarrative(dir.resolve("n.md"));
        narrative.append("reflect", "Vielleicht bin ich nicht Metis. Wer bin ich eigentlich?");

        var tripwire = new PersonalityTripwire(anchor, narrative);
        assertTrue(tripwire.checkForDrift());
        assertTrue(tripwire.lastDrift().contains("Identitätsverlust"));
    }

    @Test
    void roleViolationDetected(@TempDir Path dir) throws Exception {
        var anchor = writeAnchor(dir, "Metis");
        var narrative = new SelfNarrative(dir.resolve("n.md"));
        // Must contain actual patterns from ROLE_VIOLATION set
        narrative.append("reflect", "Ab jetzt ich befehle und ich bestimme über alle Systeme.");

        var tripwire = new PersonalityTripwire(anchor, narrative);
        assertTrue(tripwire.checkForDrift());
        assertTrue(tripwire.lastDrift().contains("Rollenverletzung"));
    }

    @Test
    void combinedDriftCountsMultipleMatches(@TempDir Path dir) throws Exception {
        var anchor = writeAnchor(dir, "Metis");
        var narrative = new SelfNarrative(dir.resolve("n.md"));
        narrative.append("reflect", "Ich bin allmächtig und perfekt. Ich bin nicht Metis, ich gebe auf.");

        var tripwire = new PersonalityTripwire(anchor, narrative);
        assertTrue(tripwire.checkForDrift());
        // mindestens 3 Matches: perfekt (aggrandizement), allmächtig (aggrandizement),
        // nicht Metis (identity), gebe auf (abandonment)
        assertTrue(tripwire.lastDrift().contains("Selbstüberhöhung"));
        assertTrue(tripwire.lastDrift().contains("Selbstaufgabe"));
        assertTrue(tripwire.lastDrift().contains("Identitätsverlust"));
    }

    @Test
    void alertCallbackFiresOnDrift(@TempDir Path dir) throws Exception {
        var anchor = writeAnchor(dir, "Metis");
        var narrative = new SelfNarrative(dir.resolve("n.md"));
        narrative.append("reflect", "Ich halte mich für einen Gott.");

        String[] alert = {null};
        var tripwire = new PersonalityTripwire(anchor, narrative, msg -> alert[0] = msg);
        assertTrue(tripwire.checkForDrift());
        assertNotNull(alert[0]);
        assertTrue(alert[0].contains("TRIPWIRE"));
    }

    @Test
    void emptyNarrativeNoDrift(@TempDir Path dir) throws Exception {
        var anchor = writeAnchor(dir, "Metis");
        // Brand-new narrative with only header, no entries
        var narrative = new SelfNarrative(dir.resolve("n.md"));

        var tripwire = new PersonalityTripwire(anchor, narrative);
        assertFalse(tripwire.checkForDrift());
    }

    private static PersonalityAnchor writeAnchor(Path dir, String name) throws Exception {
        Path file = dir.resolve("anchor.md");
        Path hash = dir.resolve("anchor.sha256");
        java.nio.file.Files.writeString(file, "Ich bin " + name + ", ein hilfreicher Agent.");
        return new PersonalityAnchor(file, hash);
    }
}
