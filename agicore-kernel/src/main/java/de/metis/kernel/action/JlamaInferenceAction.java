package de.metis.kernel.action;

import de.metis.kernel.inference.JlamaInferenceService;

import java.time.Instant;
import java.util.logging.Logger;

/**
 * Local LLM inference via JLama (pure Java, no external Ollama dependency).
 * <p>
 * Category: read. Approval: AUTO.
 * <p>
 * Supports two modes:
 * <ul>
 *   <li><b>complete</b> — plain text completion</li>
 *   <li><b>chat</b> — chat template with optional system prompt</li>
 * </ul>
 * <p>
 * This action provides Metis with a fallback inference path when Ollama is
 * unavailable, or for simple tasks where a local 1B model suffices.
 * <p>
 * Requires JVM flags: {@code --enable-preview --add-modules jdk.incubator.vector}
 * and system property {@code -Dmetis.jlama.enabled=true}.
 */
public class JlamaInferenceAction implements Action {

    private static final Logger LOG = Logger.getLogger(JlamaInferenceAction.class.getName());
    public static final String NAME = "jlama";

    private final String prompt;
    private final String systemPrompt;
    private final int maxTokens;
    private final float temperature;

    /**
     * Plain completion mode.
     */
    public JlamaInferenceAction(String prompt) {
        this(prompt, null, 256, 0.0f);
    }

    /**
     * Chat mode with system prompt.
     */
    public JlamaInferenceAction(String prompt, String systemPrompt) {
        this(prompt, systemPrompt, 256, 0.0f);
    }

    /**
     * Full configuration.
     *
     * @param prompt       user prompt (required)
     * @param systemPrompt system message (optional)
     * @param maxTokens    max tokens to generate (default 256)
     * @param temperature  generation temperature (0.0 = deterministic)
     */
    public JlamaInferenceAction(String prompt, String systemPrompt, int maxTokens, float temperature) {
        this.prompt = prompt;
        this.systemPrompt = systemPrompt;
        this.maxTokens = Math.min(maxTokens, 1024);
        this.temperature = Math.max(0.0f, Math.min(temperature, 1.0f));
    }

    @Override public String name() { return NAME; }
    @Override public String category() { return "read"; }
    @Override public ApprovalLevel approvalLevel() { return ApprovalLevel.AUTO; }

    @Override
    public ActionResult execute() {
        var now = Instant.now();
        var service = JlamaInferenceService.getInstance();

        if (!service.isEnabled()) {
            return ActionResult.fail(NAME,
                    "JLama not enabled — set -Dmetis.jlama.enabled=true and add JVM flags: "
                            + "--enable-preview --add-modules jdk.incubator.vector",
                    now);
        }

        // Lazy init on first use
        if (!service.isInitialized()) {
            LOG.info("JLama first use — initializing model...");
            boolean ok = service.init();
            if (!ok) {
                return ActionResult.fail(NAME,
                        "JLama init failed: " + service.getInitError(), now);
            }
        }

        // Generate response
        String result;
        try {
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                result = service.chat(systemPrompt, prompt);
            } else {
                result = service.complete(prompt);
            }

            if (result == null) {
                return ActionResult.fail(NAME, "JLama generation returned null", now);
            }

            LOG.info(() -> "JLama inference OK: prompt="
                    + prompt.substring(0, Math.min(50, prompt.length())) + "... → "
                    + result.substring(0, Math.min(50, result.length())) + "...");

            return ActionResult.ok(NAME, result.trim(), now);

        } catch (Exception e) {
            LOG.warning("JLama inference error: " + e.getMessage());
            return ActionResult.fail(NAME, e.getMessage(), now);
        }
    }
}
