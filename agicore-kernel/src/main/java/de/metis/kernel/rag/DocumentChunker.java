package de.metis.kernel.rag;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Splits documents into overlapping chunks for RAG indexing.
 * <p>
 * Strategies:
 * <ul>
 *   <li>SENTENCE — split on sentence boundaries, merge to target size</li>
 *   <li>PARAGRAPH — split on double-newline, merge to target size</li>
 *   <li>FIXED — split at fixed character intervals with overlap</li>
 * </ul>
 */
public class DocumentChunker {

    private final int chunkSize;
    private final int chunkOverlap;
    private final ChunkStrategy strategy;

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?])\\s+");
    private static final Pattern PARAGRAPH_SPLIT = Pattern.compile("\\n\\s*\\n");

    public enum ChunkStrategy { SENTENCE, PARAGRAPH, FIXED }

    /**
     * A chunk of text with positional metadata.
     */
    public record Chunk(String id, String text, int startPos, int endPos, Map<String, String> metadata) {
        public Chunk(String id, String text, int startPos, int endPos) {
            this(id, text, startPos, endPos, Map.of());
        }

        public Chunk withMeta(String key, String value) {
            var meta = new HashMap<>(metadata);
            meta.put(key, value);
            return new Chunk(id, text, startPos, endPos, Collections.unmodifiableMap(meta));
        }
    }

    public DocumentChunker() {
        this(512, 64, ChunkStrategy.SENTENCE);
    }

    public DocumentChunker(int chunkSize, int chunkOverlap, ChunkStrategy strategy) {
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
        this.strategy = strategy;
    }

    /**
     * Split a document into chunks.
     *
     * @param documentId unique ID for the document (used in chunk IDs)
     * @param text       the full document text
     * @return ordered list of chunks
     */
    public List<Chunk> chunk(String documentId, String text) {
        if (text == null || text.isBlank()) return List.of();

        List<String> segments = switch (strategy) {
            case SENTENCE -> splitSentences(text);
            case PARAGRAPH -> splitParagraphs(text);
            case FIXED -> splitFixed(text);
        };

        return mergeToChunks(documentId, segments);
    }

    /** Split text into sentence-level segments. */
    private List<String> splitSentences(String text) {
        String[] parts = SENTENCE_SPLIT.split(text);
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    /** Split text into paragraph-level segments. */
    private List<String> splitParagraphs(String text) {
        String[] parts = PARAGRAPH_SPLIT.split(text);
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    /** Split text into fixed-size overlapping windows. */
    private List<String> splitFixed(String text) {
        List<String> chunks = new ArrayList<>();
        int pos = 0;
        while (pos < text.length()) {
            int end = Math.min(pos + chunkSize, text.length());
            chunks.add(text.substring(pos, end).trim());
            pos += chunkSize - chunkOverlap;
        }
        return chunks;
    }

    /** Merge segments into chunks respecting the target chunk size. */
    private List<Chunk> mergeToChunks(String documentId, List<String> segments) {
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int globalPos = 0;
        int chunkIdx = 0;

        for (String segment : segments) {
            if (current.length() + segment.length() + 1 > chunkSize && current.length() > 0) {
                // Finalize current chunk
                String chunkText = current.toString().trim();
                chunks.add(new Chunk(
                        documentId + "-" + chunkIdx,
                        chunkText,
                        globalPos,
                        globalPos + chunkText.length(),
                        Map.of("documentId", documentId, "chunkIndex", String.valueOf(chunkIdx))
                ));
                chunkIdx++;

                // Start new chunk with overlap
                if (chunkOverlap > 0 && current.length() > chunkOverlap) {
                    current = new StringBuilder(current.substring(current.length() - chunkOverlap));
                } else {
                    current = new StringBuilder();
                }
                globalPos += chunkText.length();
            }

            if (current.length() > 0) current.append(' ');
            current.append(segment);
        }

        // Final chunk
        if (current.length() > 0) {
            String chunkText = current.toString().trim();
            chunks.add(new Chunk(
                    documentId + "-" + chunkIdx,
                    chunkText,
                    globalPos,
                    globalPos + chunkText.length(),
                    Map.of("documentId", documentId, "chunkIndex", String.valueOf(chunkIdx))
            ));
        }

        return chunks;
    }

    public int chunkSize() { return chunkSize; }
    public int chunkOverlap() { return chunkOverlap; }
    public ChunkStrategy strategy() { return strategy; }
}
