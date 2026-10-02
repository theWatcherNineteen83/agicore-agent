package de.metis.modules.selfrefactor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Selfrefactor-Gate (Phase 12b) — harte Schranke gegen stille Source-Downgrades.
 *
 * <p>Der Selfrefactor-Loop (FeatureGenAction, MultiFileCodeGen) hat mehrfach
 * bestehende, freigegebene Module (MathCore, RepoIndex, RoadmapReader,
 * CharCounter) mit schlechteren Neu-Generierungen ueberschrieben. Dieses Gate
 * macht solche Writes genehmigungspflichtig:</p>
 *
 * <ul>
 *   <li>Neue Dateien (existieren nicht) → erlaubt.</li>
 *   <li>Dateien ausserhalb eines {@code src/main/java}-Baums → erlaubt.</li>
 *   <li>Bestehende Source-Datei nur mit exaktem Eintrag in
 *       {@code <projectRoot>/.self-refactor-writescope} → erlaubt.</li>
 *   <li>Sonst: Block; der Inhalt landet stattdessen unter
 *       {@code <projectRoot>/.self-refactor-staging/...} und muss von einem
 *       Menschen geprueft/freigegeben werden (Checkpoint-Pflicht vor Merge).</li>
 * </ul>
 *
 * <p>Die Writescope-Liste ist absichtlich leer per Default: Metis darf keine
 * bestehende Source-Datei ohne menschliche Freigabe veroeffentlichen.</p>
 */
public final class SelfRefactorGate {

    private static final Logger LOG = Logger.getLogger(SelfRefactorGate.class.getName());

    /** Eine erlaubte Ueberschreib-Zielrelation pro Zeile; '#' = Kommentar. */
    public static final String WRITESCOPE_FILE = ".self-refactor-writescope";
    /** Blockierte Inhalte landen hier zur menschlichen Pruefung. */
    public static final String STAGING_DIR = ".self-refactor-staging";

    /**
     * Gate-Entscheidung. {@code allowed=true} → normal schreiben.
     * {@code allowed=false} → zwingend {@code stagedPath} verwenden, niemals das Original.
     */
    public record Decision(boolean allowed, Path stagedPath, String reason) {
        public boolean blocked() { return !allowed; }
    }

    private SelfRefactorGate() {}

    public static Decision check(Path projectRoot, Path targetFile) {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path target = targetFile.toAbsolutePath().normalize();

        if (!Files.exists(target)) {
            return new Decision(true, null, "new file — no existing source to downgrade");
        }
        if (!target.startsWith(root)) {
            return new Decision(true, null, "outside project root — not a selfrefactor source write");
        }
        String rel = root.relativize(target).toString().replace('\\', '/');
        if (!rel.contains("src/main/java")) {
            return new Decision(true, null, "not inside a src/main/java tree");
        }

        Path scope = root.resolve(WRITESCOPE_FILE);
        if (Files.exists(scope)) {
            try {
                for (String line : Files.readAllLines(scope)) {
                    String s = line.trim();
                    if (s.isEmpty() || s.startsWith("#")) continue;
                    String needle = s.replaceFirst("^/+", "");
                    if (rel.equals(needle) || rel.endsWith("/" + needle)) {
                        return new Decision(true, null, "write-scope entry: " + s);
                    }
                }
            } catch (IOException e) {
                LOG.warning("SelfRefactorGate: cannot read writescope, denying overwrite: " + e.getMessage());
            }
        }

        Path staged = root.resolve(STAGING_DIR).resolve(rel);
        String msg = "GATE(12b): existing source file must not be overwritten without write-scope entry: "
                + rel + " — staged for human review at " + STAGING_DIR + "/" + rel;
        LOG.warning(msg);
        return new Decision(false, staged, msg);
    }
}
