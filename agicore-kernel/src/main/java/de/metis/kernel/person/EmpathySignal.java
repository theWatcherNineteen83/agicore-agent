package de.metis.kernel.person;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Phase 11.4 — sehr leichte Sentiment-Heuristik (kein LLM).
 *
 * <p>Bewusst regelbasiert: ein deutsches/englisches Schlüsselwort-Set für
 * positive/negative Stimmung, plus Ausrufezeichen-/Großbuchstaben-Heuristik.
 * Liefert {@code SentimentSample(label, score in [-1, 1], at=now)}.
 *
 * <p>Aufrufer (HTTP/Telegram) erweitert die Person via {@code withSentiment(sample)}.
 * Aggregierte Stimmung (Mittelwert der letzten 5 Samples in den letzten 24h)
 * fließt in den SystemPromptBuilder als "Stimmung von <Person>".
 */
public class EmpathySignal {

    private static final Set<String> POS_TOKENS = Set.of(
            "danke", "super", "perfekt", "gut", "toll", "freue", "großartig", "hervorragend",
            "geil", "endlich", "lieb", "klasse",
            "thanks", "great", "perfect", "good", "awesome", "love"
    );
    private static final Set<String> NEG_TOKENS = Set.of(
            "ärgerlich", "scheiße", "doof", "kacke", "nervig", "wütend", "müde", "gestresst",
            "blöd", "kaputt", "verdammt", "frustriert", "spinnst",
            "annoying", "broken", "stupid", "stressed", "angry", "tired"
    );

    public record Sample(String label, double score) {}

    public Person.SentimentSample analyze(String text) {
        if (text == null || text.isBlank()) {
            return new Person.SentimentSample("neutral", 0.0, Instant.now());
        }
        String t = text.toLowerCase();
        int pos = 0, neg = 0;
        for (String tok : POS_TOKENS) if (t.contains(tok)) pos++;
        for (String tok : NEG_TOKENS) if (t.contains(tok)) neg++;
        int exclamations = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '!') exclamations++;
        int capsWords = 0;
        for (String w : text.split("\\s+")) {
            if (w.length() >= 3 && w.equals(w.toUpperCase())
                    && w.chars().anyMatch(Character::isLetter)) capsWords++;
        }
        double signal = pos - neg;
        if (signal > 0 && exclamations > 0) signal += 0.3;
        if (signal < 0 && (exclamations > 0 || capsWords > 1)) signal -= 0.3;
        double score = Math.max(-1.0, Math.min(1.0, signal / 3.0));
        String label = score > 0.2 ? "positiv" : score < -0.2 ? "negativ" : "neutral";
        return new Person.SentimentSample(label, round(score), Instant.now());
    }

    /**
     * Aggregate the last N samples within the last 24h.
     * Returns a label that is safe to drop into a system prompt.
     */
    public String aggregateLabel(List<Person.SentimentSample> history) {
        if (history == null || history.isEmpty()) return "neutral";
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));
        List<Person.SentimentSample> recent = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && recent.size() < 5; i--) {
            Person.SentimentSample s = history.get(i);
            if (s.at().isAfter(cutoff)) recent.add(s);
        }
        if (recent.isEmpty()) return "neutral";
        double avg = recent.stream().mapToDouble(Person.SentimentSample::score).average().orElse(0.0);
        if (avg > 0.4) return "gelassen positiv";
        if (avg > 0.1) return "leicht positiv";
        if (avg < -0.4) return "deutlich angespannt";
        if (avg < -0.1) return "leicht angespannt";
        return "neutral";
    }

    private static double round(double v) { return Math.round(v * 1000.0) / 1000.0; }
}
