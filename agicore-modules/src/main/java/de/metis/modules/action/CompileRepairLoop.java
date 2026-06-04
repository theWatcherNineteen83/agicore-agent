package de.metis.modules.action;

import javax.tools.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Phase 12d — Compile Repair Loop mit Java Compiler API.
 *
 * <p>Nutzt javax.tools.JavaCompiler fuer strukturierte Diagnostics
 * und einen bis zu 3-fachen Repair-Versuch (pass@3).
 * Kein Maven-Parsing, kein grep — saubere Objekte.
 */
public class CompileRepairLoop {

    private static final Logger LOG = Logger.getLogger(CompileRepairLoop.class.getName());

    private final String ollamaUrl;
    private final String model;
    private final String classpath;
    private final int maxAttempts;

    public CompileRepairLoop(String ollamaUrl, String model, String classpath, int maxAttempts) {
        this.ollamaUrl = ollamaUrl;
        this.model = model;
        this.classpath = classpath;
        this.maxAttempts = maxAttempts;
    }

    public record CompileResult(boolean success, String compiledCode, String diagnostics, int attempts) {}

    /**
     * Fuehrt den Repair-Loop aus: generate -> compile -> feedback -> retry (bis maxAttempts).
     */
    public CompileResult repair(String taskPrompt, String targetClassName) {
        String currentCode = null;
        String lastDiagnostics = "";
        int attempts = 0;

        for (int i = 0; i < maxAttempts; i++) {
            attempts++;

            // Generate code (initial or with error feedback)
            String prompt = currentCode == null
                    ? buildInitialPrompt(taskPrompt, targetClassName)
                    : buildRepairPrompt(taskPrompt, targetClassName, lastDiagnostics, currentCode);

            currentCode = generateCode(prompt);
            if (currentCode == null || currentCode.isBlank()) {
                LOG.warning("CompileRepair: attempt " + i + " returned empty code");
                continue;
            }

            // Compile via Java Compiler API
            var diagResult = compileWithDiagnostics(currentCode, targetClassName);
            if (diagResult.success()) {
                LOG.info("CompileRepair: pass@" + (i + 1) + " after " + attempts + " attempt(s)");
                return new CompileResult(true, currentCode, "", attempts);
            }

            lastDiagnostics = diagResult.diagnostics();
            LOG.fine("CompileRepair: attempt " + (i + 1) + " failed: " + lastDiagnostics.substring(0,
                    Math.min(200, lastDiagnostics.length())) + "...");
        }

        LOG.warning("CompileRepair: all " + attempts + " attempts failed");
        return new CompileResult(false, currentCode, lastDiagnostics, attempts);
    }

    /**
     * Kompiliert einen Java-Code-String und gibt strukturierte Diagnostics zurueck.
     */
    public record DiagResult(boolean success, String diagnostics) {}

    public DiagResult compileWithDiagnostics(String code, String className) {
        try {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                return new DiagResult(false, "No system Java compiler available (not running on JDK)");
            }

            DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
            StandardJavaFileManager fm = compiler.getStandardFileManager(diags, null, java.nio.charset.StandardCharsets.UTF_8);

            // Write code to temp file
            Path tmpDir = Files.createTempDirectory("metis-compile-");
            Path sourceFile = tmpDir.resolve(className + ".java");
            Files.writeString(sourceFile, code);

            Path outDir = tmpDir.resolve("out");
            Files.createDirectories(outDir);

            var units = fm.getJavaFileObjects(sourceFile.toFile());

            List<String> options = new ArrayList<>();
            options.add("-d");
            options.add(outDir.toString());
            options.add("-source");
            options.add("25");
            options.add("-target");
            options.add("25");
            if (classpath != null && !classpath.isBlank()) {
                options.add("-classpath");
                options.add(classpath);
            }

            boolean ok = compiler.getTask(null, fm, diags,
                    options, null, units).call();

            fm.close();

            // Extract error diagnostics only
            String errorDiags = diags.getDiagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .map(d -> "Zeile " + d.getLineNumber() + ", Spalte " + d.getColumnNumber()
                            + ": " + d.getMessage(null).replace("\n", " "))
                    .collect(Collectors.joining("\n"));

            // Cleanup temp files
            deleteDirectory(tmpDir);

            if (ok) {
                return new DiagResult(true, "");
            }
            return new DiagResult(false, errorDiags.isEmpty() ? "Unknown compile error" : errorDiags);

        } catch (Exception e) {
            return new DiagResult(false, "Compiler API error: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private String generateCode(String prompt) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var requestNode = mapper.createObjectNode();
            requestNode.put("model", model);
            requestNode.put("prompt", prompt);
            requestNode.put("stream", false);
            var opts = mapper.createObjectNode();
            opts.put("temperature", 0.15);
            opts.put("num_predict", 2048);
            requestNode.set("options", opts);

            var client = java.net.http.HttpClient.newHttpClient();
            var req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(ollamaUrl))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(requestNode)))
                    .timeout(java.time.Duration.ofSeconds(60)).build();

            var resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            String text = mapper.readTree(resp.body()).path("response").asText("").trim();

            // Extract code from markdown fences
            return stripCodeFences(text);
        } catch (Exception e) {
            LOG.warning("CompileRepair: Ollama call failed: " + e.getMessage());
            return null;
        }
    }

    private String stripCodeFences(String text) {
        int cb = text.indexOf("```java");
        if (cb >= 0) {
            int ce = text.indexOf("```", cb + 7);
            if (ce > cb) return text.substring(cb + 7, ce).trim();
        }
        int cb2 = text.indexOf("```");
        if (cb2 >= 0) {
            int ce2 = text.indexOf("```", cb2 + 3);
            if (ce2 > cb2) return text.substring(cb2 + 3, ce2).trim();
        }
        return text;
    }

    private String buildInitialPrompt(String task, String className) {
        return "Generate a Java class " + className + " that solves the following task.\n\n"
                + "TASK: " + task + "\n\n"
                + "Return ONLY the Java code inside ```java ... ```. "
                + "No explanation, no imports besides java.util.* and java.io.*. "
                + "The class must compile on Java 25.";
    }

    private String buildRepairPrompt(String task, String className, String errors, String previousCode) {
        return "Fix the following Java class " + className + ". It must compile.\n\n"
                + "TASK: " + task + "\n\n"
                + "PREVIOUS CODE (with errors):\n```java\n" + previousCode + "\n```\n\n"
                + "COMPILER ERRORS:\n" + errors + "\n\n"
                + "Fix ALL compiler errors and return ONLY the corrected code inside ```java ... ```. "
                + "No explanation. Keep the same class name and method signatures.";
    }

    private void deleteDirectory(Path dir) {
        try (var files = Files.walk(dir)) {
            files.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }
}
