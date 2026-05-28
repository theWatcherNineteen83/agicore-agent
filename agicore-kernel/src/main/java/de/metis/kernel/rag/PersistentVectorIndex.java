package de.metis.kernel.rag;

import de.metis.kernel.embedding.VectorIndex;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Persistent wrapper around any VectorIndex implementation.
 * <p>
 * Saves/loads vectors as compact binary format:
 * <pre>
 * [int: numEntries]
 * for each entry:
 *   [int: keyLength] [bytes: key] [int: vectorLength] [double[]: vector]
 * </pre>
 * Auto-saves on insert/remove (configurable). Supports manual save/load.
 */
public class PersistentVectorIndex implements VectorIndex, AutoCloseable {

    private static final Logger LOG = Logger.getLogger(PersistentVectorIndex.class.getName());

    private final VectorIndex delegate;
    private final Path storagePath;
    private boolean autoSave;

    /**
     * Create a persistent wrapper.
     *
     * @param delegate    the in-memory index to wrap
     * @param storagePath path to the binary storage file
     * @param autoSave    if true, save after every insert/remove/clear
     */
    public PersistentVectorIndex(VectorIndex delegate, Path storagePath, boolean autoSave) {
        this.delegate = delegate;
        this.storagePath = storagePath;
        this.autoSave = autoSave;
    }

    /** Load vectors from storage. Returns number of entries loaded. */
    public int load() {
        if (!Files.exists(storagePath)) {
            LOG.fine("No persistence file at " + storagePath + " — starting empty");
            return 0;
        }

        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(storagePath)))) {

            int numEntries = in.readInt();
            int loaded = 0;

            for (int i = 0; i < numEntries; i++) {
                int keyLen = in.readInt();
                byte[] keyBytes = new byte[keyLen];
                in.readFully(keyBytes);
                String key = new String(keyBytes);

                int vecLen = in.readInt();
                double[] vector = new double[vecLen];
                for (int j = 0; j < vecLen; j++) {
                    vector[j] = in.readDouble();
                }

                delegate.insert(key, vector);
                loaded++;
            }

            LOG.info("Loaded " + loaded + " vectors from " + storagePath);
            return loaded;

        } catch (IOException e) {
            LOG.warning("Failed to load vectors from " + storagePath + ": " + e.getMessage());
            return 0;
        }
    }

    /** Save all vectors to storage. Returns number of entries saved. */
    public int save() {
        try {
            Files.createDirectories(storagePath.getParent());

            // Gather all vectors from the delegate
            List<Map.Entry<String, double[]>> allVectors = gatherVectors();

            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(storagePath)))) {

                out.writeInt(allVectors.size());

                for (var entry : allVectors) {
                    byte[] keyBytes = entry.getKey().getBytes();
                    out.writeInt(keyBytes.length);
                    out.write(keyBytes);

                    double[] vector = entry.getValue();
                    out.writeInt(vector.length);
                    for (double v : vector) {
                        out.writeDouble(v);
                    }
                }
            }

            LOG.fine("Saved " + allVectors.size() + " vectors to " + storagePath);
            return allVectors.size();

        } catch (IOException e) {
            LOG.warning("Failed to save vectors: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Gather vectors from the delegate. Default implementation does nothing
     * because the delegate's data isn't directly accessible. Override or
     * use a tracking map if persistence is critical.
     * <p>
     * Alternative: use the VectorDump interface if the delegate implements it.
     */
    @SuppressWarnings("unchecked")
    private List<Map.Entry<String, double[]>> gatherVectors() {
        // Try to access internal buckets if it's an InMemoryVectorIndex
        try {
            if (delegate.getClass().getSimpleName().equals("InMemoryVectorIndex")) {
                var field = delegate.getClass().getDeclaredField("buckets");
                field.setAccessible(true);
                Map<String, double[]>[] buckets = (Map<String, double[]>[]) field.get(delegate);

                List<Map.Entry<String, double[]>> result = new ArrayList<>();
                if (buckets != null) {
                    for (var bucket : buckets) {
                        if (bucket != null) {
                            result.addAll(bucket.entrySet());
                        }
                    }
                }
                return result;
            }
        } catch (Exception e) {
            LOG.fine("Could not access delegate internals: " + e.getMessage());
        }
        return List.of();
    }

    @Override
    public void insert(String key, double[] vector) {
        delegate.insert(key, vector);
        if (autoSave) save();
    }

    @Override
    public List<String> search(double[] query, int k) {
        return delegate.search(query, k);
    }

    @Override
    public void remove(String key) {
        delegate.remove(key);
        if (autoSave) save();
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public void clear() {
        delegate.clear();
        if (autoSave) save();
    }

    @Override
    public void close() {
        save();
    }

    public VectorIndex delegate() { return delegate; }
    public Path storagePath() { return storagePath; }

    public void setAutoSave(boolean autoSave) { this.autoSave = autoSave; }
    public boolean isAutoSave() { return autoSave; }
}
