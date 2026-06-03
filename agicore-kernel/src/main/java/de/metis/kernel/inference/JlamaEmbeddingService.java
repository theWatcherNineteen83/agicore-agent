package de.metis.kernel.inference;

import com.github.tjake.jlama.model.AbstractModel;
import com.github.tjake.jlama.model.ModelSupport;
import com.github.tjake.jlama.model.functions.Generator.PoolingType;
import com.github.tjake.jlama.safetensors.DType;
import com.github.tjake.jlama.util.Downloader;

import java.io.File;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Pure Java embedding service using JLama with multi-model fallback.
 * <p>
 * Model 1: multilingual-e5-small (DE+EN, 384 dims)
 * Model 2: bge-small-en-v1.5   (EN,    384 dims)
 * Fallback: configured separately via EmbeddingProvider
 * <p>
 * {@code -Dmetis.jlama.enabled=true} to activate.
 */
public class JlamaEmbeddingService {

    private static final Logger LOG = Logger.getLogger(JlamaEmbeddingService.class.getName());

    private static final String MODEL1 = "intfloat/multilingual-e5-small";
    private static final String MODEL2 = "BAAI/bge-small-en-v1.5";

    private static volatile JlamaEmbeddingService INSTANCE;

    private final boolean enabled;
    private final Path modelDir;

    private volatile AbstractModel activeModel;
    private volatile String activeModelName;
    private volatile boolean initialized;
    private volatile int dims = 0;

    private JlamaEmbeddingService() {
        this.enabled = Boolean.parseBoolean(System.getProperty("metis.jlama.enabled", "false"));
        this.modelDir = Path.of(System.getProperty("metis.jlama.model.dir", "./models"));
    }

    public static JlamaEmbeddingService getInstance() {
        if (INSTANCE == null) {
            synchronized (JlamaEmbeddingService.class) {
                if (INSTANCE == null) INSTANCE = new JlamaEmbeddingService();
            }
        }
        return INSTANCE;
    }

    public boolean isEnabled() { return enabled; }
    public boolean isInitialized() { return initialized; }
    public String getActiveModelName() { return activeModelName; }
    public int getDims() { return dims; }

    public boolean init() {
        if (!enabled) return false;
        if (initialized) return true;

        String[] models = {MODEL1, MODEL2};
        for (String modelName : models) {
            try {
                LOG.info("JLama-Embed: downloading " + modelName + "...");
                Downloader dl = new Downloader(modelDir.toString(), modelName);
                File modelPath = dl.huggingFaceModel();

                LOG.info("JLama-Embed: loading " + modelName + "...");
                AbstractModel m = ModelSupport.loadModel(
                    AbstractModel.InferenceType.FULL_EMBEDDING,
                    modelPath, null, DType.F32, DType.I8,
                    java.util.Optional.empty(), java.util.Optional.empty(),
                    java.util.Optional.empty(), null);
                activeModel = m;
                activeModelName = modelName;
                dims = m.getConfig().embeddingLength;
                initialized = true;
                LOG.info("JLama-Embed: ready — " + activeModelName + " (" + dims + " dims)");
                return true;
            } catch (Exception e) {
                LOG.warning("JLama-Embed: " + modelName + " failed: " + e);
                if (e.getCause() != null) LOG.warning("  root: " + e.getCause());
            }
        }
        LOG.warning("JLama-Embed: all models failed to load");
        return false;
    }

    public double[] embed(String text) {
        if (!initialized || activeModel == null) return null;
        try {
            float[] f = activeModel.embed(text, PoolingType.MODEL);
            double[] d = new double[f.length];
            for (int i = 0; i < f.length; i++) d[i] = f[i];
            return d;
        } catch (Exception e) {
            LOG.fine("JLama-Embed failed: " + e.getMessage());
            return null;
        }
    }

    public void shutdown() {
        if (activeModel != null) {
            try { activeModel.close(); } catch (Exception e) { }
            activeModel = null;
        }
        initialized = false;
    }
}
