package de.metis.modules.knowledge;

import de.metis.kernel.persistence.KnowledgeStore;
import de.metis.kernel.rag.DocumentChunker;
import de.metis.kernel.rag.DocumentChunker.Chunk;
import de.metis.kernel.goal.KanbanBoard;
import de.metis.kernel.goal.Goal;
import de.metis.kernel.world.Belief;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Ingests PDF and EPUB books into the Metis knowledge system.
 * <p>
 * Pipeline: file → text extraction → chunking → belief creation → Kanban goals.
 * Supported formats: PDF (via mutool), EPUB (via unzip + HTML parsing).
 */
public class BookIngestionService {

    private static final Logger LOG = Logger.getLogger(BookIngestionService.class.getName());

    private final KnowledgeStore knowledgeStore;
    private final KanbanBoard kanbanBoard;
    private final DocumentChunker chunker;
    private final Path bookDir;
    private final Set<String> ingestedFiles = new HashSet<>();

    private static final int CHUNK_SIZE = 512;
    private static final int CHUNK_OVERLAP = 64;
    private static final int MAX_BELIEFS_PER_BOOK = 200;

    public BookIngestionService(KnowledgeStore knowledgeStore, KanbanBoard kanbanBoard, Path bookDir) {
        this.knowledgeStore = knowledgeStore;
        this.kanbanBoard = kanbanBoard;
        this.bookDir = bookDir;
        this.chunker = new DocumentChunker(CHUNK_SIZE, CHUNK_OVERLAP, DocumentChunker.ChunkStrategy.PARAGRAPH);
    }

    /**
     * Scan the book directory and ingest any new files.
     * @return number of newly ingested books
     */
    public int ingestNewBooks() {
        if (!Files.isDirectory(bookDir)) {
            LOG.warning("Book directory not found: " + bookDir);
            return 0;
        }

        int count = 0;
        try (var files = Files.list(bookDir)) {
            for (Path file : files.sorted().toList()) {
                String fileName = file.getFileName().toString().toLowerCase();
                if (ingestedFiles.contains(fileName)) continue;

                try {
                    if (fileName.endsWith(".pdf")) {
                        ingestPdf(file);
                        ingestedFiles.add(fileName);
                        count++;
                    } else if (fileName.endsWith(".epub")) {
                        ingestEpub(file);
                        ingestedFiles.add(fileName);
                        count++;
                    }
                } catch (Exception e) {
                    LOG.warning("Failed to ingest " + fileName + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.severe("Book directory scan failed: " + e.getMessage());
        }

        if (count > 0) {
            LOG.info("Ingested " + count + " new book(s) from " + bookDir);
        }
        return count;
    }

    // ── PDF ingestion ────────────────────────────────────────────

    private void ingestPdf(Path pdfFile) throws Exception {
        String bookTitle = titleFromFileName(pdfFile);
        LOG.info("Ingesting PDF: " + bookTitle);

        // Extract text via mutool
        String text = extractPdfText(pdfFile);
        if (text == null || text.isBlank()) {
            LOG.warning("No text extracted from PDF: " + pdfFile);
            return;
        }

        ingestText(bookTitle, text, "pdf", pdfFile.toString());
    }

    private String extractPdfText(Path pdfFile) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "mutool", "draw", "-F", "text", pdfFile.toString()
        );
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();

        String text;
        try (var is = p.getInputStream()) {
            text = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }

        boolean ok = p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
        if (!ok) {
            p.destroyForcibly();
            throw new IOException("mutool timeout for " + pdfFile);
        }
        if (p.exitValue() != 0) {
            throw new IOException("mutool exit code " + p.exitValue());
        }

        // Clean up PDF artifacts
        text = text.replaceAll("\\n{4,}", "\n\n\n"); // excessive newlines
        text = text.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", ""); // control chars
        return text.strip();
    }

    // ── EPUB ingestion ───────────────────────────────────────────

    private void ingestEpub(Path epubFile) throws Exception {
        String bookTitle = titleFromFileName(epubFile);
        LOG.info("Ingesting EPUB: " + bookTitle);

        String text = extractEpubText(epubFile);
        if (text == null || text.isBlank()) {
            LOG.warning("No text extracted from EPUB: " + epubFile);
            return;
        }

        ingestText(bookTitle, text, "epub", epubFile.toString());
    }

    private String extractEpubText(Path epubFile) throws IOException, InterruptedException {
        Path tmpDir = Files.createTempDirectory("epub-extract-");
        try {
            // Unzip EPUB
            ProcessBuilder unzip = new ProcessBuilder("unzip", "-o", epubFile.toString(), "-d", tmpDir.toString());
            unzip.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = unzip.start();
            p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);

            // Extract text from HTML/XHTML files
            StringBuilder allText = new StringBuilder();
            try (Stream<Path> files = Files.walk(tmpDir)) {
                files.filter(f -> {
                    String name = f.getFileName().toString().toLowerCase();
                    return name.endsWith(".html") || name.endsWith(".xhtml") || name.endsWith(".htm");
                }).sorted().forEach(htmlFile -> {
                    try {
                        String html = Files.readString(htmlFile);
                        String text = stripHtml(html);
                        if (!text.isBlank()) {
                            allText.append(text).append("\n\n");
                        }
                    } catch (IOException ignored) { }
                });
            }

            return allText.toString().strip();
        } finally {
            // Cleanup temp dir
            try (Stream<Path> files = Files.walk(tmpDir)) {
                files.sorted(Comparator.reverseOrder()).forEach(f -> {
                    try { Files.deleteIfExists(f); } catch (IOException ignored) { }
                });
            } catch (IOException ignored) { }
        }
    }

    private String stripHtml(String html) {
        // Remove scripts and styles
        String text = html.replaceAll("(?s)<script[^>]*>.*?</script>", " ");
        text = text.replaceAll("(?s)<style[^>]*>.*?</style>", " ");
        // Remove all tags
        text = text.replaceAll("<[^>]+>", " ");
        // Decode common entities
        text = text.replace("&amp;", "&")
                   .replace("&lt;", "<")
                   .replace("&gt;", ">")
                   .replace("&quot;", "\"")
                   .replace("&apos;", "'")
                   .replace("&nbsp;", " ")
                   .replace("&#13;", "\n")
                   .replace("&#160;", " ");
        // Normalize whitespace
        text = text.replaceAll("\\s+", " ");
        return text.strip();
    }

    // ── Text ingestion (shared) ──────────────────────────────────

    private void ingestText(String bookTitle, String text, String format, String sourcePath) {
        List<Chunk> chunks = chunker.chunk(bookTitle, text);
        int stored = 0;

        for (int i = 0; i < Math.min(chunks.size(), MAX_BELIEFS_PER_BOOK); i++) {
            Chunk c = chunks.get(i);
            String statement = c.text().strip();
            if (statement.length() < 20) continue; // skip tiny chunks

            var belief = new Belief(
                    statement,
                    0.5, // baseline confidence
                    "book:" + bookTitle + " [" + format + " ch" + i + "]"
            );
            knowledgeStore.saveBelief(belief);
            stored++;
        }

        LOG.info("Stored " + stored + " belief chunks for: " + bookTitle);

        // Create a Kanban learning goal
        if (kanbanBoard != null && stored > 0) {
            var goal = new Goal(
                    "Lerne aus Buch: " + bookTitle + " (" + stored + " Chunks)",
                    "book-learning",
                    60, 0.6, 3,
                    Goal.ServiceClass.INTANGIBLE,
                    Goal.ResourceType.CPU_HEAVY,
                    null // no deadline
            );
            kanbanBoard.add(goal);
            LOG.info("Created book-learning goal: " + goal.id());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    private String titleFromFileName(Path file) {
        String name = file.getFileName().toString();
        // Remove extension
        int dotIdx = name.lastIndexOf('.');
        if (dotIdx > 0) name = name.substring(0, dotIdx);
        // Replace underscores/dashes with spaces, capitalize words
        name = name.replace('_', ' ').replace('-', ' ');
        // Title case
        var words = name.split("\\s+");
        var result = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty()) {
                result.append(Character.toUpperCase(w.charAt(0)))
                      .append(w.substring(1).toLowerCase())
                      .append(' ');
            }
        }
        return result.toString().strip();
    }

    /** Check available tools on the system. */
    public static boolean toolsAvailable() {
        return checkTool("mutool") && checkTool("unzip");
    }

    private static boolean checkTool(String tool) {
        try {
            return new ProcessBuilder("which", tool)
                    .start().waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return false;
        }
    }
}
