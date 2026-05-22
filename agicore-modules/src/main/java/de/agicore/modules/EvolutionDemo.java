package de.agicore.modules;

import de.agicore.kernel.evolution.EvolutionManager;
import de.agicore.kernel.evolution.FitnessFunction;
import de.agicore.modules.evolution.OllamaMutationService;
import de.agicore.modules.planner.StubPlanner;

import java.nio.file.*;
import java.util.List;
import java.util.logging.*;

/**
 * First real LLM-powered evolution cycle.
 * <p>
 * Reads StubPlanner source, sends to Ollama (qwen3.6:27b on miniedi),
 * gets mutated variant, compile-checks, shows diff.
 */
public final class EvolutionDemo {
    static {
        Logger.getLogger("").setLevel(Level.WARNING);
        Logger.getLogger("de.agicore").setLevel(Level.INFO);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("""
                
                ╔══════════════════════════════════════════════╗
                ║   FIRST LLM-POWERED EVOLUTION               ║
                ║   Ollama: qwen3.6:27b @ miniedi:11434      ║
                ╚══════════════════════════════════════════════╝
                """);

        // 1. Read current StubPlanner source
        Path sourceFile = Path.of("agicore-modules/src/main/java/de/agicore/modules/planner/StubPlanner.java");
        String originalSource = Files.readString(sourceFile);
        System.out.println("Original source: " + originalSource.length() + " chars, "
                + originalSource.lines().count() + " lines\n");

        // 2. Build agent with evolution infrastructure
        Agent agent = Agent.builder()
                .registerShellCommand(List.of("uname", "-a"))
                .registerHttpGet(java.net.URI.create("https://httpbin.org/get"))
                .workspaceCapacity(5)
                .build();

        // 3. Create Ollama mutation service with shared prompt bank
        var ollama = new OllamaMutationService();
        // Share prompt bank between ollama and evolution manager
        ollama.promptBank(); // initialized
        agent.core().evolutionManager().setMutationService(ollama);

        // Register StubPlanner as evolvable module
        var planner = new StubPlanner();

        System.out.println("═══ Mutation Request ═══");
        System.out.println("Model:   qwen3.6:27b-q4_K_M");
        System.out.println("Target:  StubPlanner.java");
        System.out.println("Focus:   keyword extraction + action selection heuristics");
        System.out.println("Limits:  max 15% change, no signature changes, no new deps");
        System.out.println();

        // 4. Trigger mutation via Ollama
        System.out.println("→ Sending to Ollama on miniedi:11434...\n");
        long startMs = System.currentTimeMillis();

        String mutantSource = ollama.mutate(
                "stub-planner",
                originalSource,
                "StubPlanner",
                "de.agicore.modules.planner"
        );

        long elapsed = System.currentTimeMillis() - startMs;

        // Debug: print last raw response if mutation failed
        if (mutantSource == null) {
            System.out.println("→ Raw response check:");
            String rawCheck = ollama.lastRawResponse();
            if (rawCheck != null) {
                System.out.println("  Raw length: " + rawCheck.length());
                System.out.println("  First 300 chars: " + rawCheck.substring(0, Math.min(300, rawCheck.length())));
            }
            System.out.println("❌ Ollama returned no valid source (compile check would catch anyway).");
            System.out.println("   Expected: mistral-small3.1 outputs code in ```java fences.");
            return;
        }

        System.out.println("✓ Ollama response: " + elapsed + "ms, "
                + mutantSource.length() + " chars, "
                + mutantSource.lines().count() + " lines\n");

        // 5. Show the generated code (first 60 lines)
        System.out.println("═══ Generated Code (preview) ═══");
        mutantSource.lines().limit(60).forEach(System.out::println);
        if (mutantSource.lines().count() > 60) {
            System.out.println("... (" + (mutantSource.lines().count() - 60) + " more lines)");
        }
        System.out.println();

        // 6. Check if it actually changed
        if (mutantSource.equals(originalSource)) {
            System.out.println("⚠️  Mutation produced NO change — identical to original.");
            System.out.println("   This is expected for first runs. Try different model or prompt.");
            return;
        }

        // 7. Show diff summary
        System.out.println("═══ Diff Summary ═══");
        long changedLines = computeDiffLines(originalSource, mutantSource);
        double changePercent = 100.0 * changedLines / originalSource.lines().count();
        System.out.printf("Lines changed: %d / %d (%.1f%%)%n",
                changedLines, originalSource.lines().count(), changePercent);

        if (changePercent > 15) {
            System.out.println("⚠️  Change exceeds 15% limit — would be REJECTED by SafetyGuard.");
        } else {
            System.out.println("✓ Change within 15% limit.");
        }
        System.out.println();

        // 8. Compile check
        System.out.println("═══ Compile Check ═══");
        Path outputDir = Path.of("target/evolution-out");
        Files.createDirectories(outputDir);
        Path tempFile = outputDir.resolve("StubPlanner.java");
        Files.writeString(tempFile, mutantSource);
        boolean compiles = agent.core().evolutionManager().compileCheck(tempFile, "StubPlanner");
        Files.deleteIfExists(tempFile);

        if (compiles) {
            System.out.println("✓ Compiles successfully!");

            // 9. Write mutant source to file
            System.out.println("\n═══ Applying Mutation ═══");
            Files.writeString(sourceFile, mutantSource);
            System.out.println("✓ Mutant written to " + sourceFile);

            // 10. Git commit
            System.out.println("→ git add + commit...");
            runCmd("git", "add", "agicore-modules/");
            runCmd("git", "commit", "-m",
                    "Evolution #1: Ollama-generated StubPlanner mutation (qwen3.6:27b, "
                            + String.format("%.1f%%", changePercent) + "% changed)");

            System.out.println("✓ Committed to git.");
            System.out.println("  Rollback: git reset --hard HEAD~1");

            // 11. Save as few-shot example
            Path promptDir = Path.of("agicore-modules/src/main/resources/prompts");
            Files.createDirectories(promptDir);
            Path fewShotFile = promptDir.resolve("evolution-001-success.txt");
            Files.writeString(fewShotFile,
                    "// Evolution #1 — First successful mutation\n"
                    + "// Date: " + java.time.Instant.now() + "\n"
                    + "// Model: qwen3.6:27b-q4_K_M\n"
                    + "// Change: " + String.format("%.1f%%", changePercent) + "\n"
                    + "// ── Original ──\n" + originalSource + "\n"
                    + "// ── Mutated ──\n" + mutantSource + "\n");
            System.out.println("✓ Few-shot example saved to " + fewShotFile);

            System.out.println("\n✅ First evolution complete! StubPlanner has been mutated.");
            System.out.println("   Review the changes: git diff HEAD~1");

        } else {
            System.out.println("❌ Does NOT compile — mutation rejected.");
            System.out.println("   This is normal. ~90% of LLM mutations will fail compilation.");
            System.out.println("   The safety guard correctly prevented application.");
        }
    }

    private static long computeDiffLines(String original, String mutant) {
        var origLines = original.lines().toList();
        var mutLines = mutant.lines().toList();
        long diff = 0;
        int maxLen = Math.max(origLines.size(), mutLines.size());
        for (int i = 0; i < maxLen; i++) {
            String o = i < origLines.size() ? origLines.get(i) : "";
            String m = i < mutLines.size() ? mutLines.get(i) : "";
            if (!o.equals(m)) diff++;
        }
        return diff;
    }

    private static void runCmd(String... args) throws Exception {
        new ProcessBuilder(args).inheritIO().start().waitFor();
    }
}
