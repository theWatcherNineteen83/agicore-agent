package de.metis.kernel.action;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Reads Java source files from the Metis project tree.
 * <p>
 * Two modes:
 * <ul>
 *   <li>{@code FIND} — search for a class by simple name (e.g. "OllamaPlanner")</li>
 *   <li>{@code READ} — read a specific source file by relative path</li>
 * </ul>
 * <p>
 * Enables Metis to inspect its own implementation and propose targeted
 * optimisations. This is the missing piece between self-analysis (metrics)
 * and self-modification (EvolutionManager).
 * <p>
 * Safety: read-only, category "read", AUTO approval. No write access.
 * Restricted to {@code .java} files within the project roots.
 */
public class ReadSourceAction implements Action {

    private static final Logger LOG = Logger.getLogger(ReadSourceAction.class.getName());

    public static final String NAME = "source-read";

    private static final int MAX_FILE_BYTES = 100_000; // ~3000 lines

    private final String target;           // class name or relative path
    private final List<Path> sourceRoots;  // project source directories

    /**
     * @param target      class name (e.g. "OllamaPlanner") or relative path
     *                    (e.g. "de/metis/kernel/evolution/EvolutionManager.java")
     * @param sourceRoots one or more source-root directories to search
     */
    public ReadSourceAction(String target, List<Path> sourceRoots) {
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("target must not be empty");
        }
        if (sourceRoots == null || sourceRoots.isEmpty()) {
            throw new IllegalArgumentException("sourceRoots must not be empty");
        }
        this.target = target.trim();
        this.sourceRoots = sourceRoots.stream()
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .toList();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String category() {
        return "read";
    }

    @Override
    public ApprovalLevel approvalLevel() {
        return ApprovalLevel.AUTO; // read-only, safe
    }

    @Override
    public ActionResult execute() {
        Instant start = Instant.now();

        try {
            // ── Mode detection ─────────────────────────────────
            boolean isPath = target.contains("/") || target.endsWith(".java");
            Path file;

            if (isPath) {
                file = resolveByPath(target);
            } else {
                file = findByClassName(target);
            }

            if (file == null) {
                return ActionResult.fail(NAME,
                        "Source not found: '" + target + "'\n"
                                + "Searched in: " + sourceRoots + "\n"
                                + (isPath ? "Try: source-read with class name (e.g. 'OllamaPlanner')"
                                          : "Try: source-read with relative path (e.g. 'de/metis/kernel/evolution/EvolutionManager.java')"),
                        start);
            }

            if (!Files.isRegularFile(file)) {
                return ActionResult.fail(NAME,
                        "Not a regular file: " + file, start);
            }

            if (!file.toString().endsWith(".java")) {
                return ActionResult.fail(NAME,
                        "Not a Java source file: " + file + " (only .java files allowed)", start);
            }

            // ── Read ───────────────────────────────────────────
            byte[] bytes = Files.readAllBytes(file);
            String content;
            String truncationNote = "";

            if (bytes.length > MAX_FILE_BYTES) {
                content = new String(bytes, 0, MAX_FILE_BYTES);
                truncationNote = "\n\n╔══════════════════════════════════════════╗\n"
                        + "║  TRUNCATED: " + String.format("%,d", bytes.length)
                        + " total bytes               ║\n"
                        + "║  Showing first " + String.format("%,d", MAX_FILE_BYTES)
                        + " bytes                     ║\n"
                        + "║  Use class-name lookup for specific sections ║\n"
                        + "╚══════════════════════════════════════════╝";
            } else {
                content = new String(bytes);
            }

            long lineCount = content.lines().count();
            String relativePath = computeRelativePath(file);

            String summary = String.format("""
                    📄 %s (%s)
                    Path: %s
                    Size: %,d bytes | %,d lines%s
                    
                    ```java
                    %s
                    ```""",
                    file.getFileName(), relativePath,
                    file, bytes.length, lineCount,
                    truncationNote,
                    content);

            LOG.info(() -> "ReadSource: " + file.getFileName() + " (" + lineCount + " lines, " + bytes.length + " bytes)");
            return ActionResult.ok(NAME, summary, start);

        } catch (SecurityException e) {
            return ActionResult.fail(NAME,
                    "Security: " + e.getMessage(), start);
        } catch (IOException e) {
            return ActionResult.fail(NAME,
                    "I/O error reading '" + target + "': " + e.getMessage(), start);
        } catch (Exception e) {
            return ActionResult.fail(NAME,
                    "Unexpected error: " + e.getClass().getSimpleName() + " - " + e.getMessage(), start);
        }
    }

    // ── Resolution ────────────────────────────────────────────────

    /** Find a file by relative path across all source roots. */
    private Path resolveByPath(String relativePath) {
        // Normalise: strip leading ./ or /
        String clean = relativePath.replaceFirst("^\\.?/", "");
        // If path doesn't end with .java, append it
        if (!clean.endsWith(".java")) {
            clean = clean + ".java";
        }

        for (Path root : sourceRoots) {
            Path candidate = root.resolve(clean).normalize();
            // Security: ensure we don't escape the source root
            if (!candidate.startsWith(root)) {
                continue;
            }
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** Find a Java source file by simple class name (e.g. "EvolutionManager"). */
    private Path findByClassName(String className) {
        String fileName = className + ".java";
        List<Path> candidates = new ArrayList<>();

        for (Path root : sourceRoots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().equals(fileName))
                        .forEach(candidates::add);
            } catch (IOException e) {
                LOG.fine("Walk failed for " + root + ": " + e.getMessage());
            }
        }

        if (candidates.isEmpty()) return null;

        // If multiple matches, prefer kernel over modules (more fundamental)
        if (candidates.size() == 1) return candidates.getFirst();

        // Sort: kernel first, then modules, then watchdog
        candidates.sort((a, b) -> {
            int scoreA = pathPriority(a);
            int scoreB = pathPriority(b);
            return Integer.compare(scoreA, scoreB);
        });

        return candidates.getFirst();
    }

    /** Priority score: lower = preferred. kernel=0, modules=1, watchdog=2, other=3. */
    private static int pathPriority(Path p) {
        String s = p.toString();
        if (s.contains("agicore-kernel")) return 0;
        if (s.contains("agicore-modules")) return 1;
        if (s.contains("agicore-watchdog")) return 2;
        return 3;
    }

    /** Compute the most useful relative path from source roots. */
    private String computeRelativePath(Path file) {
        for (Path root : sourceRoots) {
            if (file.startsWith(root)) {
                return root.relativize(file).toString();
            }
        }
        return file.getFileName().toString();
    }

    @Override
    public String toString() {
        return "ReadSourceAction[target=" + target + "]";
    }
}
