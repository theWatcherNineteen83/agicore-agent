package de.metis.modules.evolution;

import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Phase 12d — Parst Jacoco-XML-Reports fuer Test-Coverage.
 *
 * <p>Jacoco erzeugt nach {@code mvn test} eine XML-Datei unter
 * {@code target/site/jacoco/jacoco.xml}. Diese Klasse extrahiert:
 * <ul>
 *   <li>Gesamt-Coverage (Instruction/Method/Line/Branch)</li>
 *   <li>Coverage pro Package</li>
 *   <li>Coverage pro Klasse (mit Gaps &lt; 60%)</li>
 * </ul>
 *
 * <p>Falls der Jacoco-Report nicht existiert: {@code isAvailable() == false},
 * dann wird ein leerer Report mit {@code coveragePercent=0} zurueckgegeben.
 * Metis kann dann selbst entscheiden ob sie Tests laufen lassen will.
 */
public class CoverageCheck {

    private static final Logger LOG = Logger.getLogger(CoverageCheck.class.getName());

    /** Standard-Pfad zum Jacoco XML-Report. */
    private static final Path DEFAULT_JACOCO_XML =
            Path.of("agicore-modules/target/site/jacoco/jacoco.xml");

    private final Path jacocoXml;

    public CoverageCheck() {
        this(DEFAULT_JACOCO_XML);
    }

    public CoverageCheck(Path jacocoXml) {
        this.jacocoXml = jacocoXml;
    }

    /** Coverage-Werte fuer einen Scope. */
    public record CoverageMetrics(
            String name,
            int instructionsMissed, int instructionsCovered,
            int branchesMissed, int branchesCovered,
            int linesMissed, int linesCovered,
            int methodsMissed, int methodsCovered
    ) {
        public double instructionCoverage() {
            int total = instructionsMissed + instructionsCovered;
            return total > 0 ? (double) instructionsCovered / total * 100.0 : 0.0;
        }
        public double lineCoverage() {
            int total = linesMissed + linesCovered;
            return total > 0 ? (double) linesCovered / total * 100.0 : 0.0;
        }
        public double branchCoverage() {
            int total = branchesMissed + branchesCovered;
            return total > 0 ? (double) branchesCovered / total * 100.0 : 0.0;
        }
        public String summary() {
            return String.format("%s: instr=%.0f%% line=%.0f%% branch=%.0f%%",
                    name, instructionCoverage(), lineCoverage(), branchCoverage());
        }
    }

    /** Gesamt-Coverage + Details. */
    public record JacocoReport(
            boolean available,
            CoverageMetrics total,
            List<CoverageMetrics> packages,
            List<CoverageMetrics> lowCoverageClasses  // < 60%
    ) {}

    /**
     * Liest den Jacoco-Report. Falls nicht verfuegbar,
     * wird ein Dummy-Report mit {@code available=false} zurueckgegeben.
     */
    public JacocoReport read() {
        if (!Files.exists(jacocoXml)) {
            LOG.fine("CoverageCheck: Jacoco report not found at " + jacocoXml);
            return new JacocoReport(false,
                    new CoverageMetrics("N/A", 0, 0, 0, 0, 0, 0, 0, 0),
                    List.of(), List.of());
        }
        try {
            Document doc = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().parse(jacocoXml.toFile());
            doc.getDocumentElement().normalize();

            // Root → report
            Element root = doc.getDocumentElement();
            String name = root.hasAttribute("name") ? root.getAttribute("name") : "Metis";

            // Finde <counter type="INSTRUCTION"> etc.
            CoverageMetrics total = parseCounters(root, name);

            // Packages
            List<CoverageMetrics> packages = new ArrayList<>();
            NodeList pkgNodes = root.getElementsByTagName("package");
            for (int i = 0; i < pkgNodes.getLength(); i++) {
                Element pkg = (Element) pkgNodes.item(i);
                packages.add(parseCounters(pkg, pkg.getAttribute("name")));
            }

            // Low-coverage classes (< 60% instruction)
            List<CoverageMetrics> lowCov = new ArrayList<>();
            NodeList classNodes = root.getElementsByTagName("class");
            for (int i = 0; i < classNodes.getLength(); i++) {
                Element cls = (Element) classNodes.item(i);
                CoverageMetrics cm = parseCounters(cls,
                        cls.getAttribute("name").replace('/', '.'));
                if (cm.instructionCoverage() < 60.0 && cm.instructionCoverage() > 0) {
                    lowCov.add(cm);
                }
            }
            lowCov.sort(Comparator.comparingDouble(CoverageMetrics::instructionCoverage));

            LOG.info("CoverageCheck: " + total.summary()
                    + ", " + lowCov.size() + " classes <60%");
            return new JacocoReport(true, total, packages, lowCov);

        } catch (Exception e) {
            LOG.warning("CoverageCheck: parse failed — " + e.getMessage());
            return new JacocoReport(false,
                    new CoverageMetrics("parse-error", 0, 0, 0, 0, 0, 0, 0, 0),
                    List.of(), List.of());
        }
    }

    /**
     * Quick-Check: Gibt es den Jacoco-Report?
     */
    public boolean isAvailable() {
        return Files.exists(jacocoXml);
    }

    /**
     * Gibt Coverage-Gaps als Feature-Vorschlaege zurueck.
     */
    public List<GapAnalyzer.FeatureProposal> asFeatureProposals() {
        var report = read();
        if (!report.available()) {
            return List.of(new GapAnalyzer.FeatureProposal(
                    "run_jacoco_tests",
                    "No Jacoco coverage report found",
                    "Run 'mvn test jacoco:report' to generate coverage data",
                    "pom.xml", 40));
        }

        List<GapAnalyzer.FeatureProposal> props = new ArrayList<>();

        if (report.total().instructionCoverage() < 50) {
            props.add(new GapAnalyzer.FeatureProposal(
                    "increase_instruction_coverage",
                    "Instruction coverage is only " +
                            String.format("%.0f%%", report.total().instructionCoverage()),
                    "Improve overall test coverage",
                    "N/A", 70));
        }

        for (var cls : report.lowCoverageClasses()) {
            props.add(new GapAnalyzer.FeatureProposal(
                    "improve_coverage_" + sanitize(cls.name()),
                    cls.name() + " has only " +
                            String.format("%.0f%%", cls.instructionCoverage()) + " coverage",
                    "Add tests for " + cls.name(),
                    cls.name(), 50 + (int)(60 - cls.instructionCoverage())));
        }
        return props;
    }

    // ── interne Helfer ────────────────────────────────────────

    private CoverageMetrics parseCounters(Element parent, String name) {
        int instrMissed = 0, instrCovered = 0;
        int branchMissed = 0, branchCovered = 0;
        int lineMissed = 0, lineCovered = 0;
        int methMissed = 0, methCovered = 0;

        NodeList counters = parent.getElementsByTagName("counter");
        for (int i = 0; i < counters.getLength(); i++) {
            Element c = (Element) counters.item(i);
            String type = c.getAttribute("type");
            int missed = Integer.parseInt(c.getAttribute("missed"));
            int covered = Integer.parseInt(c.getAttribute("covered"));
            switch (type) {
                case "INSTRUCTION" -> { instrMissed = missed; instrCovered = covered; }
                case "BRANCH"     -> { branchMissed = missed; branchCovered = covered; }
                case "LINE"       -> { lineMissed = missed;   lineCovered = covered;   }
                case "METHOD"     -> { methMissed = missed;   methCovered = covered;   }
            }
        }
        return new CoverageMetrics(name,
                instrMissed, instrCovered,
                branchMissed, branchCovered,
                lineMissed, lineCovered,
                methMissed, methCovered);
    }

    private String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9_.]", "_").toLowerCase();
    }
}
