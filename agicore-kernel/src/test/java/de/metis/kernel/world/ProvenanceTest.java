package de.metis.kernel.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests für {@link Provenance} (Phase 12e Baustein 1) — die Provenienz-Taxonomie.
 */
class ProvenanceTest {

    @Test
    void nullAndBlankAreUnknown() {
        assertEquals(Provenance.UNKNOWN, Provenance.classify(null));
        assertEquals(Provenance.UNKNOWN, Provenance.classify(""));
        assertEquals(Provenance.UNKNOWN, Provenance.classify("   "));
    }

    @Test
    void userSourcesClassifyAsUser() {
        assertEquals(Provenance.USER, Provenance.classify("user"));
        assertEquals(Provenance.USER, Provenance.classify("georg"));
        assertEquals(Provenance.USER, Provenance.classify("human"));
    }

    @Test
    void observationSourcesClassifyAsObserved() {
        assertEquals(Provenance.OBSERVED, Provenance.classify("observation"));
        assertEquals(Provenance.OBSERVED, Provenance.classify("sensor"));
        assertEquals(Provenance.OBSERVED, Provenance.classify("verified"));
        assertEquals(Provenance.OBSERVED, Provenance.classify("gps"));
    }

    @Test
    void inferenceSourcesClassifyAsInferred() {
        assertEquals(Provenance.INFERRED, Provenance.classify("inference"));
        assertEquals(Provenance.INFERRED, Provenance.classify("llm"));
        assertEquals(Provenance.INFERRED, Provenance.classify("hypothesis"));
    }

    @Test
    void quotedSourcesClassifyAsQuoted() {
        assertEquals(Provenance.QUOTED, Provenance.classify("sutta"));
        assertEquals(Provenance.QUOTED, Provenance.classify("wiki"));
        assertEquals(Provenance.QUOTED, Provenance.classify("marketing"));
    }

    @Test
    void unrecognizedSourceIsUnknownNotGuessed() {
        // Whitaker-Prinzip: Unbekanntes nicht als belegt behaupten.
        assertEquals(Provenance.UNKNOWN, Provenance.classify("xyz-unknown-thing"));
        assertEquals(Provenance.UNKNOWN, Provenance.classify("bootstrap"));
    }

    @Test
    void beliefExposesProvenance() {
        Belief observed = new Belief("miniedi erreichbar", 0.8, "observation");
        assertEquals(Provenance.OBSERVED, observed.provenance());

        Belief inferred = new Belief("morgen wird es regnen", 0.5, "inference");
        assertEquals(Provenance.INFERRED, inferred.provenance());

        Belief fromUser = new Belief("Georg mag direkte Kommunikation", 0.9, "user");
        assertEquals(Provenance.USER, fromUser.provenance());

        Belief legacy = new Belief("irgendwas altes", 0.4, "legacy-unknown");
        assertEquals(Provenance.UNKNOWN, legacy.provenance());
    }

    @Test
    void provenanceSurvivesReinforceAndWeaken() {
        Belief b = new Belief("belegter Fakt", 0.7, "sensor");
        assertEquals(Provenance.OBSERVED, b.reinforce().provenance());
        assertEquals(Provenance.OBSERVED, b.weaken().provenance());
    }
}
