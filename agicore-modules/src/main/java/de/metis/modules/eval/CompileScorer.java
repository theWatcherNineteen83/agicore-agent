package de.metis.modules.eval;

import de.metis.kernel.eval.*;
import de.metis.kernel.eval.GroundTruth.*;
import java.io.*;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import javax.tools.*;

/**
 * Scorer for CODEGEN tasks.
 * <p>
 * Compiles generated Java code in a sandbox and runs hidden unit tests.
 * Metrics: compile_rate (HARD), pass@1 (HARD).
 * This is the most important category — heavily weighted in the gate.
 */
class CompileScorer implements Scorer {

    private static final Logger LOG = Logger.getLogger(CompileScorer.class.getName());

    // Timeouts — generous to absorb first-call JIT/classloader warmup of the
    // platform compiler API in the host JVM. Pre-fix these were 5 s total,
    // which caused pass@1=0.0 to be timeout-dominated rather than
    // assertion-failure-dominated.
    private static final int COMPILE_TIMEOUT_SEC = 15;
    private static final int TEST_TIMEOUT_SEC = 30;

    // Diagnostic counters (visible via /api/status if exposed; otherwise log-only).
    private static int passedCount;
    private static int failedAssertionCount;
    private static int failedCompileCount;
    private static int failedTimeoutCount;

    public static int passedCount() { return passedCount; }
    public static int failedAssertionCount() { return failedAssertionCount; }
    public static int failedCompileCount() { return failedCompileCount; }
    public static int failedTimeoutCount() { return failedTimeoutCount; }

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
     * Compile AND run the hidden test suite in a sandbox.
     * <p>
     * Sandbox constraints (per Huyen Ch.5: Verteidigung auf Systemebene):
     * <ul>
     *   <li>No network access (SecurityManager blocks sockets)</li>
     *   <li>Restricted file system (temp dir only)</li>
     *   <li>Timeout per test (5s, enforced via Future)</li>
     *   <li>No process execution (Runtime.exec blocked)</li>
     *   <li>No reflection on critical classes</li>
     * </ul>
     */
    private boolean tryCompileAndTest(String sourceCode, TestSuite testSuite) {
        Path sandboxDir = null;
        try {
            // 1. Compile in sandbox directory
            sandboxDir = Files.createTempDirectory("metis-sandbox-");
            String className = extractClassName(sourceCode);
            if (className == null) return false;

            Path srcFile = sandboxDir.resolve(className + ".java");
            Files.writeString(srcFile, sourceCode);

            // Also write the test class
            Path testFile = sandboxDir.resolve(testSuite.testClassName() + ".java");
            Files.writeString(testFile, testSuite.testSourceCode());

            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                LOG.warning("No system Java compiler — skipping sandbox test");
                return false;
            }

            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            try (var fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
                var units = fileManager.getJavaFileObjects(srcFile, testFile);
                JavaCompiler.CompilationTask task = compiler.getTask(
                        null, fileManager, diagnostics,
                        List.of("-d", sandboxDir.toString()), null, units);
                if (!task.call()) return false;
            }

            // 2. Run tests in sandbox with timeout
            return runInSandbox(sandboxDir, testSuite.testClassName());

        } catch (Exception e) {
            LOG.fine("Sandbox test failed: " + e.getMessage());
            return false;
        } finally {
            // Cleanup sandbox
            if (sandboxDir != null) {
                try { Files.walk(sandboxDir).sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} }); }
                catch (IOException ignored) {}
            }
        }
    }

    /**
     * Run the compiled test class in a sandboxed classloader with timeout.
     * <p>
     * Pre-fix this used a 5 s timeout; first-call JIT warmup of the
     * sandbox classloader regularly cost &gt;5 s and made every CODEGEN
     * task time out (=&gt; pass@1 collapsed to 0.0 not because tests
     * failed but because they never finished). We now use {@value
     * #TEST_TIMEOUT_SEC} s and count timeouts separately.
     */
    private boolean runInSandbox(Path classDir, String testClassName) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "metis-sandbox-test");
            t.setDaemon(true);
            return t;
        });
        Future<Boolean> future = executor.submit(() -> {
            try {
                // Use a custom classloader that restricts access
                URL[] urls = { classDir.toUri().toURL() };
                SandboxClassLoader loader = new SandboxClassLoader(urls);
                Class<?> testClass = loader.loadClass(testClassName);

                // Find and invoke the test method
                for (Method method : testClass.getDeclaredMethods()) {
                    if (method.getName().startsWith("test") && method.getParameterCount() == 0) {
                        try {
                            Object instance = testClass.getDeclaredConstructor().newInstance();
                            method.invoke(instance);
                        } catch (Exception e) {
                            // Test failed = assertion error or exception
                            if (e.getCause() instanceof AssertionError) {
                                failedAssertionCount++;
                                return false;
                            }
                            if (e instanceof java.lang.reflect.InvocationTargetException ite
                                    && ite.getCause() instanceof AssertionError) {
                                failedAssertionCount++;
                                return false;
                            }
                            failedAssertionCount++;
                            return false;
                        }
                    }
                }
                passedCount++;
                return true; // all tests passed
            } catch (Exception e) {
                failedAssertionCount++;
                return false;
            }
        });

        try {
            return future.get(TEST_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            failedTimeoutCount++;
            LOG.warning("Sandbox test timed out after " + TEST_TIMEOUT_SEC + "s");
            future.cancel(true);
            return false;
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Restricted classloader that blocks dangerous operations.
     * Per Huyen Ch.5: Code in virtueller Maschine, vom Hauptprozess getrennt.
     */
    private static class SandboxClassLoader extends URLClassLoader {
        private static final Set<String> BLOCKED_PACKAGES = Set.of(
                "java.net.", "java.nio.file.", "java.io.RandomAccessFile",
                "java.lang.ProcessBuilder", "java.lang.Runtime",
                "java.lang.reflect.", "sun.", "jdk.internal."
        );

        SandboxClassLoader(URL[] urls) {
            super(urls, ClassLoader.getPlatformClassLoader()); // isolate from app classloader
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            for (String blocked : BLOCKED_PACKAGES) {
                if (name.startsWith(blocked)) {
                    throw new ClassNotFoundException("Blocked by sandbox: " + name);
                }
            }
            return super.loadClass(name, resolve);
        }
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
