package de.metis.modules.evolution;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Phase 12d — Leichter Code-Smell-Detektor ohne externen Parser.
 *
 * <p>Erkennt per Regex + Zeilen-Zaehlung:
 * <ul>
 *   <li>Lange Methoden (&gt;50 Zeilen) — schwer wartbar</li>
 *   <li>Klassen mit vielen Methoden (&gt;15) — zu viele Verantwortlichkeiten</li>
 *   <li>Tiefe Verschachtelung (&gt;4 Ebenen) — schwer lesbar</li>
 *   <li>Magic Numbers (mehr als 3 pro 100 Zeilen)</li>
 * </ul>
 *
 * <p>Ergebnis: Refactoring-Vorschlaege als FeatureProposals,
 * die der GapAnalyzer in den Kanban-Flow einspeisen kann.
 */
public class RefactorProposal {

    private static final Logger LOG = Logger.getLogger(RefactorProposal.class.getName());

    // Schwellwerte
    private static final int MAX_METHOD_LINES = 50;
    private static final int MAX_METHODS_PER_CLASS = 15;
    private static final int MAX_NESTING_DEPTH = 4;
    private static final int MAX_MAGIC_NUMBERS_PER_100L = 3;

    /** Ein konkreter Refactoring-Vorschlag. */
    public record Smell(
            String className,
            String filePath,
            SmellType type,
            String description,
            int severity  // 0-100
    ) {}

    public enum SmellType {
        LONG_METHOD("LongMethod", "Methode zu lang"),
        TOO_MANY_METHODS("TooManyMethods", "Zu viele Methoden"),
        DEEP_NESTING("DeepNesting", "Tiefe Verschachtelung"),
        MAGIC_NUMBERS("MagicNumbers", "Zu viele Magic Numbers");

        private final String id;
        private final String label;
        SmellType(String id, String label) { this.id = id; this.label = label; }
        public String id() { return id; }
        public String label() { return label; }
    }

    private final Path sourceRoot;

    public RefactorProposal() {
        this(Path.of("agicore-modules/src/main/java"));
    }

    public RefactorProposal(Path sourceRoot) {
        this.sourceRoot = sourceRoot;
    }

    /**
     * Analysiert alle Java-Dateien im Source-Root und
     * gibt gefundene Smells zurueck.
     */
    public List<Smell> analyze() {
        List<Smell> smells = new ArrayList<>();
        List<Path> files = findJavaFiles(sourceRoot);

        for (Path file : files) {
            try {
                List<String> lines = Files.readAllLines(file);
                String content = String.join("\n", lines);
                String className = className(file);
                String relPath = file.toString();

                // 1. Methodenlaenge
                smells.addAll(detectLongMethods(className, relPath, lines));

                // 2. Methodenanzahl
                smells.addAll(detectManyMethods(className, relPath, lines));

                // 3. Nesting-Tiefe
                smells.addAll(detectDeepNesting(className, relPath, lines));

                // 4. Magic Numbers
                smells.addAll(detectMagicNumbers(className, relPath, content, lines.size()));

            } catch (IOException e) {
                LOG.fine("RefactorProposal: skip " + file + " — " + e.getMessage());
            }
        }

        if (!smells.isEmpty()) {
            LOG.info("RefactorProposal: " + smells.size() + " smells in "
                    + files.size() + " files");
        }
        return smells;
    }

    /**
     * Gibt Smells als Feature-Vorschlaege zurueck.
     */
    public List<GapAnalyzer.FeatureProposal> asFeatureProposals() {
        List<Smell> smells = analyze();
        return smells.stream()
                .map(s -> new GapAnalyzer.FeatureProposal(
                        "refactor_" + s.type().id() + "_"
                                + s.className().toLowerCase(),
                        s.className() + ": " + s.description(),
                        "Refactor " + s.type().label() + " in " + s.className(),
                        s.filePath(), s.severity()))
                .distinct()
                .sorted(Comparator.comparingInt(GapAnalyzer.FeatureProposal::priority).reversed())
                .toList();
    }

    // ── Smell-Detektoren ────────────────────────────────────

    private List<Smell> detectLongMethods(String cls, String path, List<String> lines) {
        List<Smell> results = new ArrayList<>();
        int methodStart = -1;
        int braceDepth = 0;
        boolean inMethod = false;
        String currentMethod = "";

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) {
                continue;
            }
            // Heuristik fuer Methoden-Deklaration
            if (!inMethod && isMethodDeclaration(line)) {
                methodStart = i;
                currentMethod = extractMethodName(line);
                inMethod = true;
                braceDepth = 1; // { ist auf dieser oder naechster Zeile
                // Ueberspringe bis zur oeffnenden Klammer
                continue;
            }
            if (inMethod) {
                braceDepth += countChar(line, '{') - countChar(line, '}');
                if (braceDepth <= 0 && i - methodStart > 3) {
                    int methodLength = i - methodStart + 1;
                    if (methodLength > MAX_METHOD_LINES) {
                        results.add(new Smell(cls, path, SmellType.LONG_METHOD,
                                currentMethod + " is " + methodLength + " lines (max "
                                        + MAX_METHOD_LINES + ")",
                                Math.min(100, 40 + methodLength / 2)));
                    }
                    inMethod = false;
                    currentMethod = "";
                }
            }
        }
        return results;
    }

    private List<Smell> detectManyMethods(String cls, String path, List<String> lines) {
        long methodCount = lines.stream()
                .map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith("//"))
                .filter(this::isMethodDeclaration)
                .count();
        if (methodCount > MAX_METHODS_PER_CLASS) {
            return List.of(new Smell(cls, path, SmellType.TOO_MANY_METHODS,
                    methodCount + " methods (max " + MAX_METHODS_PER_CLASS + ")",
                    Math.min(100, 30 + (int)(methodCount - MAX_METHODS_PER_CLASS) * 5)));
        }
        return List.of();
    }

    private List<Smell> detectDeepNesting(String cls, String path, List<String> lines) {
        int maxDepth = 0;
        int currentDepth = 0;
        for (String line : lines) {
            String s = line.strip();
            if (s.isEmpty() || s.startsWith("//") || s.startsWith("*")) continue;
            currentDepth += countChar(line, '{') - countChar(line, '}');
            maxDepth = Math.max(maxDepth, currentDepth);
        }
        if (maxDepth > MAX_NESTING_DEPTH) {
            return List.of(new Smell(cls, path, SmellType.DEEP_NESTING,
                    "Max nesting depth " + maxDepth + " (max " + MAX_NESTING_DEPTH + ")",
                    Math.min(100, 30 + (maxDepth - MAX_NESTING_DEPTH) * 15)));
        }
        return List.of();
    }

    // Pattern: literal number not in string/comment
    private static final Pattern MAGIC_NUMBER = Pattern.compile(
            "\\b(?<!\")(?<![a-zA-Z_.])(-?\\d{2,})(?!\\s*L)(?![a-zA-Z_\"])");

    private List<Smell> detectMagicNumbers(String cls, String path, String content, int totalLines) {
        // Ignoriere offensichtliche Konstanten (0, 1, -1) und Zeilen-Nummern
        long count = MAGIC_NUMBER.matcher(content).results()
                .filter(mr -> {
                    String num = mr.group(1);
                    // Ignoriere 0, 1, -1, sowie haeufige Array-Groessen
                    return !num.equals("0") && !num.equals("1") && !num.equals("00")
                            && !num.equals("10") && !num.equals("16") && !num.equals("32")
                            && !num.equals("64") && !num.equals("100") && !num.equals("256")
                            && !num.equals("512") && !num.equals("1024");
                })
                .count();
        double density = totalLines > 0 ? count * 100.0 / totalLines : 0;
        if (density > MAX_MAGIC_NUMBERS_PER_100L) {
            return List.of(new Smell(cls, path, SmellType.MAGIC_NUMBERS,
                    String.format("%d magic numbers (%.1f/100lines, max %.0f)",
                            count, density, (double) MAX_MAGIC_NUMBERS_PER_100L),
                    Math.min(100, (int)(density * 20))));
        }
        return List.of();
    }

    // ── Helper ──────────────────────────────────────────────

    private boolean isMethodDeclaration(String line) {
        // Heuristik: typische Java Methodendeklaration
        return (line.contains("public ") || line.contains("private ")
                || line.contains("protected ") || line.contains(" static "))
                && line.contains("(") && line.contains(")")
                && !line.contains("class ") && !line.contains("interface ")
                && !line.contains("= ") && !line.contains("new ")
                && !line.contains("if (") && !line.contains("while (")
                && !line.contains("for (") && !line.contains("switch (")
                && !line.contains("import ") && !line.contains("package ");
    }

    private String extractMethodName(String line) {
        // Extrahiere den Methodennamen aus einer Deklaration
        int parenIdx = line.indexOf('(');
        if (parenIdx <= 0) return "unknown";
        String before = line.substring(0, parenIdx).strip();
        int lastSpace = before.lastIndexOf(' ');
        return lastSpace >= 0 ? before.substring(lastSpace + 1) : before;
    }

    private int countChar(String s, char c) {
        int count = 0;
        // Ignoriere chars in Strings und Kommentaren
        boolean inString = false;
        boolean inChar = false;
        boolean inLineComment = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (inLineComment) break;
            if (ch == '"' && !inChar && (i == 0 || s.charAt(i - 1) != '\\')) inString = !inString;
            if (ch == '\'' && !inString && (i == 0 || s.charAt(i - 1) != '\\')) inChar = !inChar;
            if (!inString && !inChar && ch == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/')
                inLineComment = true;
            if (!inString && !inChar && ch == c) count++;
        }
        return count;
    }

    private List<Path> findJavaFiles(Path root) {
        if (!Files.exists(root)) return List.of();
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private String className(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".java") ? name.substring(0, name.length() - 5) : name;
    }
}
