package de.metis.kernel.rag;

import de.metis.kernel.embedding.EmbeddingProvider;
import de.metis.kernel.embedding.InMemoryVectorIndex;
import de.metis.kernel.embedding.VectorIndex;

import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

/**
 * High-level RAG service: chunk → embed → index → hybrid search.
 * <p>
 * Manages the full pipeline:
 * <ol>
 *   <li>Document ingestion: chunk text, embed chunks, index for search</li>
 *   <li>Hybrid retrieval: semantic + keyword fused search</li>
 *   <li>Persistence: save/load vector index to disk</li>
 * </ol>
 * <p>
 * Usage:
 * <pre>{@code
 * RAGService rag = new RAGService(embeddingProvider, storagePath);
 * rag.ingest("doc1", "long document text...");
 * List<ScoredResult> results = rag.search("query", 5);
 * }</pre>
 */
public class RAGService {

    private static final Logger LOG = Logger.getLogger(RAGService.class.getName());

    private final EmbeddingProvider embeddingProvider;
    private final DocumentChunker chunker;
    private final HybridSearchService searchService;
    private final PersistentVectorIndex persistentIndex;
    private final Path storagePath;

    private final Map<String, List<String>> documentChunks = new HashMap<>(); // docId → chunk keys

    /**
     * Create a RAG service with default settings.
     */
    public RAGService(EmbeddingProvider embeddingProvider, Path storagePath) {
        this(embeddingProvider, storagePath,
                new DocumentChunker(512, 64, DocumentChunker.ChunkStrategy.SENTENCE));
    }

    /**
     * Create a RAG service with custom chunker settings.
     */
    public RAGService(EmbeddingProvider embeddingProvider, Path storagePath,
                      DocumentChunker chunker) {
        this.embeddingProvider = embeddingProvider;
        this.storagePath = storagePath;
        this.chunker = chunker;

        VectorIndex inMemory = new InMemoryVectorIndex();
        this.persistentIndex = new PersistentVectorIndex(inMemory, storagePath, false);
        this.searchService = new HybridSearchService(embeddingProvider, persistentIndex);

        // Load existing vectors from disk
        int loaded = persistentIndex.load();
        if (loaded > 0) {
            LOG.info("Loaded " + loaded + " existing vectors from " + storagePath);
        }
    }

    /**
     * Ingest a document: chunk it, embed chunks, index for search.
     *
     * @param documentId unique document identifier
     * @param text       full document text
     * @return number of chunks indexed
     */
    public int ingest(String documentId, String text) {
        List<DocumentChunker.Chunk> chunks = chunker.chunk(documentId, text);

        List<String> chunkKeys = new ArrayList<>();
        for (var chunk : chunks) {
            searchService.index(chunk.id(), chunk.text());
            chunkKeys.add(chunk.id());
        }

        documentChunks.put(documentId, chunkKeys);
        LOG.info("Ingested document '" + documentId + "': " + chunks.size() +
                " chunks (" + text.length() + " chars)");

        return chunks.size();
    }

    /**
     * Remove a document and all its chunks from the index.
     */
    public void remove(String documentId) {
        List<String> chunkKeys = documentChunks.remove(documentId);
        if (chunkKeys != null) {
            for (String key : chunkKeys) {
                searchService.remove(key);
            }
            LOG.info("Removed document '" + documentId + "' (" + chunkKeys.size() + " chunks)");
        }
    }

    /**
     * Hybrid search: semantic + keyword.
     *
     * @param query      the search query
     * @param maxResults maximum results to return
     * @return scored results, sorted by relevance
     */
    public List<HybridSearchService.ScoredResult> search(String query, int maxResults) {
        return searchService.search(query, maxResults);
    }

    /**
     * Save the vector index to disk.
     */
    public void save() {
        persistentIndex.save();
    }

    /**
     * Get statistics about the index.
     */
    public Map<String, Object> stats() {
        return Map.of(
                "documents", documentChunks.size(),
                "vectors", persistentIndex.size(),
                "keywordDocs", searchService.keywordSize(),
                "chunkSize", chunker.chunkSize(),
                "chunkOverlap", chunker.chunkOverlap(),
                "alpha", searchService.getAlpha()
        );
    }

    public void setAlpha(double alpha) { searchService.setAlpha(alpha); }
    public int documentCount() { return documentChunks.size(); }
    public int chunkCount() { return persistentIndex.size(); }
    public Path storagePath() { return storagePath; }
}
