package de.metis.kernel.action;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Linux-Lernmodus — systematische Erkundung von Shell-Kommandos.
 * <p>
 * Führt eine Liste von Lern-Kommandos aus, analysiert deren Output und
 * liefert strukturierte Ergebnisse zurück, die Metis als Beliefs in ihr
 * WorldModel übernehmen kann.
 * <p>
 * Drei Erkundungs-Stufen:
 * <ol>
 *   <li>Basic: ls, pwd, whoami, date, uptime, uname</li>
 *   <li>System: df, free, ps, top -bn1, netstat, ip addr</li>
 *   <li>Files: find, du, wc, grep, cat, head, tail</li>
 * </ol>
 * <p>
 * Jedes Kommando wird mit Timeout (10s) ausgeführt. Erfolg/Misserfolg
 * und Output-Länge werden dokumentiert. Metis kann daraus Beliefs bilden.
 */
public class LinuxExploreAction implements Action {

    private static final Logger LOG = Logger.getLogger(LinuxExploreAction.class.getName());

    public static final String NAME = "linux-explore";

    private static final long TIMEOUT_SECONDS = 10;
    private static final int MAX_OUTPUT_LINES = 20;

    // ── Lern-Kommandos nach Stufe ──────────────────────────────────

    private static final List<String[]> LEVEL_BASIC = List.of(
            new String[]{"ls", "-la", "/home"},
            new String[]{"pwd"},
            new String[]{"whoami"},
            new String[]{"date"},
            new String[]{"uptime"},
            new String[]{"uname", "-a"}
    );

    private static final List<String[]> LEVEL_SYSTEM = List.of(
            new String[]{"df", "-h"},
            new String[]{"free", "-h"},
            new String[]{"ps", "aux", "--no-headers"},
            new String[]{"ip", "addr", "show"},
            new String[]{"ss", "-tlnp"}
    );

    private static final List<String[]> LEVEL_FILES = List.of(
            new String[]{"find", "/tmp", "-maxdepth", "1", "-type", "f"},
            new String[]{"du", "-sh", "/home/prometheus"},
            new String[]{"wc", "-l", "/etc/hosts"},
            new String[]{"head", "-5", "/etc/passwd"},
            new String[]{"cat", "/proc/cpuinfo"}
    );

    private final int level; // 1=basic, 2=system, 3=files

    public LinuxExploreAction(int level) {
        this.level = Math.max(1, Math.min(3, level));
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ActionResult execute() {
        Instant start = Instant.now();
        List<String[]> commands = switch (level) {
            case 1 -> LEVEL_BASIC;
            case 2 -> LEVEL_SYSTEM;
            case 3 -> LEVEL_FILES;
            default -> LEVEL_BASIC;
        };

        StringBuilder report = new StringBuilder();
        report.append("Linux-Erkundung (Stufe ").append(level).append("):\n");
        report.append("=" .repeat(50)).append("\n\n");

        int successCount = 0;
        int totalCount = commands.size();

        for (String[] cmd : commands) {
            String cmdStr = String.join(" ", cmd);
            report.append("$ ").append(cmdStr).append("\n");

            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);

                Process proc = pb.start();
                boolean finished = proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

                if (!finished) {
                    proc.destroyForcibly();
                    report.append("  ❌ Timeout (>").append(TIMEOUT_SECONDS).append("s)\n\n");
                    continue;
                }

                String output;
                try (var in = proc.getInputStream()) {
                    output = new String(in.readAllBytes());
                }

                int exitCode = proc.exitValue();
                boolean success = exitCode == 0;

                if (success) successCount++;

                // Erste N Zeilen des Outputs
                String[] lines = output.split("\n");
                int showLines = Math.min(lines.length, MAX_OUTPUT_LINES / 2);
                for (int i = 0; i < showLines; i++) {
                    String line = lines[i].trim();
                    if (line.length() > 120) line = line.substring(0, 117) + "...";
                    report.append("  ").append(success ? "  " : "❌ ").append(line).append("\n");
                }
                if (lines.length > showLines) {
                    report.append("  ... (").append(lines.length - showLines)
                            .append(" weitere Zeilen, insgesamt ").append(output.length())
                            .append(" Bytes)\n");
                }

                if (!success) {
                    report.append("  Exit-Code: ").append(exitCode).append("\n");
                }
                report.append("\n");

            } catch (IOException e) {
                report.append("  ❌ Fehler: ").append(e.getMessage()).append("\n\n");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                report.append("  ❌ Unterbrochen\n\n");
            }
        }

        report.append("Ergebnis: ").append(successCount).append("/").append(totalCount)
                .append(" Kommandos erfolgreich\n");

        final int finalSuccess = successCount;
        final int finalTotal = totalCount;
        final int finalLevel = level;
        LOG.fine(() -> "Linux-Explore L" + finalLevel + ": " + finalSuccess + "/" + finalTotal);
        return ActionResult.ok(NAME, report.toString(), start);
    }

    @Override
    public String toString() {
        return "LinuxExploreAction[level=" + level + "]";
    }
}
