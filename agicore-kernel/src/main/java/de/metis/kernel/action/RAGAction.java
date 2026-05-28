package de.metis.kernel.action;

import de.metis.kernel.embedding.EmbeddingProvider;
import de.metis.kernel.rag.DocumentChunker;
import de.metis.kernel.rag.RAGService;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * RAG (Retrieval-Augmented Generation) action for document ingestion and hybrid search.
 * <p>
 * <b>Modes:</b>
 * <ul>
 *   <li><b>ingest</b> — chunk a document, generate embeddings, index for search</li>
 *   <li><b>search</b> — hybrid semantic+keyword search over indexed documents</li>
 *   <li><b>stats</b> — return RAG index statistics</li>
 * </ul>
 * <p>
 * Uses the full RAG pipeline: DocumentChunker → EmbeddingProvider → PersistentVectorIndex
 * → HybridSearchService (BM25 + cosine similarity). Results are persisted to disk.
 *
 * <h3>Usage</h3>
 * <pre>
 * // Ingest a document
 * new RAGAction(embeddingProvider, storagePath, "ingest", "tech-report", "Long document text...").execute();
 *
 * // Search
 * new RAGAction(embeddingProvider, storagePath, "search", null, "how to optimize").execute();
 * </pre>
 */
public class RAGAction implements Action {

    private static final Logger LOG = Logger.getLogger(RAGAction.class.getName());

    private final RAGService ragService;
    private final String mode;
    private final String documentId;
    private final String content;
    private final int maxResults;

    /**
     * Create a RAG action.
     *
     * @param embeddingProvider for generating embeddings
     * @param storagePath       path to persist the vector index
     * @param mode              "ingest", "search", or "stats"
     * @param documentId        document identifier (for "ingest" mode) or null
     * @param content           document text for "ingest" or search query for "search"
     */
    public RAGAction(EmbeddingProvider embeddingProvider, Path storagePath,
                     String mode, String documentId, String content) {
        this(embeddingProvider, storagePath, mode, documentId, content, 5);
    }

    /**
     * Create a RAG action with custom settings.
     */
    public RAGAction(EmbeddingProvider embeddingProvider, Path storagePath,
                     String mode, String documentId, String content,
                     int maxResults) {
        this.ragService = new RAGService(embeddingProvider, storagePath,
                new DocumentChunker(512, 64, DocumentChunker.ChunkStrategy.SENTENCE));
        this.mode = mode;
        this.documentId = documentId;
        this.content = content;
        this.maxResults = maxResults;
    }

    @Override
    public String name() {
        return "rag-" + mode;
    }

    @Override
    public String category() {
        return "ingest".equals(mode) ? "write" : "read";
    }

    @Override
    public ActionResult execute() {
        Instant start = Instant.now();
        try {
            return switch (mode) {
                case "ingest" -> doIngest(start);
                case "search" -> doSearch(start);
                case "stats" -> doStats(start);
                default -> ActionResult.fail(name(), "Unknown mode: " + mode, start);
            };
        } catch (Exception e) {
            LOG.warning("RAG action failed: " + e.getMessage());
            return ActionResult.fail(name(), e.getMessage(), start);
        }
    }

    private ActionResult doIngest(Instant start) {
        if (documentId == null || content == null) {
            return ActionResult.fail(name(), "ingest requires documentId and content", start);
        }
        int chunks = ragService.ingest(documentId, content);
        ragService.save();
        String body = "Ingested '" + documentId + "': " + chunks + " chunks ("
                + content.length() + " chars)";
        LOG.info(body);
        return ActionResult.ok(name(), body, start);
    }

    private ActionResult doSearch(Instant start) {
        if (content == null || content.isBlank()) {
            return ActionResult.fail(name(), "search requires a query", start);
        }
        List<de.metis.kernel.rag.HybridSearchService.ScoredResult> results =
                ragService.search(content, maxResults);

        StringBuilder sb = new StringBuilder();
        sb.append("Search '").append(content).append("' → ").append(results.size()).append(" results:\n");
        for (var r : results) {
            sb.append("  [score=").append(String.format("%.3f", r.score()))
                    .append("] ").append(r.key()).append("\n");
        }

        if (results.isEmpty()) {
            sb.append("  (no results)");
        }

        String body = sb.toString();
        LOG.fine(body);
        return ActionResult.ok(name(), body, start);
    }

    private ActionResult doStats(Instant start) {
        Map<String, Object> stats = ragService.stats();
        StringBuilder sb = new StringBuilder();
        sb.append("RAG Index Stats:\n");
        for (var entry : stats.entrySet()) {
            sb.append("  ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        return ActionResult.ok(name(), sb.toString(), start);
    }
}
