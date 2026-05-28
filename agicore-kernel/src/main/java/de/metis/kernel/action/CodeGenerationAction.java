package de.metis.kernel.action;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Autonomous code generation action — LLM → Java → compile → test → deploy.
 * <p>
 * Takes a natural-language specification, sends it to Ollama with a
 * structured system prompt, extracts the generated Java code, compiles it
 * in a sandbox, runs basic validation, and writes validated code to the
 * project source tree.
 * <p>
 * Safety: requires human approval before execution (writes to filesystem).
 * Generated code is sandbox-compiled first; only passing code is written.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * new CodeGenerationAction(
 *     "Generate a SystemMonitorAction that checks CPU and memory usage",
 *     "http://miniedi:11434", "nemotron-cascade-2:30b",
 *     Path.of("/home/admini/agicore-agent/agicore-modules/src/main/java"),
 *     executor, Duration.ofSeconds(120));
 * }</pre>
 */
public class CodeGenerationAction implements Action {

    private static final Logger LOG = Logger.getLogger(CodeGenerationAction.class.getName());

    public static final String NAME = "codegen";

    private static final int MAX_SPEC_LENGTH = 4000;
    private static final long COMPILE_TIMEOUT_SECONDS = 60;
    private static final Path SANDBOX_ROOT = Path.of("/tmp/metis-codegen");

    private final String specification;
    private final String ollamaUrl;
    private final String model;
    private final Path targetDir;
    private final Duration timeout;

    /**
     * @param specification natural-language description of what code to generate
     * @param ollamaUrl     Ollama API endpoint (e.g. http://miniedi:11434)
     * @param model         LLM model name
     * @param targetDir     root of the Java source tree (e.g. src/main/java)
     * @param timeout       maximum time for LLM generation + compilation
     */
    public CodeGenerationAction(String specification, String ollamaUrl, String model,
                                 Path targetDir, Duration timeout) {
        if (specification == null || specification.isBlank()) {
            throw new IllegalArgumentException("specification must not be empty");
        }
        if (specification.length() > MAX_SPEC_LENGTH) {
            throw new IllegalArgumentException("specification too long: "
                    + specification.length() + " > " + MAX_SPEC_LENGTH);
        }
        this.specification = specification.trim();
        this.ollamaUrl = ollamaUrl;
        this.model = model;
        this.targetDir = targetDir;
        this.timeout = timeout;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String category() {
        return "write";
    }

    @Override
    public boolean requiresApproval() {
        return true; // writes to source tree — needs human approval
    }

    @Override
    public ActionResult execute() {
        Instant start = Instant.now();

        try {
            // ── Phase 1: Generate code via LLM ──────────────────────
            LOG.info(() -> "CodeGen Phase 1: generating code for spec: "
                    + specification.substring(0, Math.min(80, specification.length())) + "...");

            String generatedCode = generateCode();
            if (generatedCode == null || generatedCode.isBlank()) {
                return ActionResult.fail(NAME, "LLM returned empty code", start);
            }

            // Extract class name for logging
            String className = extractClassName(generatedCode);
            LOG.info(() -> "CodeGen Phase 1 done: generated class " + className
                    + " (" + generatedCode.length() + " chars)");

            // ── Phase 2: Compile in sandbox ─────────────────────────
            LOG.info(() -> "CodeGen Phase 2: compiling " + className);
            ActionResult compileResult = compileInSandbox(generatedCode, className);
            if (!compileResult.success()) {
                return ActionResult.fail(NAME,
                        "Compilation failed for " + className + ":\n"
                                + compileResult.error() + "\n\nGenerated code:\n```java\n"
                                + generatedCode + "\n```", start);
            }

            // ── Phase 3: Write to project ─────────────────────────
            LOG.info(() -> "CodeGen Phase 3: writing " + className + " to project");
            Path writtenFile = writeToProject(generatedCode, className);

            String summary = String.format(
                    "✅ Code generation successful!\n\n"
                            + "Spec: %s\n"
                            + "Class: %s\n"
                            + "Lines: %d\n"
                            + "Written to: %s\n\n"
                            + "Generated code:\n```java\n%s\n```",
                    specification, className,
                    generatedCode.lines().count(),
                    writtenFile,
                    generatedCode);

            LOG.info(() -> "CodeGen done: " + className + " → " + writtenFile);
            return ActionResult.ok(NAME, summary, start);

        } catch (Exception e) {
            LOG.severe(() -> "CodeGen failed: " + e.getMessage());
            return ActionResult.fail(NAME,
                    "Code generation error: " + e.getClass().getSimpleName()
                            + " - " + e.getMessage(), start);
        }
    }

    // ── Phase 1: LLM Code Generation ────────────────────────────────

    private String generateCode() throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        String systemPrompt = buildSystemPrompt();
        String userPrompt = "Generate Java code for: " + specification;

        String requestBody = String.format("""
                {
                  "model": "%s",
                  "system": %s,
                  "prompt": %s,
                  "stream": false,
                  "options": {
                    "temperature": 0.2,
                    "num_predict": 4096
                  }
                }""",
                model,
                jsonEscape(systemPrompt),
                jsonEscape(userPrompt));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ollamaUrl + "/api/generate"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Ollama API error: " + response.statusCode() + " - " + response.body());
        }

        // Parse Ollama generate response
        String body = response.body();
        String responseField = extractJsonField(body, "response");
        if (responseField == null) {
            throw new IOException("No 'response' field in Ollama output: " + body);
        }

        // Extract Java code from markdown code blocks
        return extractJavaCode(responseField);
    }

    private String buildSystemPrompt() {
        return """
                You are an expert Java developer. Generate clean, compilable Java code
                based on the user's specification.

                STRICT RULES:
                1. Output ONLY valid Java code inside a single ```java ... ``` block
                2. The class must implement the Action interface (see below)
                3. Include all necessary imports
                4. Package must be: de.metis.modules.action (for module actions)
                   OR: de.metis.kernel.action (for kernel actions)
                5. Use Java 21 features (records, pattern matching, text blocks)
                6. Include Javadoc on the class
                7. No external dependencies beyond java.*
                8. Do NOT include a main() method
                9. Category "read" for observing, "write" for changing

                ACTION INTERFACE (de.metis.kernel.action.Action):
                ```java
                public interface Action {
                    String name();           // action label, e.g. "system-monitor"
                    default String category() { return "read"; }  // "read" or "write"
                    default boolean requiresApproval() { return "write".equals(category()); }
                    ActionResult execute();  // execute and return result
                }
                ```

                ACTION RESULT (de.metis.kernel.action.ActionResult):
                ```java
                public record ActionResult(
                    String name, boolean success, String body, String error,
                    java.time.Instant startedAt, java.time.Duration duration) {

                    public static ActionResult ok(String name, String body, Instant startedAt);
                    public static ActionResult fail(String name, String error, Instant startedAt);
                }
                ```

                EXAMPLE (a simple SystemInfoAction):
                ```java
                package de.metis.modules.action;

                import de.metis.kernel.action.Action;
                import de.metis.kernel.action.ActionResult;
                import java.time.Instant;

                /**
                 * Collects system information.
                 */
                public class SystemInfoAction implements Action {
                    @Override public String name() { return "system-info"; }
                    @Override public String category() { return "read"; }
                    @Override
                    public ActionResult execute() {
                        Instant start = Instant.now();
                        try {
                            String info = "CPU: " + Runtime.getRuntime().availableProcessors()
                                + " cores, Memory: " + Runtime.getRuntime().totalMemory();
                            return ActionResult.ok(name(), info, start);
                        } catch (Exception e) {
                            return ActionResult.fail(name(), e.getMessage(), start);
                        }
                    }
                }
                ```
                """;
    }

    // ── Phase 2: Sandbox Compilation ───────────────────────────────

    private ActionResult compileInSandbox(String code, String className) {
        Instant start = Instant.now();
        Path sandboxDir = null;
        try {
            sandboxDir = SANDBOX_ROOT.resolve(UUID.randomUUID().toString());
            Files.createDirectories(sandboxDir);

            // Determine package from code
            String packageName = extractPackage(code);
            Path srcDir;
            if (!packageName.isEmpty()) {
                srcDir = sandboxDir.resolve(packageName.replace('.', '/'));
                Files.createDirectories(srcDir);
            } else {
                srcDir = sandboxDir;
            }

            // Write Java file
            Path javaFile = srcDir.resolve(className + ".java");
            Files.writeString(javaFile, code);

            // Compile
            ProcessBuilder pb = new ProcessBuilder(
                    "javac", "--release", "21",
                    javaFile.toString());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            boolean finished = proc.waitFor(COMPILE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            String output;
            try (var in = proc.getInputStream()) {
                output = new String(in.readAllBytes());
            }

            if (!finished) {
                proc.destroyForcibly();
                return ActionResult.fail(NAME, "Compilation timed out after " + COMPILE_TIMEOUT_SECONDS + "s", start);
            }

            if (proc.exitValue() != 0) {
                // Clean up compilation errors for readability
                String cleaned = output.replace(sandboxDir.toString() + "/", "");
                return ActionResult.fail(NAME, "javac errors:\n" + cleaned, start);
            }

            return ActionResult.ok(NAME, "Compilation successful", start);

        } catch (Exception e) {
            return ActionResult.fail(NAME,
                    "Sandbox error: " + e.getClass().getSimpleName() + " - " + e.getMessage(), start);
        } finally {
            if (sandboxDir != null) {
                try {
                    Files.walk(sandboxDir)
                            .sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
                } catch (IOException ignored) {}
            }
        }
    }

    // ── Phase 3: Write to Project ──────────────────────────────────

    private Path writeToProject(String code, String className) throws IOException {
        String packageName = extractPackage(code);

        Path srcDir;
        if (!packageName.isEmpty()) {
            srcDir = targetDir.resolve(packageName.replace('.', '/'));
        } else {
            srcDir = targetDir;
        }
        Files.createDirectories(srcDir);

        Path targetFile = srcDir.resolve(className + ".java");
        Files.writeString(targetFile, code);

        long fileSize = Files.size(targetFile);
        LOG.info(() -> "Written: " + targetFile + " (" + fileSize + " bytes)");
        return targetFile;
    }

    // ── Helpers ────────────────────────────────────────────────────

    private String extractJavaCode(String llmOutput) {
        // Extract code between ```java ... ```
        Pattern p = Pattern.compile("```java\\s*\\n(.*?)\\n```", Pattern.DOTALL);
        Matcher m = p.matcher(llmOutput);
        if (m.find()) {
            return m.group(1).trim();
        }
        // Fallback: any code block
        p = Pattern.compile("```\\s*\\n(.*?)\\n```", Pattern.DOTALL);
        m = p.matcher(llmOutput);
        if (m.find()) {
            return m.group(1).trim();
        }
        // Fallback: return raw output (might work for code-only responses)
        return llmOutput.trim();
    }

    private String extractClassName(String code) {
        Pattern p = Pattern.compile("public\\s+(class|record|interface|enum)\\s+(\\w+)");
        Matcher m = p.matcher(code);
        if (m.find()) {
            return m.group(2);
        }
        // Fallback: any class/record/interface/enum
        p = Pattern.compile("(class|record|interface|enum)\\s+(\\w+)");
        m = p.matcher(code);
        if (m.find()) {
            return m.group(2);
        }
        return "UnknownClass";
    }

    private String extractPackage(String code) {
        Pattern p = Pattern.compile("package\\s+([\\w.]+)\\s*;");
        Matcher m = p.matcher(code);
        if (m.find()) {
            return m.group(1);
        }
        return "";
    }

    /** Minimal JSON string escaping. */
    private static String jsonEscape(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** Extract a top-level JSON string field value (unquotes it). */
    private static String extractJsonField(String json, String field) {
        Pattern p = Pattern.compile("\"" + field + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher m = p.matcher(json);
        if (m.find()) {
            return m.group(1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
                    .replace("\\t", "\t");
        }
        return null;
    }

    @Override
    public String toString() {
        return "CodeGenerationAction[spec="
                + specification.substring(0, Math.min(50, specification.length())) + "...]";
    }
}
