package de.metis.modules.evolution;

import de.metis.kernel.evolution.EvolutionManager;

import de.metis.kernel.evolution.EvolutionManager;
import de.metis.kernel.evolution.PromptBank;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Calls the Ollama API on miniedi to generate code mutations.
 * <p>
 * Sends the current module source + a constrained mutation prompt,
 * receives the LLM-generated variant, extracts the Java class from the response,
 * and returns it for compilation and shadow evaluation.
 * <p>
 * Safety constraints are enforced in the prompt, not parsed from output:
 * <ul>
 *   <li>Method signatures must not change</li>
 *   <li>No new dependencies</li>
 *   <li>Package name must stay identical</li>
 *   <li>Class name must stay identical</li>
 *   <li>Max ~15% code change</li>
 * </ul>
 */
public class OllamaMutationService implements EvolutionManager.MutationService {

    private static final Logger LOG = Logger.getLogger(OllamaMutationService.class.getName());

    private static final String OLLAMA_URL = "http://192.168.22.204:11434/api/generate";
    private static final String DEFAULT_MODEL = "qwen3.6:27b-q4_K_M";
    private static final Duration TIMEOUT = Duration.ofSeconds(1200);

    private Supplier<String> modelProvider = () -> DEFAULT_MODEL;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private int mutationCount = 0;
    private String lastRawResponse = null;
    private PromptBank promptBank = new PromptBank();

    /**
     * Default: uses hardcoded model. Call {@link #withRegistry(ModelRegistry)} for auto-selection.
     */
    public OllamaMutationService() {}

    /**
     * Use a ModelRegistry for automatic model selection.
     * The registry selects the best mutation-capable model from available models.
     */
    public OllamaMutationService(ModelRegistry registry) {
        this.modelProvider = registry::mutationModel;
    }

    /** Manually set model provider. */
    public OllamaMutationService withRegistry(ModelRegistry registry) {
        this.modelProvider = registry::mutationModel;
        return this;
    }

    /** Resolve current model name. */
    public String currentModel() {
        return modelProvider != null ? modelProvider.get() : DEFAULT_MODEL;
    }

    /**
     * Generate a mutated variant of a Java source file.
     *
     * @param moduleName    e.g. "stub-planner"
     * @param currentSource the full current source code
     * @param className     the simple class name (e.g. "StubPlanner")
     * @param packageName   the full package (e.g. "de.metis.modules.planner")
     * @return the mutated source code, or null if generation failed
     */
    public String mutate(String moduleName, String currentSource,
                         String className, String packageName) {
        mutationCount++;
        LOG.info("Ollama mutation #" + mutationCount + " for: " + moduleName);

        String prompt = buildMutationPrompt(moduleName, currentSource, className, packageName);

        try {
            String jsonBody = String.format("""
                    {
                      "model": "%s",
                      "prompt": %s,
                      "stream": false,
                      "options": {
                        "temperature": 0.7,
                        "top_p": 0.9,
                        "num_predict": 4096
                      }
                    }
                    """, currentModel(), escapeJson(prompt));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OLLAMA_URL))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            lastRawResponse = response.body();

            if (response.statusCode() != 200) {
                LOG.warning("Ollama returned " + response.statusCode() + ": " + response.body());
                return null;
            }

            String generatedText = extractResponse(response.body());
            String source = extractJavaSource(generatedText, className);

            if (source == null || source.isBlank()) {
                LOG.warning("Failed to extract Java source from Ollama response");
                return null;
            }

            LOG.info(() -> "Ollama generated " + source.length() + " chars for " + className);
            return source;

        } catch (Exception e) {
            LOG.warning("Ollama mutation failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Build the constrained mutation prompt.
     * Critical: the prompt must be tight to prevent the LLM from going rogue.
     */
        /**
     * Generate a mutated variant with compiler error feedback.
     * The compile errors are appended to the prompt so the LLM can fix them.
     */
    public String mutateWithFeedback(String moduleName, String currentSource,
                                      String className, String packageName, String compileErrors) {
        mutationCount++;
        LOG.info("Ollama mutation #" + mutationCount + " (with compile feedback) for: " + moduleName);

        String prompt = buildMutationPrompt(moduleName, currentSource, className, packageName);

        if (compileErrors != null && !compileErrors.isBlank()) {
            prompt += "\n\nPREVIOUS COMPILATION ERRORS (must fix):\n"
                    + compileErrors
                    + "\n\nFIX THESE ERRORS in the new version. Output ONLY valid Java code.\n";
        }

        try {
            String jsonBody = String.format("""
                    {
                      "model": "%s",
                      "prompt": %s,
                      "stream": false,
                      "options": {
                        "temperature": 0.5,
                        "top_p": 0.9,
                        "num_predict": 4096
                      }
                    }
                    """, currentModel(), escapeJson(prompt));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OLLAMA_URL))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            lastRawResponse = response.body();

            if (response.statusCode() != 200) {
                LOG.warning("Ollama returned " + response.statusCode() + ": " + response.body());
                return null;
            }

            String generatedText = extractResponse(response.body());
            String source = extractJavaSource(generatedText, className);

            if (source == null || source.isBlank()) {
                LOG.warning("Failed to extract Java source from Ollama response (feedback)");
                return null;
            }

            LOG.info(() -> "Ollama generated " + source.length() + " chars for " + className + " (with feedback)");
            return source;

        } catch (Exception e) {
            LOG.warning("Ollama mutation (feedback) failed: " + e.getMessage());
            return null;
        }
    }


    private String buildMutationPrompt(String moduleName, String source,
                                        String className, String packageName) {
        String basePrompt = String.format("""
                You are a Java code mutation engine. Output ONLY valid, compilable Java code.
                Start with the package declaration. No markdown, no explanations.

                RULES:
                1. Keep ALL imports, package declaration, class name, and method signatures identical.
                2. Modify 10-20%% of the method bodies — change thresholds, add heuristics, improve keyword extraction.
                3. Every change MUST produce valid Java that compiles with javac.
                4. Do NOT add new imports or dependencies.
                5. Return ONLY the complete modified Java source file.

                Original file:
                ```java
                %s
                ```

                Modified file (complete, compilable):
                """, source);

        // Inject few-shot examples from prompt bank
        String fewShot = promptBank.buildFewShotPrompt(moduleName);
        return basePrompt + fewShot;
    }

    /** Extract generated text from Ollama JSON, handling thinking models. */
    private String extractResponse(String json) {
        // Try "response" field first
        String text = extractJsonField(json, "response");
        if (text != null && !text.isBlank()) return unescape(text);

        // Fallback: "thinking" field (qwen, deepseek-r1, etc.)
        String thinking = extractJsonField(json, "thinking");
        if (thinking != null && !thinking.isBlank()) {
            // Try to find Java code within thinking text
            String code = extractJavaSource(thinking, null);
            if (code != null) return code;
            // If no code found, return the thinking text for debugging
            return unescape(thinking);
        }

        return json; // raw fallback
    }

    /** Extract a named field value from a JSON string, decoding all escapes. */
    private String extractJsonField(String json, String fieldName) {
        String searchKey = "\"" + fieldName + "\":\"";
        int start = json.indexOf(searchKey);
        if (start < 0) return null;
        start += searchKey.length();
        StringBuilder val = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case 'n' -> { val.append('\n'); i++; }
                    case 't' -> { val.append('\t'); i++; }
                    case 'r' -> { val.append('\r'); i++; }
                    case '"' -> { val.append('"'); i++; }
                    case '\\' -> { val.append('\\'); i++; }
                    case 'u' -> {
                        if (i + 5 < json.length()) {
                            String hex = json.substring(i + 2, i + 6);
                            val.append((char) Integer.parseInt(hex, 16));
                            i += 5;
                        } else { val.append(c); }
                    }
                    default -> val.append(c);
                }
            } else if (c == '"') {
                break; // end of string value
            } else {
                val.append(c);
            }
        }
        return val.toString();
    }

    private String unescape(String s) {
        // Already handled in extractJsonField above
        return s;
    }

    /** Extract the Java class source from the LLM output, handling markdown fences. */
    private String extractJavaSource(String text, String className) {
        // Try to find ```java ... ``` block
        int fenceStart = text.indexOf("```java");
        if (fenceStart >= 0) {
            int codeStart = text.indexOf('\n', fenceStart) + 1;
            int codeEnd = text.indexOf("```", codeStart);
            if (codeEnd > codeStart) {
                return text.substring(codeStart, codeEnd).strip();
            }
        }

        // Try to find class declaration
        int classIdx = text.indexOf("class " + className);
        if (classIdx < 0) {
            // Maybe LLM renamed it — find any class declaration
            classIdx = text.indexOf("class ");
        }
        if (classIdx >= 0) {
            // Backtrack to package declaration
            int pkgIdx = text.lastIndexOf("package ", classIdx);
            int start = pkgIdx >= 0 ? pkgIdx : classIdx;
            // Find end of class (last closing brace at root level)
            String fromClass = text.substring(start);
            int braceCount = 0;
            int end = -1;
            for (int i = 0; i < fromClass.length(); i++) {
                char c = fromClass.charAt(i);
                if (c == '{') braceCount++;
                else if (c == '}') {
                    braceCount--;
                    if (braceCount == 0 && i > 10) { end = i + 1; break; }
                }
            }
            if (end > 0) {
                return fromClass.substring(0, end).strip();
            }
        }

        // Fallback: return the raw text if it looks like Java
        if (text.contains("package ") && text.contains("class ")) {
            return text.strip();
        }

        return null;
    }

    /** Escape a string for inclusion in a JSON string value. */
    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    public int mutationCount() { return mutationCount; }
    public String lastRawResponse() { return lastRawResponse; }
    public PromptBank promptBank() { return promptBank; }

    /** Share a prompt bank instance (e.g., from EvolutionManager). */
    public void setPromptBank(PromptBank shared) {
        this.promptBank = shared != null ? shared : new PromptBank();
    }
}
