package de.metis.kernel.self;

import de.metis.kernel.metrics.FitnessSignal;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Phase 8.3 — MoodSignal mit Persistenz (Continuity-Soak-Test).
 *
 * <p>Aggregiert die FitnessSignal-Werte und externe Faktoren (Eval-Gate,
 * Goal-Erfolg, Surprise) zu einer einfachen Stimmungs-Karte mit vier Achsen:
 * <ul>
 *   <li><b>energy</b> — Throughput / Aktivität</li>
 *   <li><b>satisfaction</b> — Goal-Completion + Eval-Gate</li>
 *   <li><b>confidence</b> — currentFitness + Self-Kalibrierung</li>
 *   <li><b>curiosity</b> — recent Surprise-Rate</li>
 * </ul>
 *
 * <p>Bewusst <em>kein</em> Sentiment-LLM. Deterministisch, leicht, pro Tick fähig.
 *
 * <p><b>Persistenz:</b> Speichert nach jedem Update die aktuellen Werte + Timestamp
 * als JSON-Zeile in {@code mood-history.jsonl}. Erlaubt Continuity-Soak-Test
 * über Tage hinweg.
 */
public class MoodSignal {

    private static final Logger LOG = Logger.getLogger(MoodSignal.class.getName());
    private static final double ALPHA = 0.1;
    private static final String DEFAULT_PATH =
            System.getProperty("metis.mood.path",
                    "/home/prometheus/metis/mood-history.jsonl");

    private double energy = 0.5;
    private double satisfaction = 0.5;
    private double confidence = 0.5;
    private double curiosity = 0.5;
    private Instant lastSaved = Instant.EPOCH;
    private int updateCount = 0;

    private final Path persistPath;

    public MoodSignal() {
        this(Path.of(DEFAULT_PATH));
    }

    public MoodSignal(Path persistPath) {
        this.persistPath = persistPath;
        load();
    }

    /**
     * Load the last saved mood from the JSONL file.
     * Reads only the last non-empty line (most recent state).
     */
    private synchronized void load() {
        if (!Files.exists(persistPath)) {
            LOG.info("MoodSignal: cold start, no history file");
            return;
        }
        try {
            var lines = Files.readAllLines(persistPath);
            for (int i = lines.size() - 1; i >= 0; i--) {
                String line = lines.get(i).strip();
                if (line.isBlank()) continue;
                // Parse: {"ts":"...","energy":0.5,...}
                energy = extractDouble(line, "energy", 0.5);
                satisfaction = extractDouble(line, "satisfaction", 0.5);
                confidence = extractDouble(line, "confidence", 0.5);
                curiosity = extractDouble(line, "curiosity", 0.5);
                LOG.info("MoodSignal: restored from " + persistPath.getFileName()
                        + " — energy=" + round(energy) + " satisfaction=" + round(satisfaction)
                        + " confidence=" + round(confidence) + " curiosity=" + round(curiosity));
                return;
            }
        } catch (Exception e) {
            LOG.warning("MoodSignal: load failed " + e.getMessage());
        }
    }

    /**
     * Persist current state to JSONL (one line per save).
     * Saves after N updates to avoid excessive I/O.
     */
    private synchronized void save() {
        try {
            if (!Files.exists(persistPath.getParent())) {
                Files.createDirectories(persistPath.getParent());
            }
            String line = String.format(
                    "{\"ts\":\"%s\",\"energy\":%.3f,\"satisfaction\":%.3f,\"confidence\":%.3f,\"curiosity\":%.3f}",
                    Instant.now(), energy, satisfaction, confidence, curiosity);
            Files.writeString(persistPath, line + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            lastSaved = Instant.now();
        } catch (IOException e) {
            LOG.fine(() -> "MoodSignal: save failed " + e.getMessage());
        }
    }

    public synchronized void update(FitnessSignal fitness,
                                    double goalSuccessRate,
                                    double evalGateOk,
                                    double recentSurpriseRate,
                                    double recentEnergy) {
        if (fitness != null) {
            confidence = ema(confidence, clamp(fitness.currentFitness()));
        }
        energy = ema(energy, clamp(recentEnergy));
        curiosity = ema(curiosity, clamp(recentSurpriseRate));
        double sat = clamp(0.7 * goalSuccessRate + 0.3 * evalGateOk);
        satisfaction = ema(satisfaction, sat);

        updateCount++;
        // Save every ~100 updates (~every 10 min at current tick rate)
        // or if >5 min since last save
        if (updateCount % 100 == 0
                || Instant.now().isAfter(lastSaved.plusSeconds(300))) {
            save();
        }
    }

    /** Force-save current mood (called before shutdown or on demand). */
    public synchronized void persistNow() {
        save();
    }

    public synchronized Map<String, Double> snapshot() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("energy", round(energy));
        m.put("satisfaction", round(satisfaction));
        m.put("confidence", round(confidence));
        m.put("curiosity", round(curiosity));
        return m;
    }

    public synchronized String label() {
        StringBuilder sb = new StringBuilder();
        sb.append(satisfaction > 0.7 ? "zufrieden" : satisfaction < 0.3 ? "frustriert" : "neutral");
        sb.append(", ");
        sb.append(curiosity > 0.7 ? "sehr neugierig" : curiosity < 0.3 ? "wenig neugierig" : "neugierig");
        if (confidence < 0.3) sb.append(", verunsichert");
        if (energy < 0.3) sb.append(", müde");
        return sb.toString();
    }

    /** Line count in mood-history.jsonl (for soak test verification). */
    public synchronized int historyLineCount() {
        if (!Files.exists(persistPath)) return 0;
        try {
            return (int) Files.lines(persistPath).filter(l -> !l.isBlank()).count();
        } catch (IOException e) {
            return 0;
        }
    }

    public synchronized int updateCount() { return updateCount; }

    private static double ema(double prev, double next) { return prev + ALPHA * (next - prev); }
    private static double clamp(double v) { return Math.max(0.0, Math.min(1.0, v)); }
    private static double round(double v) { return Math.round(v * 1000.0) / 1000.0; }

    /** Extract a double from a simple JSON string (no Jackson dependency). */
    private static double extractDouble(String json, String key, double defaultValue) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start < 0) return defaultValue;
        start += search.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        StringBuilder num = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (Character.isDigit(c) || c == '.' || c == '-') num.append(c);
            else break;
        }
        try {
            return num.length() > 0 ? Double.parseDouble(num.toString()) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
