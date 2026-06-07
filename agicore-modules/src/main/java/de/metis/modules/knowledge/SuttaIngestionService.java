package de.metis.modules.knowledge;

import de.metis.kernel.persistence.KnowledgeStore;
import de.metis.kernel.rag.DocumentChunker;
import de.metis.kernel.rag.DocumentChunker.Chunk;
import de.metis.kernel.safety.EthicsCore;
import de.metis.kernel.world.Belief;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Phase 11.5 — Sutta Ingestion (Sprint #2, 07.06.2026).
 *
 * <p>Spezialisierter Loader f\u00fcr buddhistische Quelltexte (Dhammapada,
 * Metta Sutta, Sigalovada Sutta) als Markdown. Im Gegensatz zum allgemeinen
 * {@link BookIngestionService} setzt dieser Service den Source-Tag-Prefix
 * {@link EthicsCore#SOURCE_PREFIX} (= {@code "ethics:"}), damit der
 * EthicsRetriever sp\u00e4ter gezielt nach Werte-Quellen filtern kann.
 *
 * <p>Designgrund (Code-Reality-Check 07.06.): die 3 Sutten lagen seit
 * Mai auf KALI, waren aber nie in den Belief-Store gelangt \u2014 nur
 * als Lippenbekenntnis in {@code SelfReflector} hartcodiert. Dieser
 * Service ist die fehlende Wirkschicht.
 *
 * <p>Keine externen Tools, keine LLM-Calls. Pure JVM + Files.
 */
public class SuttaIngestionService {

    private static final Logger LOG = Logger.getLogger(SuttaIngestionService.class.getName());

    private static final int CHUNK_SIZE = 384;   // Suttas haben kurze Verse
    private static final int CHUNK_OVERLAP = 48;
    private static final int MAX_CHUNKS_PER_SUTTA = 500;
    private static final int MIN_CHUNK_LENGTH = 25;

    private final KnowledgeStore knowledgeStore;
    private final Path suttaDir;
    private final DocumentChunker chunker;
    private final Set<String> ingestedFiles = new HashSet<>();

    public SuttaIngestionService(KnowledgeStore knowledgeStore, Path suttaDir) {
        this.knowledgeStore = Objects.requireNonNull(knowledgeStore);
        this.suttaDir = Objects.requireNonNull(suttaDir);
        this.chunker = new DocumentChunker(CHUNK_SIZE, CHUNK_OVERLAP,
                DocumentChunker.ChunkStrategy.PARAGRAPH);
    }

    /**
     * Scant das Sutta-Verzeichnis und ingest alle .md-Dateien.
     * Idempotent: bereits ingestete Dateien werden \u00fcbersprungen.
     *
     * @return Anzahl neu ingesteter Beliefs (Summe \u00fcber alle Dateien)
     */
    public IngestResult ingest() {
        if (!Files.isDirectory(suttaDir)) {
            LOG.warning("SuttaIngestionService: directory not found: " + suttaDir);
            return new IngestResult(0, 0, List.of());
        }

        int totalBeliefs = 0;
        int filesProcessed = 0;
        List<String> details = new ArrayList<>();

        try (Stream<Path> files = Files.list(suttaDir)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (!name.toLowerCase(Locale.ROOT).endsWith(".md")) continue;
                if (ingestedFiles.contains(name)) continue;

                try {
                    int n = ingestMarkdown(file);
                    ingestedFiles.add(name);
                    totalBeliefs += n;
                    filesProcessed++;
                    details.add(name + " -> " + n + " beliefs");
                    LOG.info("SuttaIngestionService: " + name + " -> " + n + " beliefs");
                } catch (Exception e) {
                    LOG.warning("SuttaIngestionService: " + name + " failed: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.severe("SuttaIngestionService: scan failed: " + e.getMessage());
        }

        return new IngestResult(filesProcessed, totalBeliefs, List.copyOf(details));
    }

    private int ingestMarkdown(Path file) throws IOException {
        String content = Files.readString(file);
        String tag = sourceTagFrom(file);

        // Idempotenz: skip if any belief with this exact ethics:<tag># prefix exists.
        String prefix = EthicsCore.SOURCE_PREFIX + tag + "#";
        int existing = knowledgeStore.countBeliefsBySourcePrefix(prefix);
        if (existing > 0) {
            LOG.info("SuttaIngestionService: skip " + file.getFileName()
                    + " — already ingested (" + existing + " beliefs with source prefix '"
                    + prefix + "')");
            return 0;
        }

        // Strip simple Markdown noise: leading #, ** **, code fences.
        String text = content
                .replaceAll("(?m)^#{1,6}\\s+", "")
                .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
                .replaceAll("(?m)^>\\s+", "")
                .replaceAll("```[\\s\\S]*?```", " ")
                .strip();

        List<Chunk> chunks = chunker.chunk(tag, text);
        int stored = 0;

        for (int i = 0; i < Math.min(chunks.size(), MAX_CHUNKS_PER_SUTTA); i++) {
            Chunk c = chunks.get(i);
            String stmt = c.text().strip();
            if (stmt.length() < MIN_CHUNK_LENGTH) continue;

            // Source prefix: "ethics:<sutta>#<chunkIndex>"
            String source = EthicsCore.SOURCE_PREFIX + tag + "#" + i;
            var belief = new Belief(stmt, 0.7, source);
            knowledgeStore.saveBelief(belief);
            stored++;
        }
        return stored;
    }

    /**
     * Leitet einen kurzen, kanonisch lesbaren Tag aus dem Dateinamen ab.
     * {@code dhammapada.md} -> {@code dhammapada}.
     */
    static String sourceTagFrom(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        // collapse runs of non-alphanumerics into single dashes
        name = name.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return name.isEmpty() ? "unnamed" : name;
    }

    public record IngestResult(int filesProcessed, int beliefsCreated, List<String> details) {
        public String summary() {
            return "SuttaIngest: " + filesProcessed + " files, "
                    + beliefsCreated + " beliefs created";
        }
    }
}
