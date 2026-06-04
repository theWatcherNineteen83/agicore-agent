package de.metis.modules.self;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.*;

/**
 * Phase 12a — Parst Maven-Compiler-Output und erstellt strukturierte Fehlerberichte.
 *
 * <p>Metis kann damit Compile-Fehler aus {@code mvn compile} analysieren
 * und gezielte Fix-Versuche starten. Der Reporter extrahiert:
 * <ul>
 *   <li>Datei + Zeilennummer jedes Compile-Fehlers</li>
 *   <li>Fehlermeldung + Symbol-Name</li>
 *   <li>Gesamtanzahl Fehler/Warnungen</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * var reporter = new CompileErrorReporter("/home/prometheus/metis-build");
 * var errors = reporter.compile();
 * if (!errors.isEmpty()) { ... generate fix ... }
 * }</pre>
 */
public class CompileErrorReporter {

    private static final Logger LOG = Logger.getLogger(CompileErrorReporter.class.getName());

    /** Regex für Maven-Compiler-Fehler: {@code /path/to/File.java:[lin,colem] error: message}. */
    private static final Pattern ERROR_PATTERN =
            Pattern.compile("^(\\S+\\.java):\\[(\\d+),(\\d+)\\]\\s+error:\\s+(.+)$", Pattern.MULTILINE);

    /** Regex für Symbol-not-found Fehler: liefert den Symbol-Namen extra. */
    private static final Pattern SYMBOL_PATTERN =
            Pattern.compile("Symbol:\\s+(.+)$", Pattern.MULTILINE);

    private final Path projectDir;
    private final Path mvnw;

    /**
     * @param projectDir  das Projekt-Root-Verzeichnis (mit pom.xml)
     */
    public CompileErrorReporter(String projectDir) {
        this(Path.of(projectDir));
    }

    public CompileErrorReporter(Path projectDir) {
        this.projectDir = projectDir;
        this.mvnw = projectDir.resolve("mvnw");
    }

    /**
     * Führt {@code mvn compile} aus und parst die Compile-Fehler.
     *
     * @return Liste der gefundenen Compile-Errors (leer bei Erfolg)
     */
    public List<CompileError> compile() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    Files.exists(mvnw) ? mvnw.toString() : "mvn",
                    "compile", "-q", "-pl", "agicore-modules", "-am");
            pb.directory(projectDir.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                LOG.info("CompileErrorReporter: BUILD SUCCESS (0 errors)");
                return List.of();
            }

            return parse(output, buildIncludePaths());

        } catch (Exception e) {
            LOG.warning("CompileErrorReporter: compile failed: " + e.getMessage());
            return List.of(new CompileError(
                    "unknown", 0, "CompileErrorReporter infrastructure error: " + e.getMessage(),
                    "System", "UNKNOWN"));
        }
    }

    /**
     * Parst rohen Maven-Compiler-Output.
     */
    public List<CompileError> parse(String mavenOutput, List<String> includePaths) {
        List<CompileError> errors = new ArrayList<>();
        Matcher m = ERROR_PATTERN.matcher(mavenOutput);

        // Extract symbol names from the full output
        List<String> symbols = new ArrayList<>();
        Matcher sm = SYMBOL_PATTERN.matcher(mavenOutput);
        while (sm.find()) symbols.add(sm.group(1).trim());

        int si = 0;
        while (m.find()) {
            String file = m.group(1);
            int line = Integer.parseInt(m.group(2));
            String message = m.group(4); // error message (group 2=line, 3=col)

            // Filter: nur Dateien in includePaths
            boolean inScope = includePaths.isEmpty();
            for (String inc : includePaths) {
                if (file.contains(inc)) {
                    inScope = true;
                    break;
                }
            }
            if (!inScope) continue;

            // Symbol-Name aus der Nachricht extrahieren wenn möglich
            String symbol = si < symbols.size() ? symbols.get(si++) : extractSymbol(message);
            String severity = message.toLowerCase().contains("cannot find symbol")
                    ? "MISSING_SYMBOL" : "COMPILE_ERROR";

            errors.add(new CompileError(file, line, message, symbol, severity));
        }

        Collections.sort(errors); // nach Datei + Zeile sortieren
        LOG.info("CompileErrorReporter: parsed " + errors.size() + " errors from output");
        return errors;
    }

    // ── Prüfung ob Fehler behebbar ist ─────────────────────────

    /**
     * Prüft ob ein Fehler durch automatische Code-Generierung behebbar ist.
     */
    public boolean isFixable(CompileError error) {
        String msg = error.message().toLowerCase();
        // Typ- und Symbol-Fehler sind gut automatisch fixbar
        if (msg.contains("cannot find symbol")) return true;
        if (msg.contains("variable ") && msg.contains(" might not have been initialized")) return true;
        if (msg.contains("incompatible types")) return true;
        if (msg.contains("missing return statement")) return true;
        // Methoden/Constructor-Fehler sind schwieriger
        if (msg.contains("constructor ") && msg.contains(" is not applicable")) return false;
        if (msg.contains("method ") && msg.contains(" is not applicable")) return false;
        return false;
    }

    // ── Helpers ───────────────────────────────────────────────

    private List<String> buildIncludePaths() {
        return List.of(
                "/agicore-modules/src/main/java/",
                "/agicore-kernel/src/main/java/",
                "/agicore-watchdog/src/main/java/"
        );
    }

    private String extractSymbol(String message) {
        // "cannot find symbol: class Foo" → "Foo"
        Matcher m = Pattern.compile("class (\\w+)").matcher(message);
        if (m.find()) return m.group(1);
        m = Pattern.compile("variable (\\w+)").matcher(message);
        if (m.find()) return m.group(1);
        m = Pattern.compile("method (\\w+)").matcher(message);
        if (m.find()) return m.group(1);
        return "?";
    }

    // ── CompileError Record ───────────────────────────────────

    /**
     * Strukturierter Compile-Fehler.
     */
    public record CompileError(
            String file,
            int line,
            String message,
            String symbol,
            String severity
    ) implements Comparable<CompileError> {
        @Override
        public int compareTo(CompileError o) {
            int c = this.file.compareTo(o.file);
            if (c != 0) return c;
            return Integer.compare(this.line, o.line);
        }

        /** Kurze, menschenlesbare Zusammenfassung. */
        public String summary() {
            return "[" + severity + "] " + file + ":" + line + " — " + message;
        }

        /** Kurzform für Goal-Beschreibungen. */
        public String shortDesc() {
            // Nur den Dateinamen, nicht den Pfad
            String name = file;
            int idx = file.lastIndexOf('/');
            if (idx >= 0) name = file.substring(idx + 1);
            return name + ":" + line + " " + symbol + " — " + message.substring(0, Math.min(message.length(), 60));
        }
    }
}
