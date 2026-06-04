package de.metis.modules.eval;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

public class EvalReportGenerator {

    private static final Logger LOG = Logger.getLogger(EvalReportGenerator.class.getName());
    private final Path reportsDir;
    private final Path outputDir;

    public EvalReportGenerator(Path reportsDir, Path outputDir) {
        this.reportsDir = reportsDir;
        this.outputDir = outputDir;
    }

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

                         String runAt = root.path("runAt").asText("unknown");
                         if (runAt.length() > 19) runAt = runAt.substring(0, 19);

                         String tier = root.path("tier").asText("SMOKE");
                         boolean gateOk = root.path("gate").path("ok").asBoolean(false);
                         int totalRuns = 0;
                         var results = root.path("results");
                         var it = results.fieldNames();
                         while (it.hasNext()) {
                             var metric = results.path(it.next());
                             var fields = metric.fieldNames();
                             while (fields.hasNext()) {
                                 var value = metric.path(fields.next());
                                 totalRuns += value.path("runs").asInt(0);
                             }
                         }

                         // Count passing/failing metrics
                         int passingMetrics = 0;
                         int failingMetrics = 0;
                         var gateArr = root.path("gate").path("failingMetrics");
                         if (gateArr.isArray()) failingMetrics = gateArr.size();

                         // Count total metrics
                         int totalMetrics = 0;
                         var resIt = results.fieldNames();
                         while (resIt.hasNext()) {
                             var metric = results.path(resIt.next());
                             var mIt = metric.fieldNames();
                             while (mIt.hasNext()) {
                                 mIt.next();
                                 totalMetrics++;
                             }
                         }
                         passingMetrics = totalMetrics - failingMetrics;

                         result.add(new ReportEntry(
                                 f.getFileName().toString(),
                                 runAt,
                                 passingMetrics,
                                 failingMetrics,
                                 totalRuns,
                                 gateOk ? "PASS" : "FAIL",
                                 tier));
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
          .append("<meta name='viewport' content='width=device-width, initial-scale=1'>")
          .append("<style>")
          .append("body{font-family:system-ui,sans-serif;background:#1a1a2e;color:#e0e0e0;padding:20px;margin:0;}")
          .append("h1{color:#00d4ff;}.pass{color:#00ff88;}.fail{color:#ff4466;}")
          .append("table{border-collapse:collapse;width:100%;margin-top:20px;font-size:14px;}")
          .append("th,td{padding:6px 10px;text-align:left;border-bottom:1px solid #333;}")
          .append("th{background:#16213e;color:#00d4ff;position:sticky;top:0;}")
          .append("tr:hover{background:#0f3460;}")
          .append(".gate-PASS{color:#00ff88;font-weight:bold;}")
          .append(".gate-FAIL{color:#ff4466;font-weight:bold;}")
          .append(".stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:10px;margin-bottom:20px;}")
          .append(".stat-card{background:#16213e;padding:15px;border-radius:8px;text-align:center;}")
          .append(".stat-card h3{color:#00d4ff;margin:0 0 8px 0;font-size:14px;}")
          .append(".stat-card .value{font-size:2em;font-weight:bold;}")
          .append(".badge{display:inline-block;padding:2px 8px;border-radius:12px;font-size:12px;font-weight:bold;}")
          .append(".badge-pass{background:#00ff8822;color:#00ff88;border:1px solid #00ff88;}")
          .append(".badge-fail{background:#ff446622;color:#ff4466;border:1px solid #ff4466;}")
          .append("a{color:#00d4ff;text-decoration:none;}")
          .append("a:hover{text-decoration:underline;}")
          .append("@media(prefers-color-scheme:light){body{background:#fff;color:#333;}")
          .append("th{background:#f0f4ff;color:#0066cc;} .stat-card{background:#f0f4ff;}")
          .append("h1{color:#0066cc;}}")
          .append("</style></head><body>");

        // Header with navigation
        sb.append("<div style='display:flex;justify-content:space-between;align-items:center;'>")
          .append("<h1>📊 Metis AGI — Eval Dashboard</h1>")
          .append("<div>")
          .append("<a href='/api/status'>API Status</a> | ")
          .append("<a href='/api/metrics'>Metrics</a>")
          .append("</div></div>");

        // Summary stats
        int totalReports = reports.size();
        int passedReports = (int) reports.stream().filter(r -> "PASS".equals(r.gate())).count();
        int failedReports = totalReports - passedReports;
        int totalMetrics = reports.stream().mapToInt(r -> r.passed() + r.failed()).sum();
        int failedMetrics = reports.stream().mapToInt(ReportEntry::failed).sum();
        int totalRuns = reports.stream().mapToInt(ReportEntry::total).sum();

        sb.append("<div class='stats'>")
          .append("<div class='stat-card'><h3>Reports</h3><div class='value'>").append(String.valueOf(totalReports)).append("</div></div>")
          .append("<div class='stat-card'><h3>Passed</h3><div class='value pass'>").append(String.valueOf(passedReports)).append("</div></div>")
          .append("<div class='stat-card'><h3>Failed</h3><div class='value fail'>").append(String.valueOf(failedReports)).append("</div></div>")
          .append("<div class='stat-card'><h3>Metrics</h3><div class='value'>").append(String.valueOf(totalMetrics)).append("</div></div>");
        if (failedMetrics > 0) {
            sb.append("<div class='stat-card'><h3>Failing</h3><div class='value fail'>").append(String.valueOf(failedMetrics)).append("</div></div>");
        }
        sb.append("<div class='stat-card'><h3>Total Runs</h3><div class='value'>").append(String.valueOf(totalRuns)).append("</div></div>")
          .append("</div>");

        // Table
        sb.append("<table><thead><tr><th>#</th><th>Report</th><th>Time</th><th>Tier</th>")
          .append("<th>Gate</th></tr></thead><tbody>");
        int idx = totalReports;
        for (var r : reports.reversed()) {
            String gateClass = "gate-" + r.gate();
            sb.append("<tr>")
              .append("<td>").append(String.valueOf(idx--)).append("</td>")
              .append("<td>").append(r.fileName()).append("</td>")
              .append("<td>").append(r.timestamp()).append("</td>")
              .append("<td><span class='badge badge-").append("PASS".equals(r.gate()) ? "pass" : "fail").append("'>")
              .append(r.taskType()).append("</span></td>")
              .append("<td class='").append(gateClass).append("'>").append(r.gate()).append("</td>")
              .append("</tr>");
        }
        sb.append("</tbody></table>")
          .append("<p style='color:#888;font-size:12px;margin-top:10px;'>")
          .append("Metis AGI — Auto-generated from eval-reports/")
          .append("</p></body></html>");
        return sb.toString();
    }

    public record ReportEntry(String fileName, String timestamp,
                              int passed, int failed, int total, String gate, String taskType) {}
}
