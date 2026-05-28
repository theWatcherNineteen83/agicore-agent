package de.metis.kernel.rag;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

/**
 * Unit tests for DocumentChunker — verifies chunking strategies.
 */
class DocumentChunkerTest {

    private static final String SHORT_TEXT = "Dies ist ein kurzer Text. Er hat zwei Sätze. Das ist der dritte Satz.";

    @Test
    void sentenceChunkingShortText() {
        DocumentChunker chunker = new DocumentChunker(512, 64, DocumentChunker.ChunkStrategy.SENTENCE);
        List<DocumentChunker.Chunk> chunks = chunker.chunk("doc1", SHORT_TEXT);
        assertFalse(chunks.isEmpty(), "Should produce at least one chunk");
        assertTrue(chunks.size() == 1, "Short text should produce one chunk: got " + chunks.size());
        assertEquals("doc1-0", chunks.get(0).id());
    }

    @Test
    void sentenceChunkingLongText() {
        DocumentChunker chunker = new DocumentChunker(50, 20, DocumentChunker.ChunkStrategy.SENTENCE);
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            longText.append("Satz ").append(i).append(". ");
        }
        List<DocumentChunker.Chunk> chunks = chunker.chunk("doc2", longText.toString());
        assertTrue(chunks.size() > 1, "Long text with small chunk size should produce multiple chunks");
        // Each chunk should contain documentId in its id
        for (var chunk : chunks) {
            assertTrue(chunk.id().startsWith("doc2-"), "Chunk id should start with doc2-: " + chunk.id());
        }
    }

    @Test
    void paragraphChunking() {
        DocumentChunker chunker = new DocumentChunker(100, 16, DocumentChunker.ChunkStrategy.PARAGRAPH);
        String multiPar = "Paragraf eins mit genug Text um ein Chunk zu füllen.\n\n"
                + "Paragraf zwei mit anderem Inhalt.\n\n"
                + "Paragraf drei komplett anders.";
        List<DocumentChunker.Chunk> chunks = chunker.chunk("doc3", multiPar);
        assertFalse(chunks.isEmpty());
    }

    @Test
    void fixedChunking() {
        DocumentChunker chunker = new DocumentChunker(30, 10, DocumentChunker.ChunkStrategy.FIXED);
        String text = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        List<DocumentChunker.Chunk> chunks = chunker.chunk("doc4", text);
        assertTrue(chunks.size() > 1, "Fixed chunking should split long text: got " + chunks.size());
    }

    @Test
    void emptyText() {
        DocumentChunker chunker = new DocumentChunker();
        assertTrue(chunker.chunk("doc5", "").isEmpty());
        assertTrue(chunker.chunk("doc6", null).isEmpty());
        assertTrue(chunker.chunk("doc7", "   ").isEmpty());
    }

    @Test
    void chunkMetadata() {
        DocumentChunker chunker = new DocumentChunker(200, 32, DocumentChunker.ChunkStrategy.SENTENCE);
        List<DocumentChunker.Chunk> chunks = chunker.chunk("meta-doc", SHORT_TEXT);
        for (var chunk : chunks) {
            assertNotNull(chunk.metadata(), "Metadata should not be null");
            assertNotNull(chunk.startPos(), "Start position should not be null");
            assertNotNull(chunk.endPos(), "End position should not be null");
        }
    }

    /** Verify fixed chunking produces multiple chunks for long text. */
    @Test
    void fixedChunkOverlap() {
        int chunkSize = 50;
        int overlap = 20;
        DocumentChunker chunker = new DocumentChunker(chunkSize, overlap, DocumentChunker.ChunkStrategy.FIXED);
        // Use varied text to prevent mergeToChunks from collapsing everything into one
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append(String.format("Segment %d mit unterschiedlichem Inhalt. ", i));
        }
        List<DocumentChunker.Chunk> chunks = chunker.chunk("overlap-test", sb.toString());
        assertTrue(chunks.size() >= 2, "Should produce at least 2 chunks, got " + chunks.size());
        // All chunks should have valid positions
        for (var chunk : chunks) {
            assertTrue(chunk.startPos() >= 0);
            assertTrue(chunk.endPos() > chunk.startPos());
        }
    }
}
