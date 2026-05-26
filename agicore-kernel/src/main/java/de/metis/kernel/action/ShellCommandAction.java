package de.metis.kernel.action;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Executes a shell command with a configurable timeout.
 * <p>
 * The process is spawned via {@link ProcessBuilder}. Stdout and stderr
 * are merged. If the command exceeds {@code timeoutSeconds} it is
 * forcibly destroyed.
 * <p>
 * Security note: command strings are passed to the shell verbatim.
 * The caller is responsible for sanitising input. In a future phase
 * this action should gain an allow-list of permitted commands.
 */
public class ShellCommandAction implements Action {

    private static final Logger LOG = Logger.getLogger(ShellCommandAction.class.getName());

    /** The action name registered in the executor. */
    public static final String NAME = "shell";

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
        Instant start = Instant.now();
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true); // merge stderr into stdout

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
