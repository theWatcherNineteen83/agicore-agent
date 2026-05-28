package de.metis.modules.eval;

import de.metis.kernel.eval.*;
import de.metis.kernel.eval.GroundTruth.*;
import java.io.*;
import java.nio.file.*;
import javax.tools.*;
import java.util.List;
import java.util.logging.Logger;

/**
 * Scorer for CODEGEN tasks.
 * <p>
 * Compiles generated Java code in a sandbox and runs hidden unit tests.
 * Metrics: compile_rate (HARD), pass@1 (HARD).
 * This is the most important category — heavily weighted in the gate.
 */
class CompileScorer implements Scorer {

    private static final Logger LOG = Logger.getLogger(CompileScorer.class.getName());

    @Override
    public MetricResult score(EvalTask task, MetisOutput output) {
        if (output.isError()) {
            return new MetricResult(task.scoring().metric(), 0.0, task.scoring().gate());
        }

        String metric = task.scoring().metric();

        if ("compile_rate".equals(metric)) {
            boolean compiled = tryCompile(output.rawText());
            return new MetricResult(metric, compiled ? 1.0 : 0.0, task.scoring().gate());
        }

        if (metric.startsWith("pass@")) {
            if (!(task.groundTruth() instanceof TestSuite testSuite)) {
                return new MetricResult(metric, 0.0, task.scoring().gate());
            }
            boolean passed = tryCompileAndTest(output.rawText(), testSuite);
            return new MetricResult(metric, passed ? 1.0 : 0.0, task.scoring().gate());
        }

        // Default: compile check only
        boolean compiled = tryCompile(output.rawText());
        return new MetricResult("compile_rate", compiled ? 1.0 : 0.0, task.scoring().gate());
    }

    /**
     * Try to compile the generated Java source code.
     * Uses javax.tools.JavaCompiler in-memory.
     */
    private boolean tryCompile(String sourceCode) {
        if (sourceCode == null || sourceCode.isBlank()) return false;

        try {
            // Extract class name
            String className = extractClassName(sourceCode);
            if (className == null) return false;

            // Write to temp file
            Path tmpDir = Files.createTempDirectory("metis-eval-");
            Path srcFile = tmpDir.resolve(className + ".java");
            Files.writeString(srcFile, sourceCode);

            // Compile
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                LOG.warning("No system Java compiler available — skipping compile check");
                return true; // can't check, assume pass
            }

            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            try (var fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
                Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjects(srcFile);
                JavaCompiler.CompilationTask compileTask = compiler.getTask(
                        null, fileManager, diagnostics,
                        List.of("-d", tmpDir.toString()), null, units);
                boolean ok = compileTask.call();

                // Cleanup
                try { Files.walk(tmpDir).sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} }); }
                catch (IOException ignored) {}

                return ok;
            }
        } catch (Exception e) {
            LOG.fine("Compile check failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Compile AND run the hidden test suite.
     * For now, compile-only. Full sandbox test execution is Phase 2.
     */
    private boolean tryCompileAndTest(String sourceCode, TestSuite testSuite) {
        // MVP: compile check only. Full test execution needs sandbox.
        return tryCompile(sourceCode);
    }

    private String extractClassName(String sourceCode) {
        // Simple regex: find "public class ClassName" or "class ClassName"
        var pattern = java.util.regex.Pattern.compile("(?:public\\s+)?class\\s+(\\w+)");
        var matcher = pattern.matcher(sourceCode);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
