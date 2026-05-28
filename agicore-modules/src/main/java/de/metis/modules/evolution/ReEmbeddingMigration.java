package de.metis.modules.evolution;

import de.metis.kernel.rag.PersistentVectorIndex;
import de.metis.kernel.embedding.InMemoryVectorIndex;
import de.metis.kernel.embedding.VectorIndex;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Migrates stored embeddings from one model/dimension to another.
 * <p>
 * Triggered when changing embedding models (e.g. llama3.2:3b 3072d →
 * nomic-embed-text 768d). The old vectors are incompatible — they must
 * be regenerated from the original text corpus.
 * <p>
 * Usage:
 * <pre>{@code
 * var migration = new ReEmbeddingMigration(oldSvc, newSvc, vectorFile);
 * int count = migration.migrate(corpusTexts);
 * }</pre>
 */
public class ReEmbeddingMigration {

    private static final Logger LOG = Logger.getLogger(ReEmbeddingMigration.class.getName());

    private final OllamaEmbeddingService oldService;
    private final OllamaEmbeddingService newService;
    private final Path vectorFile;

    /**
     * @param oldService  old embedding service (e.g. llama3.2:3b)
     * @param newService  new embedding service (e.g. nomic-embed-text)
     * @param vectorFile  path to metis-vectors.bin
     */
    public ReEmbeddingMigration(OllamaEmbeddingService oldService,
                                 OllamaEmbeddingService newService,
                                 Path vectorFile) {
        this.oldService = oldService;
        this.newService = newService;
        this.vectorFile = vectorFile;
    }

    /**
     * Re-embed all texts using the new model and save to vector file.
     * <p>
     * Strategy: discard old vectors, re-embed from original corpus.
     * This is a ONE-WAY migration — back up the old vector file first.
     *
     * @param corpusTexts all original texts that were embedded
     * @return number of texts re-embedded
     */
    public int migrate(List<String> corpusTexts) {
        LOG.info("Starting re-embedding migration: " +
                oldService.model() + " (" + oldService.embeddingDimension() + "d) → " +
                newService.model() + " (" + newService.embeddingDimension() + "d)");
        LOG.info("Corpus size: " + corpusTexts.size() + " texts");

        // 1. Backup old vector file
        backupOldVectors();

        // 2. Create fresh vector index with new dimensions
        int newDim = newService.embeddingDimension();
        if (newDim <= 0) {
            // Trigger detection by embedding a dummy text
            newService.embed("__DIMENSION_DETECT__");
            newDim = newService.embeddingDimension();
            if (newDim <= 0) {
                LOG.severe("Cannot detect new embedding dimension — migration aborted");
                return 0;
            }
        }

        VectorIndex delegate = new InMemoryVectorIndex();
        PersistentVectorIndex newIndex = new PersistentVectorIndex(delegate, vectorFile, false);
        int count = 0;

        // 3. Re-embed every text
        for (String text : corpusTexts) {
            try {
                double[] vector = newService.embed(text);
                if (vector.length > 0) {
                    newIndex.insert(text, vector);
                    count++;
                    if (count % 50 == 0) {
                        LOG.info("Re-embedded " + count + "/" + corpusTexts.size() + " texts");
                    }
                }
            } catch (Exception e) {
                LOG.warning("Failed to re-embed text: " + e.getMessage());
            }
        }

        // 4. Persist
        newIndex.save();
        LOG.info("Re-embedding complete: " + count + " texts in " + newDim + " dimensions");
        LOG.info("New model: " + newService.model() + ", old backup kept at " +
                vectorFile.toString().replace(".bin", ".3072d.bak"));

        return count;
    }

    /**
     * Dry-run: check dimension mismatch without actually migrating.
     * @return true if migration is needed (dimensions differ)
     */
    public boolean needsMigration() {
        int oldDim = oldService.embeddingDimension();
        int newDim = newService.embeddingDimension();

        // Trigger detection if needed
        if (oldDim <= 0) {
            oldService.embed("__DIMENSION_DETECT__");
            oldDim = oldService.embeddingDimension();
        }
        if (newDim <= 0) {
            newService.embed("__DIMENSION_DETECT__");
            newDim = newService.embeddingDimension();
        }

        if (oldDim <= 0 || newDim <= 0) {
            LOG.warning("Cannot determine dimensions — assuming migration needed");
            return true;
        }

        boolean needed = oldDim != newDim;
        LOG.info("Dimension check: " + oldService.model() + "=" + oldDim + "d vs " +
                newService.model() + "=" + newDim + "d → migration " +
                (needed ? "NEEDED" : "NOT needed"));
        return needed;
    }

    private void backupOldVectors() {
        try {
            if (Files.exists(vectorFile)) {
                Path backup = Path.of(vectorFile.toString().replace(".bin", ".3072d.bak"));
                Files.copy(vectorFile, backup, StandardCopyOption.REPLACE_EXISTING);
                LOG.info("Old vectors backed up to: " + backup);
            }
        } catch (Exception e) {
            LOG.warning("Failed to backup old vectors: " + e.getMessage());
        }
    }
}
