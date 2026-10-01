package de.metis.kernel.action;

import de.metis.kernel.goal.Goal;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Executes a shell command with a configurable timeout.
 * <p>
 * The process is spawned via {@link ProcessBuilder}. Stdout and stderr
 * are merged. If the command exceeds {@code timeoutSeconds} it is
 * forcibly destroyed.
 * <p>
 * Security: Phase 11.5+ — allow-list of permitted commands enforced.
 * Only commands in {@link #ALLOWED_COMMANDS} (or their known safe aliases)
 * can execute. Commands with destructive potential are permanently blocked.
 * <p>
 * Architecture note: unlike EthicsCore (prompt-based), this is a
 * hard-code-level guardrail — it cannot be bypassed via prompt injection.
 * <p>
 * <b>uname-Falle Dauer-Fix (01.10.2026):</b> implements
 * {@link GoalAwareAction}. When a goal is injected, the goal description is
 * parsed for an explicit command (marker {@code Befehl:} or backticked
 * {@code `...`}). If found and allow-list-valid, it OVERRIDES the fixed
 * registration command (historically {@code uname -a}, which made every
 * shell-routed user task fail LLM acceptance). Parsing never widens the
 * allow-list: an extracted command still passes {@link #validateCommand}.
 * If the description has no command, the configured default runs unchanged.
 */
public class ShellCommandAction implements Action, GoalAwareAction {

    private static final Logger LOG = Logger.getLogger(ShellCommandAction.class.getName());

    /** The action name registered in the executor. */
    public static final String NAME = "shell";

    // ── Shell Security: Allowlist ───────────────────────────────
    /** Read-only / safe commands that Metis may execute. */
    private static final Set<String> ALLOWED_COMMANDS = Set.of(
            "cat", "head", "tail", "ls", "find", "file", "stat",
            "du", "df", "ps", "free", "uptime", "uname", "hostname",
            "whoami", "id", "ping", "curl", "wget", "host", "dig",
            "ss", "ip", "grep", "awk", "sed", "cut", "sort", "uniq",
            "wc", "tr", "diff", "echo", "printf", "date", "which",
            "systemctl", "journalctl", "git", "java", "javac", "mvn",
            "dpkg", "apt", "pip", "pip3", "tar", "gzip", "unzip"
    );
    /** Commands unconditionally blocked (destructive/network-server). */
    private static final Set<String> BLOCKED_COMMANDS = Set.of(
            "rm", "mv", "cp", "dd", "mkfs", "mkswap", "fdisk", "parted",
            "shred", "chmod", "chown", "chgrp", "chattr", "mount", "umount",
            "useradd", "usermod", "userdel", "passwd", "su",
            "iptables", "nft", "ufw", "shutdown", "reboot", "halt",
            "kill", "pkill", "killall", "crontab", "at",
            "nc", "ncat", "socat", "telnet", "eval", "exec", "source",
            "docker", "podman", "kubectl", "helm", "cryptsetup"
    );
    private static final Set<String> ALLOWED_SYSTEMCTL = Set.of(
            "status", "is-active", "list-units", "show", "restart"
    );

    static String validateCommand(List<String> command) {
        if (command == null || command.isEmpty()) return "empty command";
        String cmd = command.getFirst();
        if (cmd.contains("/")) cmd = cmd.substring(cmd.lastIndexOf('/') + 1);
        if (BLOCKED_COMMANDS.contains(cmd))
            return "blocked command: " + cmd;
        if (!ALLOWED_COMMANDS.contains(cmd))
            return "unknown command (not allowlisted): " + cmd;
        if ("systemctl".equals(cmd) && command.size() >= 2) {
            String sub = command.get(1);
            if (!ALLOWED_SYSTEMCTL.contains(sub))
                return "systemctl subcommand not allowed: " + sub;
        }
        return null;
    }

    private final List<String> command;
    private final long timeoutSeconds;

    /** Goal injected right before execute() — may be null (non-goal context). */
    private volatile Goal currentGoal;

    /**
     * @param command        command and arguments (e.g. {@code ["ls", "-la"]})
     * @param timeoutSeconds max runtime before kill; must be &gt; 0
     */
    public ShellCommandAction(List<String> command, long timeoutSeconds) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds must be > 0, got " + timeoutSeconds);
        }
        this.command = List.copyOf(command);
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override public String category() {
        return "read";
    }

    @Override
    public void setCurrentGoal(Goal goal) {
        this.currentGoal = goal;
    }

    /** Words that end the machine-command part after "Befehl:" (natural-language tail). */
    private static final Set<String> PROSE_STOPWORDS = Set.of(
            "aus", "und", "dann", "bitte", "melde", "fuehre", "den", "die", "das",
            "als", "evidenz", "auf", "dem", "der", "des", "von", "mit", "ausgabe",
            "pruefe", "zeige");

    /**
     * Extract an explicit command from the goal description.
     * Recognized forms (first match wins):
     * <ul>
     *   <li>{@code Befehl: <cmd> [args...]} — rest of that line, cut at the first
     *       prose stop-word (e.g. "uptime aus und melde …" → {@code [uptime]})</li>
     *   <li>a backticked span {@code `<cmd> [args...]`} — taken literally, no stop-cut</li>
     * </ul>
     * Returns {@code null} if nothing parses or the extracted command
     * fails the allow-list (then the fixed default runs).
     */
    static List<String> parseCommandFromGoal(String description) {
        if (description == null || description.isBlank()) return null;
        String payload = null;
        boolean proseCut = false;
        int marker = description.toLowerCase().indexOf("befehl:");
        if (marker >= 0) {
            String rest = description.substring(marker + "befehl:".length());
            int nl = rest.indexOf('\n');
            payload = (nl >= 0 ? rest.substring(0, nl) : rest).trim();
            proseCut = true;
        }
        if (payload == null || payload.isEmpty()) {
            int bt = description.indexOf('`');
            if (bt >= 0) {
                int end = description.indexOf('`', bt + 1);
                if (end > bt) payload = description.substring(bt + 1, end).trim();
            }
        }
        if (payload == null || payload.isEmpty()) return null;
        List<String> raw = List.of(payload.split("\\s+"));
        List<String> parts = new java.util.ArrayList<>(raw.size());
        for (String tok : raw) {
            if (proseCut && PROSE_STOPWORDS.contains(tok.toLowerCase())) break;
            String clean = tok.replaceAll("[.,;:?]$", "");
            if (clean.isEmpty()) break;
            parts.add(clean);
        }
        if (parts.isEmpty()) return null;
        if (validateCommand(parts) != null) return null; // never widen the guardrail
        return parts;
    }

    private List<String> effectiveCommand() {
        List<String> fromGoal = parseCommandFromGoal(
                currentGoal != null ? currentGoal.description() : null);
        if (fromGoal != null) {
            LOG.info("shell: using goal-parsed command (uname-Falle fix): " + String.join(" ", fromGoal));
            return fromGoal;
        }
        return command;
    }

    @Override
    public ActionResult execute() {
        List<String> cmd = effectiveCommand();
        // ── Security gate: allowlist check ──────────────────
        String blockReason = validateCommand(cmd);
        if (blockReason != null) {
            LOG.warning(() -> "Shell command BLOCKED: " + blockReason
                    + " — cmd=" + String.join(" ", cmd));
            return ActionResult.fail(NAME,
                    "BLOCKED by ShellSecurity: " + blockReason, Instant.now());
        }
        Instant start = Instant.now();
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);

        try {
            Process proc = pb.start();
            boolean finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return ActionResult.fail(NAME,
                        "Command timed out after " + timeoutSeconds + "s: " + String.join(" ", cmd), start);
            }

            String output;
            try (var in = proc.getInputStream()) {
                output = new String(in.readAllBytes()).strip();
            }
            int exit = proc.exitValue();
            String evidence = "$ " + String.join(" ", cmd) + "\n" + output;
            if (exit == 0) {
                LOG.fine(() -> "Shell command OK: " + String.join(" ", cmd));
                return ActionResult.ok(NAME, evidence, start);
            } else {
                return ActionResult.fail(NAME,
                        "Exit code " + exit + ": " + output, start);
            }
        } catch (IOException e) {
            return ActionResult.fail(NAME,
                    "IO error: " + e.getMessage(), start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ActionResult.fail(NAME, "Interrupted", start);
        }
    }

    @Override
    public String toString() {
        return "ShellCommandAction[" + String.join(" ", command) + "]";
    }
}
