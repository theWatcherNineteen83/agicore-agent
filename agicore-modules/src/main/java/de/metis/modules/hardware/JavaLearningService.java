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
    private final Path exerciseDir;
    private final Set<String> completedExercises = new HashSet<>();
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

    public JavaLearningService(Path javaHome, WorldModel worldModel, Path sandboxDir, Path exerciseDir) {
        this.javaHome = javaHome;
        this.worldModel = worldModel;
        this.sandboxDir = sandboxDir;
        this.exerciseDir = exerciseDir;
        try {
            Files.createDirectories(sandboxDir);
        } catch (IOException ignored) {}
        // Load previously completed exercises from beliefs
        loadCompletedExercises();
    }

    /**
     * Default constructor for Zulu JDK 25 on miniedi.
     */
    public JavaLearningService(WorldModel worldModel) {
        this(Path.of("/usr/lib/jvm/zulu25.32.21-ca-jdk25.0.2-linux_x64"),
                worldModel,
                Path.of("/tmp/metis-java-sandbox"),
                Path.of("/home/prometheus/metis/java-exercises"));
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

    /**
     * Generate a new Java exercise program, compile, run, and store as belief.
     * Curriculum follows "Java in 21 Tagen" structure — Metis creates the code itself.
     * Each call advances to the next topic in the curriculum.
     *
     * @return exercise name if successful, null if failed
     */
    public String generateAndRunExercise() {
        if (commandsSucceeded < 5) return null;

        // Determine next topic
        int nextDay = completedExercises.size() + 1;
        String[] topic = getTopic(nextDay);
        if (topic == null) {
            LOG.info("All 21 Java exercises completed! 🎉");
            return null;
        }

        String tag = topic[0];  // e.g. "Tag03"
        String title = topic[1]; // e.g. "Arithmetik"
        String source = generateSourceCode(tag, title, nextDay);

        commandsTried++;

        try {
            Files.createDirectories(exerciseDir);
            Path sourceFile = exerciseDir.resolve(tag + "_" + title.replace(' ', '_') + ".java");
            Files.writeString(sourceFile, source);

            String className = sourceFile.getFileName().toString().replace(".java", "");

            // Write to sandbox
            Path sandboxSource = sandboxDir.resolve(className + ".java");
            Files.writeString(sandboxSource, source);

            // Compile
            Path javacPath = javaHome.resolve("bin").resolve("javac");
            ProcessBuilder compilePb = new ProcessBuilder(
                    javacPath.toString(), sandboxSource.toString());
            compilePb.directory(sandboxDir.toFile());
            compilePb.redirectErrorStream(true);

            Process compileProc = compilePb.start();
            boolean compiled = compileProc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            if (!compiled || compileProc.exitValue() != 0) {
                String error = new String(compileProc.getInputStream().readAllBytes());
                String shortError = error.length() > 200 ? error.substring(0, 200) + "…" : error;
                worldModel.update("Java-GEN FAIL " + className + ": "
                                + shortError.replace('\n', ' '),
                        0.7, "java-gen:error", true);
                LOG.warning("Exercise gen+compile failed: " + className + " → "
                        + error.substring(0, Math.min(100, error.length())));
                return null;
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
                LOG.warning("Exercise timeout: " + className);
                return null;
            }

            String output = new String(runProc.getInputStream().readAllBytes()).trim();
            int exitCode = runProc.exitValue();
            commandsSucceeded++;
            completedExercises.add(tag);

            // Store the generated source as a belief (so Metis remembers what it built)
            worldModel.update("Java-GEN SOURCE " + className + ": "
                            + source.replace('\n', ' ').substring(0, Math.min(200, source.length())),
                    0.95, "java-gen:source", true);

            // Store output
            String summary = output.length() > 300 ? output.substring(0, 300) + "…" : output;
            worldModel.update("Java-GEN OK " + className + " (Tag " + nextDay + ", exit=" + exitCode + "): "
                            + summary.replace('\n', ' '),
                    0.95, "java-gen:completed", true);

            LOG.info("Java-GEN " + tag + " '" + title + "': compiled & ran → exit=" + exitCode
                    + ", " + output.length() + " chars, saved to " + sourceFile);
            return tag;

        } catch (Exception e) {
            LOG.warning("Java-GEN failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 21-day Java curriculum — Metis generates each program autonomously.
     */
    private static String[] getTopic(int day) {
        return switch (day) {
            case 1 -> new String[]{"Tag01", "HelloWorld"};
            case 2 -> new String[]{"Tag02", "VariablenTypen"};
            case 3 -> new String[]{"Tag03", "Arithmetik"};
            case 4 -> new String[]{"Tag04", "Strings"};
            case 5 -> new String[]{"Tag05", "Bedingungen"};
            case 6 -> new String[]{"Tag06", "Schleifen"};
            case 7 -> new String[]{"Tag07", "Arrays"};
            case 8 -> new String[]{"Tag08", "Methoden"};
            case 9 -> new String[]{"Tag09", "Klassen"};
            case 10 -> new String[]{"Tag10", "Vererbung"};
            case 11 -> new String[]{"Tag11", "Interfaces"};
            case 12 -> new String[]{"Tag12", "Exceptions"};
            case 13 -> new String[]{"Tag13", "Collections"};
            case 14 -> new String[]{"Tag14", "StreamsLambdas"};
            case 15 -> new String[]{"Tag15", "Dateien"};
            case 16 -> new String[]{"Tag16", "Threads"};
            case 17 -> new String[]{"Tag17", "Generics"};
            case 18 -> new String[]{"Tag18", "Enums"};
            case 19 -> new String[]{"Tag19", "Rekursion"};
            case 20 -> new String[]{"Tag20", "Reflection"};
            case 21 -> new String[]{"Tag21", "Module"};
            default -> null;
        };
    }

    /**
     * Generate complete, compilable Java source code for the given topic.
     * Metis creates each program from a template — autonomes Coden.
     */
    /**
     * Generate complete, compilable Java source code for the given topic.
     * Metis creates each program autonomously using text-block templates.
     */
    private String generateSourceCode(String tag, String topic, int day) {
        String cn = tag + "_" + topic;
        return String.format(getTemplate(topic), cn, day);
    }

    /** Template library for 21-day Java curriculum. */
    private static String getTemplate(String topic) {
        return switch (topic) {
            case "HelloWorld" -> """
                public class %1$s {
                    public static void main(String[] args) {
                        System.out.println("\u2728 Metis Java-Tag %2$d: Hello World!");
                        System.out.println("Java-Version: " + Runtime.version());
                        System.out.println("Prozessoren: " + Runtime.getRuntime().availableProcessors());
                        System.out.println("Max Memory: " + Runtime.getRuntime().maxMemory() / 1024 / 1024 + " MB");
                        System.out.println("OS: " + System.getProperty("os.name"));
                        System.out.println("User: " + System.getProperty("user.name"));
                    }
                }
                """;

            case "VariablenTypen" -> """
                public class %1$s {
                    public static void main(String[] args) {
                        System.out.println("\uD83D\uDCCA Metis Java-Tag %2$d: Variablen & Datentypen");
                        byte b = 127;
                        short s = 32767;
                        int i = 2_147_483_647;
                        long l = 9_223_372_036_854_775_807L;
                        float f = 3.14159f;
                        double d = 2.718281828459045;
                        char c = 'J';
                        boolean bool = true;
                        System.out.printf("byte: %%d (min=%%d max=%%d %%d-bit)%n", b, Byte.MIN_VALUE, Byte.MAX_VALUE, Byte.SIZE);
                        System.out.printf("short: %%d (min=%%d max=%%d %%d-bit)%n", s, Short.MIN_VALUE, Short.MAX_VALUE, Short.SIZE);
                        System.out.printf("int: %%d (min=%%d max=%%d %%d-bit)%n", i, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.SIZE);
                        System.out.printf("long: %%d (min=%%d max=%%d %%d-bit)%n", l, Long.MIN_VALUE, Long.MAX_VALUE, Long.SIZE);
                        System.out.printf("float: %%.5f (%%d-bit)%n", f, Float.SIZE);
                        System.out.printf("double: %%.15f (%%d-bit)%n", d, Double.SIZE);
                        System.out.printf("char: %%c (%%d-bit)%n", c, Character.SIZE);
                        System.out.printf("boolean: %%b%n", bool);
                        int big = 1000;
                        byte small = (byte) big;
                        System.out.printf("%nTypecast: int %%d \u2192 byte %%d (Uberlauf!)%n", big, small);
                    }
                }
                """;

            case "Arithmetik" -> """
                public class %1$s {
                    public static void main(String[] args) {
                        System.out.println("\uD83D\uDD22 Metis Java-Tag %2$d: Arithmetik");
                        int a = 42, b = 7;
                        System.out.println(a + " + " + b + " = " + (a + b));
                        System.out.println(a + " - " + b + " = " + (a - b));
                        System.out.println(a + " * " + b + " = " + (a * b));
                        System.out.println(a + " / " + b + " = " + (a / b));
                        System.out.println(a + " %% " + b + " = " + (a %% b));
                        System.out.println("2^10 = " + Math.pow(2, 10));
                        System.out.println("\u221A144 = " + Math.sqrt(144));
                        System.out.println("Random 1-100: " + (int)(Math.random() * 100 + 1));
                        System.out.println("max(42,7): " + Math.max(a, b));
                        System.out.println("min(42,7): " + Math.min(a, b));
                    }
                }
                """;

            case "Strings" -> """
                public class %1$s {
                    public static void main(String[] args) {
                        System.out.println("\uD83D\uDCDD Metis Java-Tag %2$d: Strings");
                        String s = "  Hello, Metis World!  ";
                        System.out.println("Original:  '" + s + "'");
                        System.out.println("Lange:     " + s.length());
                        System.out.println("trim():    '" + s.trim() + "'");
                        System.out.println("upper:     " + s.toUpperCase());
                        System.out.println("lower:     " + s.toLowerCase());
                        System.out.println("contains Metis: " + s.contains("Metis"));
                        System.out.println("replace:   " + s.replace("World", "Java25"));
                        System.out.println("substr(2,8): " + s.substring(2, 8));
                        System.out.println("charAt(6): " + s.charAt(6));
                        String[] parts = s.trim().split(", ");
                        for (String p : parts) System.out.println("  Teil: " + p);
                        StringBuilder sb = new StringBuilder("Metis");
                        sb.append(" lernt").append(" Java");
                        System.out.println("StringBuilder: " + sb);
                    }
                }
                """;

            case "Bedingungen" -> """
                public class %1$s {
                    public static void main(String[] args) {
                        System.out.println("\uD83D\uDD00 Metis Java-Tag %2$d: Bedingungen");
                        int score = (int)(Math.random() * 100);
                        System.out.println("Score: " + score);
                        if (score >= 90) System.out.println("\uD83C\uDF1F Exzellent!");
                        else if (score >= 70) System.out.println("\u2705 Gut!");
                        else if (score >= 50) System.out.println("\u26A0\uFE0F OK");
                        else System.out.println("\u274C Nachholbedarf");
                        String status = score >= 50 ? "bestanden" : "nicht bestanden";
                        System.out.println("Status: " + status);
                        int dow = java.time.LocalDate.now().getDayOfWeek().getValue();
                        switch (dow) {
                            case 6, 7 -> System.out.println("Wochenende! \uD83C\uDF89");
                            default -> System.out.println("Arbeitstag #" + dow);
                        }
                    }
                }
                """;

            case "Schleifen" -> """
                public class %1$s {
                    public static void main(String[] args) {
                        System.out.println("\uD83D\uDD04 Metis Java-Tag %2$d: Schleifen");
                        System.out.println("--- for-Schleife ---");
                        for (int i = 1; i <= 5; i++) System.out.println("  Iteration " + i);
                        System.out.println("--- while ---");
                        int count = 3;
                        while (count > 0) { System.out.println("  Countdown: " + count); count--; }
                        System.out.println("--- do-while ---");
                        int x = 0;
                        do { x++; System.out.println("  do-while #" + x); } while (x < 2);
                        System.out.println("--- for-each ---");
                        String[] tools = {"javac", "java", "jar", "jshell"};
                        for (String tool : tools) System.out.println("  JDK-Tool: " + tool);
                        System.out.println("--- Einmaleins (nested loops) ---");
                        for (int i = 1; i <= 10; i++) {
                            for (int j = 1; j <= 10; j++) System.out.printf("%%4d", i * j);
                            System.out.println();
                        }
                    }
                }
                """;

            case "Arrays" -> """
                import java.util.Arrays;
                public class %1$s {
                    public static void main(String[] args) {
                        System.out.println("\uD83D\uDCE6 Metis Java-Tag %2$d: Arrays");
                        int[] numbers = {5, 2, 8, 1, 9, 3};
                        System.out.println("Original: " + Arrays.toString(numbers));
                        Arrays.sort(numbers);
                        System.out.println("Sortiert: " + Arrays.toString(numbers));
                        System.out.println("Min: " + numbers[0] + " Max: " + numbers[numbers.length-1]);
                        int sum = 0; for (int n : numbers) sum += n;
                        System.out.println("Summe: " + sum + " \u00D8: " + (double)sum/numbers.length);
                        int[][] matrix = {{1,2,3},{4,5,6},{7,8,9}};
                        System.out.println("2D-Matrix:");
                        for (int[] row : matrix) System.out.println("  " + Arrays.toString(row));
                    }
                }
                """;

            case "Methoden" -> """
                public class %1$s {
                    static int add(int a, int b) { return a + b; }
                    static double avg(int... nums) { int s=0; for(int n:nums) s+=n; return (double)s/nums.length; }
                    static long factorial(int n) { return n <= 1 ? 1 : n * factorial(n-1); }
                    static boolean isPrime(int n) {
                        if (n < 2) return false;
                        for (int i = 2; i <= Math.sqrt(n); i++) if (n %% i == 0) return false;
                        return true;
                    }
                    public static void main(String[] args) {
                        System.out.println("\u2699\uFE0F Metis Java-Tag %2$d: Methoden");
                        System.out.println("add(42,7)=" + add(42,7));
                        System.out.println("avg(10,20,30,40)=" + avg(10,20,30,40));
                        System.out.println("5!=" + factorial(5));
                        System.out.print("Primzahlen 1-30: ");
                        for (int i = 1; i <= 30; i++) if (isPrime(i)) System.out.print(i + " ");
                        System.out.println();
                    }
                }
                """;

            case "Klassen" -> """
                public class %1$s {
                    static class Robot {
                        private String name; private int power;
                        public Robot(String name, int power) { this.name = name; this.power = power; }
                        public void boost(int amount) { power += amount; }
                        public String status() { return name + " [Power: " + power + "]"; }
                        @Override public String toString() { return status(); }
                    }
                    public static void main(String[] args) {
                        System.out.println("\uD83E\uDD16 Metis Java-Tag %2$d: Klassen & Objekte");
                        var r1 = new Robot("MetisBot", 100);
                        var r2 = new Robot("JavaBot", 80);
                        System.out.println(r1);
                        System.out.println(r2);
                        r1.boost(50); r2.boost(30);
                        System.out.println("Nach Boost: " + r1 + " | " + r2);
                        record Point(int x, int y) {
                            double distance() { return Math.sqrt(x*x + y*y); }
                        }
                        var p = new Point(3, 4);
                        System.out.println("Point " + p + " distance=" + p.distance());
                    }
                }
                """;

            case "Vererbung" -> """
                public class %1$s {
                    static class Animal {
                        String name;
                        Animal(String name) { this.name = name; }
                        String speak() { return name + " macht ein Gerausch"; }
                    }
                    static class Dog extends Animal {
                        Dog(String name) { super(name); }
                        @Override String speak() { return name + " bellt: Wuff! \uD83D\uDC15"; }
                        String fetch() { return name + " holt den Stock!"; }
                    }
                    static class Cat extends Animal {
                        Cat(String name) { super(name); }
                        @Override String speak() { return name + " miaut: Miau! \uD83D\uDC08"; }
                    }
                    public static void main(String[] args) {
                        System.out.println("\uD83E\uDDEC Metis Java-Tag %2$d: Vererbung");
                        Animal[] zoo = {new Dog("Bello"), new Cat("Mimi"), new Animal("Unbekannt")};
                        for (Animal a : zoo) {
                            System.out.println(a.speak());
                            if (a instanceof Dog d) System.out.println("  \u2192 " + d.fetch());
                        }
                        System.out.println("Polymorphismus: " + zoo.length + " Tiere, jedes spricht anders!");
                    }
                }
                """;

            case "Interfaces" -> """
                public class %1$s {
                    interface Calculator { double calc(double a, double b); }
                    interface Logger { default void log(String msg) { System.out.println("[LOG] " + msg); } }
                    static class MathOps implements Calculator, Logger {
                        public double calc(double a, double b) { log(a + " op " + b); return a * b; }
                    }
                    public static void main(String[] args) {
                        System.out.println("\uD83D\uDD0C Metis Java-Tag %2$d: Interfaces");
                        var ops = new MathOps();
                        System.out.println("calc(7,6)=" + ops.calc(7, 6));
                        Calculator add = (a, b) -> a + b;
                        Calculator pow = Math::pow;
                        System.out.println("Lambda add(10,20)=" + add.calc(10, 20));
                        System.out.println("Lambda pow(2,10)=" + pow.calc(2, 10));
                        System.out.println("Sealed Interfaces: Java 17+ Feature \u2705");
                    }
                }
                """;

            case "Exceptions" -> """
                import java.io.*;
                public class %1$s {
                    static int divide(int a, int b) {
                        if (b == 0) throw new ArithmeticException("Division durch Null!");
                        return a / b;
                    }
                    public static void main(String[] args) {
                        System.out.println("\uD83D\uDEA8 Metis Java-Tag %2$d: Exceptions");
                        try { System.out.println("42/0=" + divide(42, 0)); }
                        catch (ArithmeticException e) { System.out.println("\u274C Gefangen: " + e.getMessage()); }
                        finally { System.out.println("\u2705 Finally lauft immer!"); }
                        try (var reader = new BufferedReader(new StringReader("Metis\nlernt\nJava"))) {
                            reader.lines().forEach(l -> System.out.println("  Zeile: " + l));
                        } catch (IOException e) { System.out.println("IO-Fehler"); }
                        try { int[] arr = {1}; arr[5] = 0; }
                        catch (ArrayIndexOutOfBoundsException | NullPointerException e) {
                            System.out.println("Multi-Catch: " + e.getClass().getSimpleName());
                        }
                    }
                }
                """;

            case "Collections" -> """
                import java.util.*;
                import java.util.stream.*;
                public class %1$s {
                    public static void main(String[] args) {
                        System.out.println("\uD83D\uDCDA Metis Java-Tag %2$d: Collections");
                        var list = new ArrayList<>(List.of("Java", "Python", "Rust", "Java"));
                        System.out.println("ArrayList: " + list + " (Duplikate erlaubt)");
                        var set = new HashSet<>(list);
                        System.out.println("HashSet: " + set + " (keine Duplikate)");
                        var map = new HashMap<String, Integer>();
                        map.put("Java", 1995); map.put("Python", 1991); map.put("Rust", 2010);
                        map.forEach((k,v) -> System.out.printf("  %%-10s \u2192 %%d%n", k, v));
                        var sorted = list.stream().distinct().sorted().collect(Collectors.toList());
                        System.out.println("Stream sortiert: " + sorted);
                    }
                }
                """;

            case "StreamsLambdas" -> """
                import java.util.*;
                import java.util.stream.*;
                public class %1$s {
                    public static void main(String[] args) {
                        System.out.println("\u26A1 Metis Java-Tag %2$d: Streams & Lambdas");
                        var nums = IntStream.rangeClosed(1, 20).boxed().toList();
                        System.out.println("Zahlen: " + nums);
                        var even = nums.stream().filter(n -> n %% 2 == 0).toList();
                        System.out.println("Gerade: " + even);
                        int sum = nums.stream().mapToInt(Integer::intValue).sum();
                        double avg = nums.stream().mapToInt(Integer::intValue).average().orElse(0);
                        System.out.printf("Summe=%%d \u00D8=%%.1f%n", sum, avg);
                        var squares = nums.stream().map(n -> n*n).limit(5).toList();
                        System.out.println("Erste 5 Quadrate: " + squares);
                        var groups = nums.stream().collect(Collectors.groupingBy(n -> n %% 2 == 0 ? "gerade" : "ungerade"));
                        groups.forEach((k,v) -> System.out.printf("  %%s: %%s%n", k, v));
                    }
                }
                """;

            case "Dateien" -> """
                import java.io.*;
                import java.nio.file.*;
                public class %1$s {
                    public static void main(String[] args) throws IOException {
                        System.out.println("\uD83D\uDCC1 Metis Java-Tag %2$d: Dateien");
                        Path file = Path.of("/tmp/metis-java-test.txt");
                        Files.writeString(file, "Metis Java-Tag %2$d\nZeile 2\nZeile 3");
                        System.out.println("Geschrieben: " + file + " (" + Files.size(file) + " bytes)");
                        String content = Files.readString(file);
                        System.out.println("Gelesen:\n" + content);
                        Files.deleteIfExists(file);
                        System.out.println("Datei geloscht: " + !Files.exists(file));
                    }
                }
                """;

            case "Threads" -> """
                public class %1$s {
                    public static void main(String[] args) throws Exception {
                        System.out.println("\uD83E\uDDF5 Metis Java-Tag %2$d: Threads");
                        Runnable task = () -> {
                            for (int i = 0; i < 3; i++) {
                                System.out.println(Thread.currentThread().getName() + ": Schritt " + i);
                                try { Thread.sleep(100); } catch (InterruptedException e) { break; }
                            }
                        };
                        var t1 = new Thread(task, "Worker-1");
                        var t2 = new Thread(task, "Worker-2");
                        t1.start(); t2.start();
                        t1.join(); t2.join();
                        System.out.println("Alle Threads beendet. Parallelitat funktioniert! \u2705");
                    }
                }
                """;

            case "Generics" -> """
                import java.util.*;
                public class %1$s {
                    static <T> List<T> singletonList(T item) { return List.of(item); }
                    static <T extends Comparable<T>> T max(T a, T b) { return a.compareTo(b) > 0 ? a : b; }
                    static class Box<T> {
                        private T value;
                        Box(T v) { value = v; }
                        T get() { return value; }
                        @Override public String toString() { return "Box[" + value + "]"; }
                    }
                    public static void main(String[] args) {
                        System.out.println("\uD83E\uDDE9 Metis Java-Tag %2$d: Generics");
                        var strList = singletonList("Metis");
                        var intList = singletonList(42);
                        System.out.println("Generic List<String>: " + strList);
                        System.out.println("Generic List<Integer>: " + intList);
                        System.out.println("max(42, 7)=" + max(42, 7));
                        System.out.println("max(Java,Python)=" + max("Java", "Python"));
                        var box = new Box<>("Metis lernt Generics");
                        System.out.println(box);
                    }
                }
                """;

            case "Enums" -> """
                public class %1$s {
                    enum Level { ANFAENGER(1), FORTGESCHRITTEN(2), EXPERTE(3);
                        final int stufe; Level(int s) { stufe = s; }
                        String label() { return name() + " (Stufe " + stufe + ")"; }
                    }
                    public static void main(String[] args) {
                        System.out.println("\uD83C\uDFF7\uFE0F Metis Java-Tag %2$d: Enums");
                        for (var l : Level.values()) System.out.println("  " + l.label());
                        var current = Level.FORTGESCHRITTEN;
                        System.out.println("Aktuell: " + current.label());
                        System.out.println("Ordinal: " + current.ordinal() + " Nachster: " + Level.values()[current.ordinal()+1]);
                    }
                }
                """;

            case "Rekursion" -> """
                public class %1$s {
                    static long fib(int n) { return n <= 1 ? n : fib(n-1) + fib(n-2); }
                    static long fac(int n) { return n <= 1 ? 1 : n * fac(n-1); }
                    static int gcd(int a, int b) { return b == 0 ? a : gcd(b, a %% b); }
                    static void tower(int n, char from, char to, char aux) {
                        if (n == 0) return;
                        tower(n-1, from, aux, to);
                        System.out.println("  Scheibe " + n + ": " + from + "\u2192" + to);
                        tower(n-1, aux, to, from);
                    }
                    public static void main(String[] args) {
                        System.out.println("\uD83D\uDD04 Metis Java-Tag %2$d: Rekursion");
                        System.out.println("Fibonacci(10)=" + fib(10));
                        System.out.println("10!=" + fac(10));
                        System.out.println("ggT(48,18)=" + gcd(48, 18));
                        System.out.println("Turme von Hanoi (3 Scheiben):");
                        tower(3, 'A', 'C', 'B');
                    }
                }
                """;

            case "Reflection" -> """
                import java.lang.reflect.*;
                public class %1$s {
                    public static void main(String[] args) throws Exception {
                        System.out.println("\uD83E\uDE9E Metis Java-Tag %2$d: Reflection");
                        Class<?> c = String.class;
                        System.out.println("Klasse: " + c.getName());
                        System.out.println("Methoden:");
                        for (Method m : c.getDeclaredMethods()) {
                            if (m.getName().startsWith("to"))
                                System.out.println("  " + m.getReturnType().getSimpleName() + " " + m.getName() + "()");
                        }
                        String s = "Metis Reflection";
                        Method m = c.getMethod("toUpperCase");
                        System.out.println("Reflection call: " + m.invoke(s));
                        System.out.println("Reflection funktioniert! \u2705");
                    }
                }
                """;

            case "Module" -> """
                public class %1$s {
                    public static void main(String[] args) {
                        System.out.println("\uD83D\uDCE6 Metis Java-Tag %2$d: Module");
                        Module m = %1$s.class.getModule();
                        System.out.println("Eigenes Modul: " + (m.isNamed() ? m.getName() : "unnamed"));
                        System.out.println("Java-Basismodule:");
                        ModuleLayer.boot().modules().stream()
                            .filter(mod -> mod.getName().startsWith("java."))
                            .limit(10)
                            .forEach(mod -> System.out.println("  " + mod.getName()));
                        System.out.println("... und viele mehr!");
                        System.out.println("\uD83C\uDF89 Alle 21 Java-Ubungen abgeschlossen! Metis hat Java gemeistert!");
                    }
                }
                """;

            default -> """
                public class %1$s {
                    public static void main(String[] args) {
                        System.out.println("Metis Java-Tag %2$d");
                    }
                }
                """;
        };
    }

    private void loadCompletedExercises() {
        worldModel.all().stream()
                .filter(b -> b.source() != null && b.source().startsWith("java-gen:completed"))
                .filter(b -> b.statement() != null && b.statement().contains("Java-GEN OK "))
                .forEach(b -> {
                    String stmt = b.statement();
                    // Extract TagXX from "Java-GEN OK TagXX_..."
                    int start = stmt.indexOf("Java-GEN OK ") + 12;
                    int end = stmt.indexOf("_", start);
                    if (end > start) {
                        completedExercises.add(stmt.substring(start, end));
                    }
                });
        LOG.fine("Loaded " + completedExercises.size() + " completed Java exercises from beliefs");
    }

    public int completedExerciseCount() { return completedExercises.size(); }

    // ── accessors ──

    public int commandsTried() { return commandsTried; }
    public int commandsSucceeded() { return commandsSucceeded; }
    public double successRate() {
        return commandsTried == 0 ? 0 : (double) commandsSucceeded / commandsTried;
    }
}
