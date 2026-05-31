package de.metis.kernel.inference;

import com.github.tjake.jlama.model.AbstractModel;
import com.github.tjake.jlama.model.ModelSupport;
import com.github.tjake.jlama.safetensors.DType;
import com.github.tjake.jlama.safetensors.prompt.PromptContext;
import com.github.tjake.jlama.util.Downloader;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * Pure Java LLM inference service using JLama.
 * <p>
 * Provides local LLM inference without external dependencies (no Ollama, no Python).
 * Uses Panama Vector API for SIMD acceleration on supported CPUs.
 * <p>
 * Configuration via system properties:
 * <ul>
 *   <li>{@code metis.jlama.model} — HuggingFace model name (default: tjake/Llama-3.2-1B-Instruct-JQ4)</li>
 *   <li>{@code metis.jlama.model.dir} — local model directory (default: ./models)</li>
 *   <li>{@code metis.jlama.enabled} — enable/disable JLama (default: false)</li>
 *   <li>{@code metis.jlama.maxTokens} — max tokens per generation (default: 256)</li>
 *   <li>{@code metis.jlama.temperature} — generation temperature (default: 0.0)</li>
 * </ul>
 * <p>
 * Requires JVM flags: {@code --enable-preview --add-modules jdk.incubator.vector}
 */
public class JlamaInferenceService {

    private static final Logger LOG = Logger.getLogger(JlamaInferenceService.class.getName());

    private static final String DEFAULT_MODEL = "tjake/Llama-3.2-1B-Instruct-JQ4";
    private static final String MODEL_DIR_PROP = "metis.jlama.model.dir";
    private static final String MODEL_PROP = "metis.jlama.model";
    private static final String ENABLED_PROP = "metis.jlama.enabled";
    private static final String MAX_TOKENS_PROP = "metis.jlama.maxTokens";
    private static final String TEMPERATURE_PROP = "metis.jlama.temperature";

    private static volatile JlamaInferenceService INSTANCE;

    private final boolean enabled;
    private final String modelName;
    private final Path modelDir;
    private final int maxTokens;
    private final float temperature;

    private final ReentrantLock loadLock = new ReentrantLock();
    private volatile AbstractModel model;
    private volatile boolean initialized;
    private volatile String initError;

    private JlamaInferenceService() {
        this.enabled = Boolean.parseBoolean(System.getProperty(ENABLED_PROP, "false"));
        this.modelName = System.getProperty(MODEL_PROP, DEFAULT_MODEL);
        this.modelDir = Path.of(System.getProperty(MODEL_DIR_PROP, "./models"));
        this.maxTokens = Integer.parseInt(System.getProperty(MAX_TOKENS_PROP, "256"));
        this.temperature = Float.parseFloat(System.getProperty(TEMPERATURE_PROP, "0.0"));

        if (enabled) {
            LOG.info(() -> "JLama service configured: model=" + modelName
                    + " dir=" + modelDir.toAbsolutePath()
                    + " maxTokens=" + maxTokens + " temp=" + temperature);
        }
    }

    public static JlamaInferenceService getInstance() {
        if (INSTANCE == null) {
            synchronized (JlamaInferenceService.class) {
                if (INSTANCE == null) {
                    INSTANCE = new JlamaInferenceService();
                }
            }
        }
        return INSTANCE;
    }

    public boolean isEnabled() { return enabled; }
    public boolean isInitialized() { return initialized; }
    public String getInitError() { return initError; }
    public String getModelName() { return modelName; }

    /**
     * Initialize the JLama model. Downloads if not present, loads into memory.
     * Thread-safe: only one initialization at a time.
     *
     * @return true if initialization succeeded
     */
    public boolean init() {
        if (!enabled) {
            LOG.fine("JLama not enabled — skipping init");
            return false;
        }
        if (initialized) return true;

        loadLock.lock();
        try {
            if (initialized) return true; // double-check

            LOG.info(() -> "Initializing JLama model: " + modelName);

            // 1. Download model if needed
            File localModelPath;
            try {
                Downloader downloader = new Downloader(modelDir.toString(), modelName);
                localModelPath = downloader.huggingFaceModel();
                LOG.info(() -> "JLama model local path: " + localModelPath);
            } catch (IOException e) {
                initError = "Failed to download model " + modelName + ": " + e.getMessage();
                LOG.severe(initError);
                return false;
            }

            // 2. Load model
            try {
                model = ModelSupport.loadModel(localModelPath, DType.F32, DType.I8);
                LOG.info(() -> "JLama model loaded: " + modelName
                        + " (params=" + model.getConfig().contextLength + " context)");
            } catch (Exception e) {
                initError = "Failed to load model " + modelName + ": " + e.getMessage();
                LOG.severe(initError);
                return false;
            }

            initialized = true;
            LOG.info(() -> "JLama inference ready: " + modelName);
            return true;
        } finally {
            loadLock.unlock();
        }
    }

    /**
     * Generate a response for a prompt (completion mode, no chat template).
     *
     * @param prompt plain text prompt
     * @return generated text, or null on error
     */
    public String complete(String prompt) {
        if (!checkReady()) return null;

        try {
            PromptContext ctx = PromptContext.of(prompt);
            var response = model.generate(UUID.randomUUID(), ctx, temperature, maxTokens,
                    (token, timing) -> {});
            LOG.fine(() -> "JLama complete: " + maxTokens + " tokens generated");
            return response.responseText;
        } catch (Exception e) {
            LOG.warning("JLama complete failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Generate a response in chat mode (system + user messages).
     *
     * @param systemPrompt system message (can be null)
     * @param userMessage  user message
     * @return generated text, or null on error
     */
    public String chat(String systemPrompt, String userMessage) {
        if (!checkReady()) return null;

        try {
            PromptContext ctx;
            if (model.promptSupport().isPresent()) {
                var builder = model.promptSupport().get().builder();
                if (systemPrompt != null && !systemPrompt.isEmpty()) {
                    builder.addSystemMessage(systemPrompt);
                }
                builder.addUserMessage(userMessage);
                ctx = builder.build();
            } else {
                // Fallback: combine system + user into plain prompt
                String combined = systemPrompt != null
                        ? systemPrompt + "\n\nUser: " + userMessage + "\nAssistant:"
                        : userMessage;
                ctx = PromptContext.of(combined);
            }

            var response = model.generate(UUID.randomUUID(), ctx, temperature, maxTokens,
                    (token, timing) -> {});
            LOG.fine(() -> "JLama chat: " + tokenCount(response.responseText) + " tokens");
            return response.responseText;
        } catch (Exception e) {
            LOG.warning("JLama chat failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Shutdown and release model resources.
     */
    public void shutdown() {
        if (model != null) {
            try {
                model.close();
                LOG.info("JLama model closed");
            } catch (Exception e) {
                LOG.warning("JLama shutdown error: " + e.getMessage());
            }
            model = null;
        }
        initialized = false;
    }

    private boolean checkReady() {
        if (!enabled) {
            LOG.fine("JLama not enabled");
            return false;
        }
        if (!initialized) {
            LOG.fine("JLama not initialized — call init() first");
            return false;
        }
        if (model == null) {
            LOG.warning("JLama model is null after init");
            return false;
        }
        return true;
    }

    private int tokenCount(String text) {
        if (text == null || text.isEmpty()) return 0;
        return text.split("\\s+").length; // rough estimate
    }
}
