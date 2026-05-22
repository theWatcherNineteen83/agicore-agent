package de.agicore.modules.evolution;

import de.agicore.kernel.evolution.EvolutionManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
    private static final String MODEL = "qwen3.6:27b-q4_K_M"; // good code generation, fits 7900 XTX
    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private int mutationCount = 0;

    /**
     * Generate a mutated variant of a Java source file.
     *
     * @param moduleName    e.g. "stub-planner"
     * @param currentSource the full current source code
     * @param className     the simple class name (e.g. "StubPlanner")
     * @param packageName   the full package (e.g. "de.agicore.modules.planner")
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
                        "num_predict": 4096,
                        "stop": ["```"]
                      }
                    }
                    """, MODEL, escapeJson(prompt));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OLLAMA_URL))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

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
    private String buildMutationPrompt(String moduleName, String source,
                                        String className, String packageName) {
        return String.format("""
                You are a Java code optimizer. Your task is to improve a Planner implementation.

                CURRENT CODE:
                ```java
                %s
                ```

                MUTATION RULES (MUST FOLLOW ALL):
                1. Do NOT change any method signatures.
                2. Do NOT add new import statements or dependencies.
                3. Keep the package declaration EXACTLY: %s
                4. Keep the class name EXACTLY: %s
                5. Change at most 15%% of the code.
                6. Focus ONLY on improving: keyword extraction logic, action selection heuristics,
                   or learned-mapping thresholds.
                7. Do NOT add logging, comments unrelated to logic, or dead code.

                Return ONLY the complete modified Java class, starting with the package declaration.
                Do not include explanations. Do not wrap in markdown.
                """, source, packageName, className);
    }

    /** Extract the "response" field from Ollama's JSON output. */
    private String extractResponse(String json) {
        // Simple extraction: find "response":"..." 
        int start = json.indexOf("\"response\":\"");
        if (start < 0) return json;
        start += 12;
        int end = json.indexOf("\",\"done\"", start);
        if (end < 0) end = json.indexOf("\"}", start);
        if (end < 0) return json.substring(start);

        String text = json.substring(start, end);
        // Unescape JSON escapes
        return text.replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
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
}
