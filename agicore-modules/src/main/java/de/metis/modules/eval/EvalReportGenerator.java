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
                         var metricsList = new ArrayList<MetricDetail>();
                         var results = root.path("results");
                         var it = results.fieldNames();
                         while (it.hasNext()) {
                             String category = it.next();
                             var metric = results.path(category);
                             var fields = metric.fieldNames();
                             while (fields.hasNext()) {
                                 String metricName = fields.next();
                                 var value = metric.path(metricName);
                                 int runs = value.path("runs").asInt(0);
                                 double mean = value.path("mean").asDouble(0.0);
                                 String gate = value.path("gate").asText("SOFT");
                                 totalRuns += runs;
                                 metricsList.add(new MetricDetail(category, metricName, mean, runs, gate));
                             }
                         }

                         int failingMetrics = 0;
                         var gateArr = root.path("gate").path("failingMetrics");
                         if (gateArr.isArray()) failingMetrics = gateArr.size();
                         int passingMetrics = metricsList.size() - failingMetrics;

                         result.add(new ReportEntry(
                                 f.getFileName().toString(),
                                 runAt,
                                 passingMetrics,
                                 failingMetrics,
                                 totalRuns,
                                 gateOk ? "PASS" : "FAIL",
                                 tier,
                                 metricsList));
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
          .append("<meta http-equiv='refresh' content='120'>")
          .append("<style>")
          .append("body{font-family:system-ui,sans-serif;background:#1a1a2e;color:#e0e0e0;padding:20px;margin:0;}")
          .append("h1{color:#00d4ff;}.pass{color:#00ff88;}.fail{color:#ff4466;}")
          .append("table{border-collapse:collapse;width:100%;margin-top:20px;font-size:13px;}")
          .append("th,td{padding:5px 8px;text-align:left;border-bottom:1px solid #333;}")
          .append("th{background:#16213e;color:#00d4ff;position:sticky;top:0;white-space:nowrap;}")
          .append("tr:hover{background:#0f3460;}")
          .append(".gate-PASS{color:#00ff88;font-weight:bold;}")
          .append(".gate-FAIL{color:#ff4466;font-weight:bold;}")
          .append(".stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:10px;margin-bottom:20px;}")
          .append(".stat-card{background:#16213e;padding:12px;border-radius:8px;text-align:center;}")
          .append(".stat-card h3{color:#00d4ff;margin:0 0 6px 0;font-size:13px;}")
          .append(".stat-card .value{font-size:1.8em;font-weight:bold;}")
          .append(".badge{display:inline-block;padding:2px 6px;border-radius:10px;font-size:11px;font-weight:bold;}")
          .append(".badge-pass{background:#00ff8822;color:#00ff88;border:1px solid #00ff88;}")
          .append(".badge-fail{background:#ff446622;color:#ff4466;border:1px solid #ff4466;}")
          .append(".metric-grid{display:flex;flex-wrap:wrap;gap:2px;max-width:300px;}")
          .append(".metric-dot{width:6px;height:6px;border-radius:50%;display:inline-block;}")
          .append(".dot-pass{background:#00ff88;}.dot-fail{background:#ff4466;}.dot-soft{background:#ffaa00;}")
          .append(".metric-row{cursor:pointer;}")
          .append(".metric-detail{display:none;font-size:11px;color:#ccc;padding:2px 8px;}")
          .append(".metric-detail.open{display:block;}")
          .append(".refresh-bar{font-size:12px;color:#666;margin-bottom:10px;text-align:right;}")
          .append("a{color:#00d4ff;text-decoration:none;}a:hover{text-decoration:underline;}")
          .append("details{margin:0;padding:0;font-size:12px;}")
          .append("summary{cursor:pointer;color:#888;font-size:11px;}")
          .append("@media(prefers-color-scheme:light){body{background:#fff;color:#333;}")
          .append("th{background:#f0f4ff;color:#0066cc;}.stat-card{background:#f0f4ff;}")
          .append("h1{color:#0066cc;}}")
          .append("</style></head><body>");

        sb.append("<div style='display:flex;justify-content:space-between;align-items:center;'>")
          .append("<h1>📊 Metis AGI — Eval Dashboard</h1>")
          .append("<div><a href='/api/status'>API Status</a> | <a href='/api/metrics'>Metrics</a></div>")
          .append("</div>");
        sb.append("<div class='refresh-bar'>Auto-refresh every 2 min &middot; ")
          .append(String.valueOf(reports.size())).append(" reports</div>");

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
        sb.append("<div class='stat-card'><h3>Runs</h3><div class='value'>").append(String.valueOf(totalRuns)).append("</div></div>")
          .append("</div>");

        // Table
        sb.append("<table><thead><tr><th>#</th><th>Report</th><th>Time</th><th>Tier</th>")
          .append("<th>Gate</th><th>Metrics</th></tr></thead><tbody>");
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
              .append("<td>");

            // Metric dots + detail
            sb.append("<details><summary>").append(String.valueOf(r.metrics().size())).append(" metrics</summary>");
            for (var m : r.metrics()) {
                String dotClass = "HARD".equals(m.gate()) && !"PASS".equals(r.gate()) ? "dot-fail"
                    : "HARD".equals(m.gate()) ? "dot-pass"
                    : "dot-soft";
                String valueStr = String.format("%.2f", m.mean());
                sb.append("<div style='margin:2px 0;font-size:12px;'>")
                  .append("<span class='metric-dot ").append(dotClass).append("'></span> ")
                  .append(htmlEscape(m.category())).append(".").append(htmlEscape(m.metricName()))
                  .append(" = ").append(valueStr)
                  .append(" (runs=").append(String.valueOf(m.runs())).append(")")
                  .append("</div>");
            }
            sb.append("</details>");

            sb.append("</td></tr>");
        }
        sb.append("</tbody></table>")
          .append("<p style='color:#888;font-size:12px;margin-top:10px;'>")
          .append("Metis AGI — Auto-generated from eval-reports/ (v0.11.9)")
          .append("</p></body></html>");
        return sb.toString();
    }

    private static String htmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public record MetricDetail(String category, String metricName, double mean, int runs, String gate) {}
    public record ReportEntry(String fileName, String timestamp,
                              int passed, int failed, int total, String gate,
                              String taskType, List<MetricDetail> metrics) {}
}
