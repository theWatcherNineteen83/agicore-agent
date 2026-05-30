package de.metis.modules.hardware;

import de.metis.kernel.world.WorldModel;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Autonomous Java learning service for Zulu JDK 25 (Java 25 LTS).
 * <p>
 * Explores JDK tools via --help, tries safe commands in a sandbox,
 * remembers successful patterns as beliefs. Designed as a curiosity-driven
 * background learner that feeds the Kanban board.
 * <p>
 * ResourceType: CPU_HEAVY (compilation, javadoc parsing)
 * ServiceClass: STANDARD
 */
public class JavaLearningService {

    private static final Logger LOG = Logger.getLogger(JavaLearningService.class.getName());
    private static final Random RANDOM = new Random();

    /** JDK installation root. */
    private final Path javaHome;
    private final WorldModel worldModel;
    private final Path sandboxDir;
    private int commandsTried = 0;
    private int commandsSucceeded = 0;

    /** JDK tools to explore. */
    private static final List<String> JDK_TOOLS = List.of(
            "java", "javac", "jar", "javadoc", "jlink", "jpackage",
            "jwebserver", "jconsole", "jstat", "jmap", "jcmd",
            "jshell", "jdeps", "jdeprscan", "jfr", "jhsdb"
    );

    /** Safe exploration commands (no filesystem mutation outside sandbox). */
    private static final List<String> SAFE_PATTERNS = List.of(
            "--version",
            "--help",
            "-help",
            "--list-modules",
            "--describe-module java.base",
            "--show-module-resolution",
            "-version",
            "-XshowSettings:all",
            "-Xdiag"
    );

    public JavaLearningService(Path javaHome, WorldModel worldModel, Path sandboxDir) {
        this.javaHome = javaHome;
        this.worldModel = worldModel;
        this.sandboxDir = sandboxDir;
        try {
            Files.createDirectories(sandboxDir);
        } catch (IOException ignored) {}
    }

    /**
     * Default constructor for Zulu JDK 25 on miniedi.
     */
    public JavaLearningService(WorldModel worldModel) {
        this(Path.of("/usr/lib/jvm/zulu25.32.21-ca-jdk25.0.2-linux_x64"),
                worldModel,
                Path.of("/tmp/metis-java-sandbox"));
    }

    /**
     * Explore one JDK tool. Picks a random tool, runs a safe command,
     * parses output, and stores interesting findings as beliefs.
     *
     * @return number of new beliefs stored, or -1 on failure
     */
    public int exploreOneTool() {
        String tool = JDK_TOOLS.get(RANDOM.nextInt(JDK_TOOLS.size()));
        String pattern = SAFE_PATTERNS.get(RANDOM.nextInt(SAFE_PATTERNS.size()));
        Path toolPath = javaHome.resolve("bin").resolve(tool);

        if (!Files.exists(toolPath)) {
            LOG.fine("JDK tool not found: " + toolPath);
            return 0;
        }

        commandsTried++;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    toolPath.toString(), pattern);
            pb.directory(sandboxDir.toFile());
            pb.redirectErrorStream(true);

            Process proc = pb.start();
            boolean finished = proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                LOG.fine("Java tool timeout: " + tool + " " + pattern);
                return 0;
            }

            String output = new String(proc.getInputStream().readAllBytes()).trim();
            int exitCode = proc.exitValue();

            if (exitCode != 0 && output.length() < 20) {
                // Failed with no useful output
                return 0;
            }

            // Store the finding
            commandsSucceeded++;
            String belief = String.format("Java %s %s → exit=%d, %d chars output",
                    tool, pattern, exitCode, output.length());
            worldModel.update(belief, 0.85, "java-learning:" + tool, true);

            // If output is substantial, extract key lines as beliefs
            if (output.length() > 100) {
                List<String> keyLines = extractKeyLines(output);
                for (String line : keyLines) {
                    worldModel.update("Java-" + tool + ": " + line,
                            0.75, "java-learning:" + tool, true);
                }
            }

            LOG.info(() -> "JavaLearn [" + tool + " " + pattern + "]: exit=" + exitCode
                    + ", " + output.length() + " chars, +" + (1 + extractKeyLines(output).size()) + " beliefs");

            return 1;

        } catch (Exception e) {
            LOG.fine("Java exploration failed [" + tool + "]: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Try a more complex pattern: compile and run a small Java program.
     * Only attempted when basic exploration has succeeded enough.
     */
    public int tryCompileAndRun() {
        if (commandsSucceeded < 5) return 0; // Build confidence first

        String className = "Sandbox" + RANDOM.nextInt(1000);
        String source = """
                public class %s {
                    public static void main(String[] args) {
                        System.out.println("Java 25 sandbox: " + Runtime.version());
                        System.out.println("Available processors: " + Runtime.getRuntime().availableProcessors());
                        System.out.println("Max memory: " + Runtime.getRuntime().maxMemory() / 1024 / 1024 + " MB");
                    }
                }
                """.formatted(className);

        Path sourceFile = sandboxDir.resolve(className + ".java");
        try {
            Files.writeString(sourceFile, source);
            commandsTried++;

            // Compile
            Path javacPath = javaHome.resolve("bin").resolve("javac");
            ProcessBuilder compilePb = new ProcessBuilder(
                    javacPath.toString(), sourceFile.toString());
            compilePb.directory(sandboxDir.toFile());
            compilePb.redirectErrorStream(true);

            Process compileProc = compilePb.start();
            boolean compiled = compileProc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            if (!compiled || compileProc.exitValue() != 0) {
                String error = new String(compileProc.getInputStream().readAllBytes());
                LOG.fine("Java compile failed: " + error.substring(0, Math.min(100, error.length())));
                return 0;
            }

            // Run
            Path javaPath = javaHome.resolve("bin").resolve("java");
            ProcessBuilder runPb = new ProcessBuilder(
                    javaPath.toString(), "-cp", sandboxDir.toString(), className);
            runPb.directory(sandboxDir.toFile());
            runPb.redirectErrorStream(true);

            Process runProc = runPb.start();
            boolean ran = runProc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            if (!ran) {
                runProc.destroyForcibly();
                return 0;
            }

            String output = new String(runProc.getInputStream().readAllBytes()).trim();
            commandsSucceeded++;

            // Store as belief
            worldModel.update("Java sandbox compile+run success: " + className
                            + " → " + output.replace('\n', ' '),
                    0.9, "java-learning:sandbox", true);

            LOG.info("Java sandbox: " + className + " compiled & ran → " + output);
            return 1;

        } catch (Exception e) {
            LOG.fine("Java sandbox failed: " + e.getMessage());
            return 0;
        } finally {
            // Cleanup
            try { Files.deleteIfExists(sourceFile); } catch (IOException ignored) {}
            try { Files.deleteIfExists(sandboxDir.resolve(className + ".class")); } catch (IOException ignored) {}
        }
    }

    /**
     * Extract the most informative lines from tool output.
     */
    private List<String> extractKeyLines(String output) {
        return Arrays.stream(output.split("\n"))
                .map(String::trim)
                .filter(line -> line.length() > 20 && line.length() < 200)
                .filter(line -> !line.startsWith("Usage:") && !line.startsWith("  -"))
                .filter(line -> line.matches(".*[a-zA-Z]{5,}.*")) // has actual words
                .limit(5)
                .collect(Collectors.toList());
    }

    // ── accessors ──

    public int commandsTried() { return commandsTried; }
    public int commandsSucceeded() { return commandsSucceeded; }
    public double successRate() {
        return commandsTried == 0 ? 0 : (double) commandsSucceeded / commandsTried;
    }
}
