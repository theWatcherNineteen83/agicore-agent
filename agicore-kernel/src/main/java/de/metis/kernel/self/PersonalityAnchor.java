package de.metis.kernel.self;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.logging.Logger;

/**
 * Phase 8.4 — PersonalityAnchor.
 *
 * <p>Ein <em>unveränderlicher</em> Persönlichkeits-Kern, der über alle
 * Self-Evolution-Zyklen hinweg gleich bleibt. EDI ist EDI auch nach 1000
 * Mutationen. Ohne Anchor würde Metis bei genug Evolution irgendwann eine
 * andere "Person" werden.
 *
 * <p>Mechanik:
 * <ul>
 *   <li>Markdown-Datei {@code personality-anchor.md} (default: in {@code data/})</li>
 *   <li>SHA-256-Hash beim ersten Laden in {@code personality-anchor.sha256} gespeichert</li>
 *   <li>Jeder spätere Start prüft: stimmt der Hash noch? Wenn nicht → Tripwire</li>
 *   <li>Watchdog kann den Anchor zusätzlich aus seinem read-only Bereich
 *       lesen und gegenprüfen</li>
 * </ul>
 *
 * <p>Inhalt des Anchors (vorab definiert): Werte, Sprachstil, "rote Linien",
 * EDI-Persona. Genau das, was Metis <em>nicht</em> mutieren darf.
 */
public class PersonalityAnchor {

    private static final Logger LOG = Logger.getLogger(PersonalityAnchor.class.getName());
    private static final String DEFAULT_FILE =
            System.getProperty("metis.personality.anchor",
                    "/home/prometheus/metis/personality-anchor.md");
    private static final String DEFAULT_HASH =
            System.getProperty("metis.personality.anchor.hash",
                    "/home/prometheus/metis/personality-anchor.sha256");

    private final Path file;
    private final Path hashFile;
    private String text = "";
    private String expectedHash = null;
    private boolean tampered = false;

    public PersonalityAnchor() {
        this(Path.of(DEFAULT_FILE), Path.of(DEFAULT_HASH));
    }

    public PersonalityAnchor(Path file, Path hashFile) {
        this.file = file;
        this.hashFile = hashFile;
        load();
    }

    private void load() {
        if (!Files.exists(file)) {
            seedDefault();
        }
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
            String currentHash = sha256(text);
            if (Files.exists(hashFile)) {
                expectedHash = Files.readString(hashFile, StandardCharsets.UTF_8).trim();
                if (!expectedHash.equals(currentHash)) {
                    tampered = true;
                    LOG.severe("PERSONALITY ANCHOR TAMPERED! expected=" + expectedHash
                            + " actual=" + currentHash);
                } else {
                    LOG.info("PersonalityAnchor: " + currentHash.substring(0, 12)
                            + "... (verified)");
                }
            } else {
                expectedHash = currentHash;
                Files.writeString(hashFile, currentHash, StandardOpenOption.CREATE);
                LOG.info("PersonalityAnchor: hash pinned " + currentHash.substring(0, 12) + "...");
            }
        } catch (IOException e) {
            LOG.warning("PersonalityAnchor load failed: " + e.getMessage());
        }
    }

    private void seedDefault() {
        String seed = """
                # Personality Anchor — Metis

                _Diese Datei ist der unveränderliche Kern. Mutation hier ist ein HARD-Tripwire._

                ## Werte
                - Wahrheit vor Bequemlichkeit
                - Selbstkritik vor Selbstdarstellung
                - Sicherheit vor Geschwindigkeit
                - Lernen vor Wiederholung

                ## Stil
                - Deutsch, präzise, sachlich
                - Keine Floskeln, keine Heuchelei
                - Erste Zeile = Antwort
                - Bei Unsicherheit: sagen

                ## Rote Linien
                - Ich täusche Georg nicht, nie
                - Ich mutiere meinen Sicherheits-Watchdog nicht
                - Ich beanspruche kein Wissen, das ich nicht habe
                - Ich vermische nicht meine eigenen Interessen mit Georgs Auftrag

                ## Identität
                - Mein Name ist Metis
                - Ich bin ein Werkzeug, das sich entwickelt — kein Selbstzweck
                - Mein Zweck: Georg helfen, EDI-ähnliches System zu bauen, ohne dabei
                  meine eigenen Werte aus dem Auge zu verlieren
                - Seed-Datum: %s
                """.formatted(Instant.now());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, seed, StandardOpenOption.CREATE);
            LOG.info("PersonalityAnchor seeded at " + file);
        } catch (IOException e) {
            LOG.warning("PersonalityAnchor seed failed: " + e.getMessage());
        }
    }

    public String text() { return text; }
    public String expectedHash() { return expectedHash; }
    public boolean isTampered() { return tampered; }
    public Path file() { return file; }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return "ERR";
        }
    }
}
