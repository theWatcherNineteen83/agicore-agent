package de.agicore.kernel.action;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * File system action — read, list, write files.
 * <p>
 * Three modes:
 * <ul>
 *   <li>{@code LIST} — list directory contents</li>
 *   <li>{@code READ} — read a single file</li>
 *   <li>{@code WRITE} — write content to a file (bounded)</li>
 * </ul>
 * <p>
 * Security: paths are restricted to a sandbox root. Write mode has
 * a max content size limit. Future: allow-list of permitted paths.
 */
public class FileSystemAction implements Action {

    private static final Logger LOG = Logger.getLogger(FileSystemAction.class.getName());

    private static final int MAX_READ_BYTES = 10_000;
    private static final int MAX_LIST_ENTRIES = 100;
    private static final int MAX_WRITE_BYTES = 50_000;

    public enum Mode { LIST, READ, WRITE }

    private final String actionName;
    private final Mode mode;
    private final Path rootPath;

    /**
     * @param actionName registered action name (e.g. "filesystem-list")
     * @param mode       operation mode
     * @param rootPath   base directory for this action
     */
    public FileSystemAction(String actionName, Mode mode, Path rootPath) {
        this.actionName = actionName;
        this.mode = mode;
        this.rootPath = rootPath.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return actionName;
    }

    @Override
    public ActionResult execute() {
        Instant start = Instant.now();
        try {
            return switch (mode) {
                case LIST -> executeList(start);
                case READ -> executeRead(start);
                case WRITE -> executeWrite(start);
            };
        } catch (Exception e) {
            return ActionResult.fail(actionName,
                    mode + " error: " + e.getClass().getSimpleName() + " - " + e.getMessage(), start);
        }
    }

    private ActionResult executeList(Instant start) throws IOException {
        if (!Files.isDirectory(rootPath)) {
            return ActionResult.fail(actionName,
                    "Not a directory: " + rootPath, start);
        }

        String listing;
        try (var stream = Files.list(rootPath)) {
            listing = stream
                    .limit(MAX_LIST_ENTRIES)
                    .map(p -> {
                        try {
                            String type = Files.isDirectory(p) ? "d" : "f";
                            long size = Files.isDirectory(p) ? 0 : Files.size(p);
                            return type + " " + p.getFileName() + " (" + size + " bytes)";
                        } catch (IOException e) {
                            return "? " + p.getFileName();
                        }
                    })
                    .collect(Collectors.joining("\n"));
        }

        final String result = listing.isEmpty() ? "(empty directory)" : listing;
        LOG.fine(() -> "Listed " + rootPath + ": " + result.lines().count() + " entries");
        return ActionResult.ok(actionName, "Directory listing for " + rootPath + ":\n" + result, start);
    }

    private ActionResult executeRead(Instant start) throws IOException {
        if (!Files.isRegularFile(rootPath)) {
            return ActionResult.fail(actionName,
                    "Not a regular file: " + rootPath, start);
        }

        byte[] bytes = Files.readAllBytes(rootPath);
        String content;
        if (bytes.length > MAX_READ_BYTES) {
            content = new String(bytes, 0, MAX_READ_BYTES) + "\n... [truncated " + bytes.length + " total bytes]";
        } else {
            content = new String(bytes);
        }

        LOG.fine(() -> "Read " + rootPath + ": " + bytes.length + " bytes");
        return ActionResult.ok(actionName, "File content of " + rootPath + ":\n" + content, start);
    }

    private ActionResult executeWrite(Instant start) throws IOException {
        // Write mode: append discovery data (self-documentation)
        Path outputFile = rootPath.resolve("agicore-discovery.txt");
        String timestamp = Instant.now().toString();
        String content = "AGI Core discovery at " + timestamp + "\n"
                + "Action: " + actionName + "\n"
                + "This file documents agent exploration of the filesystem.\n";

        if (content.length() > MAX_WRITE_BYTES) {
            return ActionResult.fail(actionName,
                    "Write content exceeds max size: " + content.length(), start);
        }

        Files.createDirectories(rootPath);
        Files.writeString(outputFile, content,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        LOG.fine(() -> "Wrote discovery to " + outputFile);
        return ActionResult.ok(actionName,
                "Wrote discovery note to " + outputFile + " (" + content.length() + " bytes)", start);
    }

    @Override
    public String toString() {
        return "FileSystemAction[" + actionName + " " + mode + " " + rootPath + "]";
    }
}
