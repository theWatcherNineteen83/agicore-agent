package de.metis.modules.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class EvalReportGeneratorTest {

    @Test
    void testGenerateWithEmptyReportsDirectory(@TempDir Path dir) {
        var reportsDir = dir.resolve("eval-reports");
        var gen = new EvalReportGenerator(reportsDir, dir);
        assertDoesNotThrow(() -> gen.generate());
    }

    @Test
    void testGenerateProducesHtmlFile(@TempDir Path dir) throws Exception {
        var reportsDir = dir.resolve("eval-reports");
        var gen = new EvalReportGenerator(reportsDir, dir);
        var out = gen.generate();
        assertTrue(out.toString().endsWith("eval-dashboard.html"));
        assertTrue(java.nio.file.Files.exists(out));
        String content = java.nio.file.Files.readString(out);
        assertTrue(content.contains("Eval Dashboard"));
        assertTrue(content.contains("</html>"));
    }

    @Test
    void testParsesJsonReport(@TempDir Path dir) throws Exception {
        var reportsDir = dir.resolve("eval-reports");
        java.nio.file.Files.createDirectories(reportsDir);
        var reportContent = """
                {"timestamp":"2026-06-04T12:00:00","passed":5,"failed":1,"total":6,"gate":"PASS","taskType":"SMOKE"}
                """.trim();
        java.nio.file.Files.writeString(reportsDir.resolve("report-001.json"), reportContent);

        var gen = new EvalReportGenerator(reportsDir, dir);
        var out = gen.generate();
        String html = java.nio.file.Files.readString(out);
        assertTrue(html.contains("PASS"));
        assertTrue(html.contains("5"));
    }

    @Test
    void testMultipleReports(@TempDir Path dir) throws Exception {
        var reportsDir = dir.resolve("eval-reports");
        java.nio.file.Files.createDirectories(reportsDir);

        for (int i = 1; i <= 3; i++) {
            var content = String.format(
                    "{\"timestamp\":\"t%d\",\"passed\":%d,\"failed\":%d,\"total\":%d,\"gate\":\"%s\",\"taskType\":\"SMOKE\"}",
                    i, 10-i, i, 10, i == 2 ? "FAIL" : "PASS");
            java.nio.file.Files.writeString(reportsDir.resolve("report-%03d.json".formatted(i)), content);
        }

        var gen = new EvalReportGenerator(reportsDir, dir);
        var out = gen.generate();
        String html = java.nio.file.Files.readString(out);
        assertTrue(html.contains("FAIL"));
        assertTrue(html.contains("PASS"));
    }
}
