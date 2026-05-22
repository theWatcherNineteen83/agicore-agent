package de.agicore.kernel.embedding;

import java.util.List;

/**
 * Vector index abstraction — replaces linear scan in LongTermMemory.
 * <p>
 * Stores vectors with associated keys and supports:
 * <ul>
 *   <li>Insert with key + vector</li>
 *   <li>k-NN search via cosine similarity</li>
 *   <li>Remove by key</li>
 *   <li>Size query</li>
 * </ul>
 * <p>
 * The kernel depends on this interface. Implementations can be
 * in-memory (Phase 1), FAISS-backed (Phase 2), or disk-backed.
 */
public interface VectorIndex {

    /** Insert or update a vector for a key. */
    void insert(String key, double[] vector);

    /**
     * Find the top-k most similar vectors to the query.
     *
     * @param query the query vector
     * @param k     max results
     * @return list of keys sorted by similarity (descending)
     */
    List<String> search(double[] query, int k);

    /** Remove a vector by key. */
    void remove(String key);

    /** Number of indexed vectors. */
    int size();

    /** Clear all vectors. */
    void clear();
}
