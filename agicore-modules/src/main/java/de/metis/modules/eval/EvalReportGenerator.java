package de.metis.modules.eval;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Phase 12a — EvalReportGenerator: HTML-Benchmark-Dashboard.
 *
 * <p>Liest eval-reports/ und generiert eine HTML-Status-Seite
 * mit Metrik-Trends, Bestehen/Fehlschlagen und History.
 */
public class EvalReportGenerator {

    private static final Logger LOG = Logger.getLogger(EvalReportGenerator.class.getName());
    private final Path reportsDir;
    private final Path outputDir;

    public EvalReportGenerator(Path reportsDir, Path outputDir) {
        this.reportsDir = reportsDir;
        this.outputDir = outputDir;
    }

    /**
     * Generiert das HTML-Dashboard und gibt den Pfad zurueck.
     */
    public Path generate() throws IOException {
        Files.createDirectories(outputDir);
        Files.createDirectories(reportsDir);

        var reports = loadReports();
        String html = buildHtml(reports);

        Path out = outputDir.resolve("eval-dashboard.html");
        Files.writeString(out, html);
        LOG.info("EvalReportGenerator: dashboard written to " + out);
        return out;
    }

    private List<ReportEntry> loadReports() throws IOException {
        var result = new ArrayList<ReportEntry>();
        try (var files = Files.list(reportsDir)) {
            files.filter(f -> f.toString().endsWith(".json"))
                 .sorted()
                 .forEach(f -> {
                     try {
                         var content = Files.readString(f);
                         var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                         var root = mapper.readTree(content);
                         var entry = new ReportEntry(
                                 f.getFileName().toString(),
                                 root.path("timestamp").asText("unknown"),
                                 root.path("passed").asInt(0),
                                 root.path("failed").asInt(0),
                                 root.path("total").asInt(0),
                                 root.path("gate").asText("UNKNOWN"),
                                 root.path("taskType").asText("unknown"));
                         result.add(entry);
                     } catch (Exception e) {
                         LOG.fine("EvalReportGenerator: skip " + f + ": " + e.getMessage());
                     }
                 });
        }
        return result;
    }

    private String buildHtml(List<ReportEntry> reports) {
        var sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>")
          .append("<title>Metis AGI — Eval Dashboard</title>")
          .append("<style>")
          .append("body{font-family:system-ui,sans-serif;background:#1a1a2e;color:#e0e0e0;padding:20px;}")
          .append("h1{color:#00d4ff;}.pass{color:#00ff88;}.fail{color:#ff4466;}")
          .append("table{border-collapse:collapse;width:100%;margin-top:20px;}")
          .append("th,td{padding:8px 12px;text-align:left;border-bottom:1px solid #333;}")
          .append("th{background:#16213e;color:#00d4ff;}")
          .append("tr:hover{background:#0f3460;}")
          .append(".gate-PASS{color:#00ff88;font-weight:bold;}")
          .append(".gate-FAIL{color:#ff4466;font-weight:bold;}")
          .append(".stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:10px;}")
          .append(".stat-card{background:#16213e;padding:15px;border-radius:8px;text-align:center;}")
          .append(".stat-card h3{color:#00d4ff;}")
          .append(".stat-card .value{font-size:2em;font-weight:bold;}")
          .append("</style></head><body>")
          .append("<h1>📊 Metis AGI — Eval Dashboard</h1>");

        // Summary stats
        int totalPassed = reports.stream().mapToInt(ReportEntry::passed).sum();
        int totalFailed = reports.stream().mapToInt(ReportEntry::failed).sum();
        int totalTotal = reports.stream().mapToInt(ReportEntry::total).sum();
        long passRate = totalTotal > 0 ? (totalPassed * 100 / totalTotal) : 0;

        sb.append("<div class='stats'>")
          .append("<div class='stat-card'><h3>Reports</h3><div class='value'>")
          .append(String.valueOf(reports.size())).append("</div></div>")
          .append("<div class='stat-card'><h3>Pass Rate</h3><div class='value pass'>")
          .append(String.valueOf(passRate)).append("%</div></div>")
          .append("<div class='stat-card'><h3>Passed</h3><div class='value pass'>")
          .append(String.valueOf(totalPassed)).append("</div></div>")
          .append("<div class='stat-card'><h3>Failed</h3><div class='value fail'>")
          .append(String.valueOf(totalFailed)).append("</div></div>")
          .append("</div>");

        // Table
        sb.append("<table><tr><th>Report</th><th>Time</th><th>Type</th>")
          .append("<th>Passed</th><th>Failed</th><th>Total</th><th>Gate</th></tr>");
        for (var r : reports.reversed()) {
            sb.append("<tr>")
              .append("<td>").append(r.fileName()).append("</td>")
              .append("<td>").append(r.timestamp()).append("</td>")
              .append("<td>").append(r.taskType()).append("</td>")
              .append("<td class='pass'>").append(String.valueOf(r.passed())).append("</td>")
              .append("<td class='fail'>").append(String.valueOf(r.failed())).append("</td>")
              .append("<td>").append(String.valueOf(r.total())).append("</td>")
              .append("<td class='gate-").append(r.gate()).append("'>").append(r.gate()).append("</td>")
              .append("</tr>");
        }
        sb.append("</table></body></html>");
        return sb.toString();
    }

    public record ReportEntry(String fileName, String timestamp,
                              int passed, int failed, int total, String gate, String taskType) {}
}
