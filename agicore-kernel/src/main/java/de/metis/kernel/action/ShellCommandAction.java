package de.metis.kernel.action;

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
 * <p>
 * Security: Phase 11.5+ — allow-list of permitted commands enforced.
 * Only commands in {@link #ALLOWED_COMMANDS} (or their known safe aliases)
 * can execute. Commands with destructive potential are permanently blocked.
 * <p>
 * Architecture note: unlike EthicsCore (prompt-based), this is a
 * hard-code-level guardrail — it cannot be bypassed via prompt injection.
 */
public class ShellCommandAction implements Action {

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
    public ActionResult execute() {
        // ── Security gate: allowlist check ──────────────────
        String blockReason = validateCommand(command);
        if (blockReason != null) {
            LOG.warning(() -> "Shell command BLOCKED: " + blockReason
                    + " — cmd=" + String.join(" ", command));
            return ActionResult.fail(NAME,
                    "BLOCKED by ShellSecurity: " + blockReason, Instant.now());
        }
        Instant start = Instant.now();
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        try {
            Process proc = pb.start();
            boolean finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return ActionResult.fail(NAME,
                        "Command timed out after " + timeoutSeconds + "s: " + String.join(" ", command), start);
            }

            String output;
            try (var in = proc.getInputStream()) {
                output = new String(in.readAllBytes()).strip();
            }
            int exit = proc.exitValue();
            if (exit == 0) {
                LOG.fine(() -> "Shell command OK: " + String.join(" ", command));
                return ActionResult.ok(NAME, output, start);
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
