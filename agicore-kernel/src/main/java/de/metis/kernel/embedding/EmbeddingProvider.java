package de.metis.kernel.embedding;

/**
 * Provider for text embeddings — used by WorldModel for semantic belief retrieval.
 * <p>
 * Simple interface: given text, return a vector representation.
 * Implementations can call Ollama, local models, or be mock-based.
 */
@FunctionalInterface
public interface EmbeddingProvider {
    /**
     * Generate an embedding vector for the given text.
     * @return normalized vector, or null if unavailable
     */
    double[] embed(String text);
}
