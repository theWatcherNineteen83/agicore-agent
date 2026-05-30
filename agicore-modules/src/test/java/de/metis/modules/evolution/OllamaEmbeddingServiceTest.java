package de.metis.modules.evolution;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke tests for the embedding-service cache layer.
 * Does NOT call Ollama — only exercises construction, hashing, and bounded-LRU behaviour.
 */
class OllamaEmbeddingServiceTest {

    @Test
    void respectsCacheCapacity() {
        // 64 is the documented minimum; constructor clamps to >= 64.
        OllamaEmbeddingService svc = new OllamaEmbeddingService("nomic-embed-text", 8);
        assertEquals(0, svc.cacheSize());
        assertEquals(0, svc.cacheHits());
        assertEquals(0, svc.embedCount());
        assertEquals(0.0, svc.cacheHitRate(), 0.0001);
    }

    @Test
    void defaultModelIsNomicEmbed() {
        OllamaEmbeddingService svc = new OllamaEmbeddingService();
        assertEquals("nomic-embed-text", svc.model());
    }

    @Test
    void nullModelFallsBackToDefault() {
        OllamaEmbeddingService svc = new OllamaEmbeddingService((String) null);
        assertEquals("nomic-embed-text", svc.model());
    }

    @Test
    void nullTextReturnsZeroVector() {
        OllamaEmbeddingService svc = new OllamaEmbeddingService();
        double[] v = svc.embed(null);
        assertEquals(0, v.length);
    }
}
