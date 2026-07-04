package de.metis.modules.evolution;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Phase 12d — Analyse welche Source-Dateien keine Tests haben.
 *
 * <p>Scant das Maven-Modul nach {@code *Test.java} und matched gegen
 * Source-Dateien. Erkennt:
 * <ul>
 *   <li>Klassen ohne Tests (TestGap)</li>
 *   <li>Test-Klassen ohne zugehoerige Source (OrphanTest)</li>
 *   <li>Grobe Test-Abdeckung in Prozent</li>
 * </ul>
 *
 * <p>Bewusst einfach: keine Bytecode-Analyse, keine Coverage-Daten.
 * Reine Datei-Struktur-Analyse — schnell, robust, kein externes Tool.
 * Fuer vollstaendige Coverage → Jacoco-Integration in CoverageCheck.
 */
public class TestGapAnalyzer {

    private static final Logger LOG = Logger.getLogger(TestGapAnalyzer.class.getName());

    /** Wo die Source-Dateien liegen. */
    private final Path sourceRoot;
    private final Path testRoot;

    public TestGapAnalyzer() {
        this(Path.of("agicore-modules/src/main/java"),
             Path.of("agicore-modules/src/test/java"));
    }

    public TestGapAnalyzer(Path sourceRoot, Path testRoot) {
        this.sourceRoot = sourceRoot;
        this.testRoot = testRoot;
    }

    /** Eine Luecke: Source-Datei ohne zugehoerigen Test. */
    public record TestGap(
            String className,      // z. B. "GapAnalyzer"
            String packagePath,    // z. B. "de/metis/modules/evolution"
            String sourceFile,     // relativer Pfad zur Source
            int sourceLines,       // Zeilenanzahl (Proxy fuer Komplexitaet)
            boolean isCritical     // >200 Zeilen ohne Test = critical
    ) {}

    /** Erfasste Test-Abdeckung nach dieser Analyse. */
    public record CoverageReport(
            int sourceFiles,
            int testedFiles,
            int untestedFiles,
            int orphanTests,
            List<TestGap> gaps,
            double coveragePercent
    ) {}

    /**
     * Fuehrt die Analyse durch.
     */
    public CoverageReport analyze() {
        List<Path> sourceFiles = findJavaFiles(sourceRoot);
        List<Path> testFiles = findJavaFiles(testRoot);

        // Baue Maps: SimpleClassName → Set<full_path>
        Map<String, Set<Path>> sourcesByClass = groupByClassName(sourceFiles);
        Map<String, Set<Path>> testsByClass = groupByClassName(testFiles);

        // Finde Test-Gaps: Source-Klassen ohne Test
        List<TestGap> gaps = new ArrayList<>();
        int untestedCount = 0;

        for (var entry : sourcesByClass.entrySet()) {
            String className = entry.getKey();
            // Erwarteter Test-Name: className + "Test" oder "Test" + className
            boolean hasTest = testsByClass.containsKey(className + "Test")
                    || testsByClass.containsKey("Test" + className);
            if (!hasTest) {
                for (Path sf : entry.getValue()) {
                    int lines = countLines(sf);
                    String pkgPath = extractPackagePath(sf, sourceRoot);
                    gaps.add(new TestGap(
                            className, pkgPath,
                            sourceRoot.relativize(sf).toString(),
                            lines, lines > 200));
                }
                untestedCount += entry.getValue().size();
            }
        }

        // Finde Orphan-Tests (Tests ohne zugehoerige Source)
        int orphanCount = 0;
        for (var entry : testsByClass.entrySet()) {
            String testName = entry.getKey();
            // Entferne "Test" Praefix/Suffix
            String baseName = testName.endsWith("Test")
                    ? testName.substring(0, testName.length() - 4)
                    : testName.startsWith("Test")
                        ? testName.substring(4)
                        : testName;
            if (!sourcesByClass.containsKey(baseName)) {
                orphanCount++;
            }
        }

        int totalSource = sourceFiles.size();
        int tested = totalSource - untestedCount;
        double coverage = totalSource > 0
                ? (double) tested / totalSource * 100.0
                : 100.0;

        // Sortiere Gaps: kritisch zuerst, dann nach Zeilen
        gaps.sort(Comparator.<TestGap>comparingInt(g -> g.isCritical() ? 0 : 1)
                .thenComparingInt(g -> -g.sourceLines()));

        var report = new CoverageReport(
                totalSource, tested, untestedCount, orphanCount,
                List.copyOf(gaps), Math.round(coverage * 10.0) / 10.0);

        StringBuilder sb = new StringBuilder();
        sb.append("TestGapAnalyzer: ").append(tested).append('/').append(totalSource)
          .append(" tested (")
          .append(String.format("%.1f", coverage)).append("%), ")
          .append(untestedCount).append(" gaps");
        if (orphanCount > 0) sb.append(", ").append(orphanCount).append(" orphan tests");
        if (!gaps.isEmpty()) {
            sb.append(" — critical: ");
            sb.append(gaps.stream().filter(TestGap::isCritical).count());
            sb.append(", total: ").append(gaps.size());
        }
        LOG.info(sb.toString());

        return report;
    }

    /**
     * Quick-Check: Liste der kritischen Gaps (fuer Featur-Generierung).
     */
    public List<TestGap> criticalGaps() {
        return analyze().gaps().stream()
                .filter(TestGap::isCritical)
                .toList();
    }

    /**
     * Formatiert Gaps als Goal-Vorschlaege fuer den GapAnalyzer.
     */
    public List<GapAnalyzer.FeatureProposal> asFeatureProposals() {
        var report = analyze();
        List<GapAnalyzer.FeatureProposal> props = new ArrayList<>();

        if (report.coveragePercent() < 30) {
            props.add(new GapAnalyzer.FeatureProposal(
                    "increase_test_coverage",
                    "Test coverage is only %.1f%%".formatted(report.coveragePercent()),
                    "Write tests for critical untested classes",
                    "N/A (codebase-wide)", 75));
        }

        for (var gap : report.gaps()) {
            if (gap.isCritical()) {
                props.add(new GapAnalyzer.FeatureProposal(
                        "test_" + gap.className().toLowerCase(),
                        gap.className() + " has " + gap.sourceLines()
                                + " lines and NO tests",
                        "Write unit test for " + gap.className(),
                        gap.sourceFile(), 60 + Math.min(40, gap.sourceLines() / 10)));
            }
        }
        return props;
    }

    // ── interne Helfer ────────────────────────────────────────

    private List<Path> findJavaFiles(Path root) {
        if (!Files.exists(root)) return List.of();
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .toList();
        } catch (IOException e) {
            LOG.warning("TestGapAnalyzer: walk failed for " + root + ": " + e.getMessage());
            return List.of();
        }
    }

    private Map<String, Set<Path>> groupByClassName(List<Path> files) {
        Map<String, Set<Path>> map = new HashMap<>();
        for (Path f : files) {
            String name = f.getFileName().toString();
            // Entferne .java-Endung
            if (name.endsWith(".java")) name = name.substring(0, name.length() - 5);
            map.computeIfAbsent(name, k -> new HashSet<>()).add(f);
        }
        return map;
    }

    private String extractPackagePath(Path file, Path root) {
        try {
            Path relative = root.relativize(file);
            Path parent = relative.getParent();
            return parent != null ? parent.toString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private int countLines(Path file) {
        try {
            return (int) Files.lines(file).count();
        } catch (IOException e) {
            return 0;
        }
    }
}
