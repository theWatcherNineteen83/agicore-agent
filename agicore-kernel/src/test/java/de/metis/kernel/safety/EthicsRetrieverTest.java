package de.metis.kernel.safety;

import de.metis.kernel.world.Belief;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EthicsRetrieverTest {

    private final List<Belief> sample = List.of(
            new Belief("Mitgefuehl ist der Anfang.", 0.9, "ethics:dhammapada#1"),
            new Belief("Hass wird durch Liebe besiegt.", 0.95, "ethics:dhammapada#5"),
            new Belief("Moege ich frei von Leid sein.", 0.8, "ethics:metta_sutta#3"),
            new Belief("ADS-B liefert ICAO-Codes.", 0.6, "observation:adsb"),
            new Belief("HTTP 200 ist OK.", 0.99, "observation:status")
    );

    private final EthicsRetriever retriever = new EthicsRetriever(() -> sample);

    @Test
    void allEthicsBeliefs_filtersByPrefix() {
        var ethics = retriever.allEthicsBeliefs();
        assertEquals(3, ethics.size());
        for (var b : ethics) {
            assertTrue(b.source().startsWith("ethics:"));
        }
    }

    @Test
    void count_returnsThree() {
        assertEquals(3, retriever.count());
    }

    @Test
    void bySource_groupsCorrectly() {
        Map<String, List<Belief>> grouped = retriever.bySource();
        assertEquals(2, grouped.size());
        assertTrue(grouped.containsKey("dhammapada"));
        assertTrue(grouped.containsKey("metta_sutta"));
        assertEquals(2, grouped.get("dhammapada").size());
        assertEquals(1, grouped.get("metta_sutta").size());
    }

    @Test
    void topRelevant_ranksByConfidence() {
        var hits = retriever.topRelevant("Hass", 2);
        assertEquals(1, hits.size());
        assertEquals("Hass wird durch Liebe besiegt.", hits.get(0).statement());
    }

    @Test
    void topRelevant_emptyKeyword_returnsEmpty() {
        assertTrue(retriever.topRelevant("", 5).isEmpty());
        assertTrue(retriever.topRelevant(null, 5).isEmpty());
    }

    @Test
    void topRelevant_zeroK_returnsEmpty() {
        assertTrue(retriever.topRelevant("Hass", 0).isEmpty());
    }

    @Test
    void subSourceOf_handlesAllVariants() {
        assertEquals("dhammapada", EthicsRetriever.subSourceOf("ethics:dhammapada#1"));
        assertEquals("metta_sutta", EthicsRetriever.subSourceOf("ethics:metta_sutta"));
        assertEquals("observation:adsb", EthicsRetriever.subSourceOf("observation:adsb"));
        assertEquals("(unknown)", EthicsRetriever.subSourceOf(null));
    }

    @Test
    void emptyStore_returnsEmptyResults() {
        var empty = new EthicsRetriever(List::of);
        assertEquals(0, empty.count());
        assertTrue(empty.allEthicsBeliefs().isEmpty());
        assertTrue(empty.bySource().isEmpty());
    }
}
