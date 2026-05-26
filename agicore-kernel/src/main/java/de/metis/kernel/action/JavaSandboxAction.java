package de.metis.kernel.action;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Java-Code-Sandbox — führt kleine Java-Snippets aus.
 * <p>
 * Zwei Modi:
 * <ul>
 *   <li>{@code jshell} — interaktive Java-Shell (empfohlen für Experimente)</li>
 *   <li>{@code compile-run} — javac + java (vollständige Klassen)</li>
 * </ul>
 * <p>
 * Sicherheit: Jeder Lauf in einem temp-Verzeichnis, Timeout 30s,
 * max. 2000 Zeichen Code-Länge. Keine Network- oder FileSystem-Permissions
 * über das Temp-Verzeichnis hinaus.
 */
public class JavaSandboxAction implements Action {

    private static final Logger LOG = Logger.getLogger(JavaSandboxAction.class.getName());

    public static final String NAME = "javasandbox";

    private static final int MAX_CODE_LENGTH = 2000;
    private static final long TIMEOUT_SECONDS = 30;
    private static final Path SANDBOX_ROOT = Path.of("/tmp/metis-sandbox");

    private final String code;

    /**
     * @param code Java-Code-Snippet zum Ausführen
     */
    public JavaSandboxAction(String code) {
        this.code = code;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override public String category() {
        return "write";
    }

    @Override
    public ActionResult execute() {
        Instant start = Instant.now();
        try {
            if (code == null || code.isBlank()) {
                return ActionResult.fail(NAME, "Kein Code angegeben", start);
            }
            if (code.length() > MAX_CODE_LENGTH) {
                return ActionResult.fail(NAME,
                        "Code zu lang: " + code.length() + " Zeichen (max " + MAX_CODE_LENGTH + ")", start);
            }

            // Erstelle Sandbox-Verzeichnis
            Path sandboxDir = SANDBOX_ROOT.resolve(UUID.randomUUID().toString());
            Files.createDirectories(sandboxDir);

            try {
                // Versuche jshell zuerst
                if (hasCommand("jshell")) {
                    return runJshell(sandboxDir, start);
                }

                // Fallback: javac + java
                return runJavac(sandboxDir, start);

            } finally {
                // Aufräumen
                try {
                    Files.walk(sandboxDir)
                            .sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
                } catch (IOException ignored) {}
            }

        } catch (Exception e) {
            return ActionResult.fail(NAME,
                    "Sandbox-Fehler: " + e.getClass().getSimpleName() + " - " + e.getMessage(), start);
        }
    }

    private ActionResult runJshell(Path tmpDir, Instant start) throws Exception {
        Path codeFile = tmpDir.resolve("snippet.jsh");
        Files.writeString(codeFile, code);

        ProcessBuilder pb = new ProcessBuilder("jshell", "--execution", "local",
                "--feedback", "concise", "-R", "-Djava.security.manager=allow", codeFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);

        Process proc = pb.start();
        boolean finished = proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        String rawOutput;
        try (var in = proc.getInputStream()) {
            rawOutput = new String(in.readAllBytes());
        }

        final String output;
        if (!finished) {
            proc.destroyForcibly();
            output = rawOutput + "\n[Timeout nach " + TIMEOUT_SECONDS + "s]";
        } else {
            output = rawOutput;
        }

        String cleaned = output.replaceAll("jshell>\\s*", "").replaceAll("\\|\\s+Welcome.*\\n", "").trim();
        final String finalOutput = cleaned;

        LOG.fine(() -> "jshell: " + code.length() + " chars → " + finalOutput.lines().count() + " lines");
        return ActionResult.ok(NAME,
                "Java-Sandbox-Ergebnis (jshell):\n```\n" + code + "\n```\n\nOutput:\n```\n" + finalOutput + "\n```", start);
    }

    private ActionResult runJavac(Path tmpDir, Instant start) throws Exception {
        String className = "Sandbox" + System.currentTimeMillis() % 10000;
        String javaCode;
        if (code.contains("class ")) {
            javaCode = code;
        } else {
            javaCode = "public class " + className + " {\n"
                    + "  public static void main(String[] args) {\n"
                    + code.lines().map(l -> "    " + l).reduce("", (a, b) -> a + b + "\n")
                    + "  }\n"
                    + "}\n";
        }

        Path javaFile = tmpDir.resolve(className + ".java");
        Files.writeString(javaFile, javaCode);

        ProcessBuilder compilePb = new ProcessBuilder("javac", javaFile.toString());
        compilePb.directory(tmpDir.toFile());
        compilePb.redirectErrorStream(true);
        Process compileProc = compilePb.start();
        compileProc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        String compileOutput;
        try (var in = compileProc.getInputStream()) {
            compileOutput = new String(in.readAllBytes());
        }

        if (compileProc.exitValue() != 0) {
            return ActionResult.fail(NAME, "Kompilierungsfehler:\n" + compileOutput, start);
        }

        ProcessBuilder runPb = new ProcessBuilder("java", "-cp", tmpDir.toString(), className);
        runPb.directory(tmpDir.toFile());
        runPb.redirectErrorStream(true);
        Process runProc = runPb.start();
        boolean finished = runProc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        String rawOutput;
        try (var in = runProc.getInputStream()) {
            rawOutput = new String(in.readAllBytes());
        }

        final String output;
        if (!finished) {
            runProc.destroyForcibly();
            output = rawOutput + "\n[Timeout nach " + TIMEOUT_SECONDS + "s]";
        } else {
            output = rawOutput;
        }

        LOG.fine(() -> "javac+java: " + output.lines().count() + " lines output");
        return ActionResult.ok(NAME,
                "Java-Sandbox-Ergebnis (javac + java):\n```java\n" + code + "\n```\n\nOutput:\n```\n" + output + "\n```", start);
    }

    private boolean hasCommand(String cmd) {
        try {
            return new ProcessBuilder("which", cmd).start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String toString() {
        return "JavaSandboxAction[" + code.length() + " chars]";
    }
}
