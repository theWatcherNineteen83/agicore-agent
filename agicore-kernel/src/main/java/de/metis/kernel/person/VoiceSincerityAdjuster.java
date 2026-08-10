package de.metis.kernel.person;

import java.time.Instant;
import java.util.logging.Logger;

/**
 * Phase 13c — Voice Sincerity → TrustLevel Adjustment.
 * <p>
 * Passt den TrustLevel einer Person basierend auf der stimmlichen
 * Aufrichtigkeitseinschätzung aus der Lusseyran-Analyse an.
 * <p>
 * <b>Regeln (konservativ, kleine Nudges):</b>
 * <ul>
 *   <li>sincerityScore ≥ 0.8 (hoch) → +1 Rank (max TRUSTED, nie OWNER)</li>
 *   <li>sincerityScore ≤ 0.2 (niedrig) → -1 Rank (min STRANGER)</li>
 *   <li>OWNER wird NIE durch Voice-Analyse angepasst</li>
 *   <li>Änderungen werden geloggt</li>
 * </ul>
 * <p>
 * Sincerity wird aus dem LusseyranEvaluator-SpeakerProfile abgeleitet.
 */
public class VoiceSincerityAdjuster {

    private static final Logger LOG = Logger.getLogger(VoiceSincerityAdjuster.class.getName());

    /**
     * Adjust TrustLevel based on voice sincerity analysis.
     *
     * @param person     the person to evaluate
     * @param profile    the Lusseyran speaker profile (may be null)
     * @return adjusted Person (or same if no change)
     */
    public Person adjust(Person person, Person.SpeakerProfile profile) {
        if (person == null || profile == null) return person;
        if (person.trustLevel() == TrustLevel.OWNER) {
            LOG.fine(() -> "VoiceSincerityAdjuster: skipping OWNER " + person.name());
            return person;
        }

        double sincerityScore = profile.sincerityScore();
        TrustLevel oldLevel = person.trustLevel();
        TrustLevel newLevel = oldLevel;

        if (sincerityScore >= 0.8 && oldLevel.rank() < TrustLevel.TRUSTED.rank()) {
            // Promote max 1 rank
            newLevel = TrustLevel.values()[Math.min(oldLevel.rank() + 1, TrustLevel.TRUSTED.rank())];
        } else if (sincerityScore <= 0.2 && oldLevel.rank() > TrustLevel.STRANGER.rank()) {
            // Demote max 1 rank
            newLevel = TrustLevel.values()[Math.max(oldLevel.rank() - 1, TrustLevel.STRANGER.rank())];
        }

        if (newLevel != oldLevel) {
            LOG.info("VoiceSincerityAdjuster: " + person.name()
                    + " trust " + oldLevel + " → " + newLevel
                    + " (sincerity=" + String.format("%.2f", sincerityScore)
                    + " reason=" + profile.aufrichtigkeitBegruendung() + ")");
            return person.withTrust(newLevel)
                    .withFact("Stimm-Aufrichtigkeit: " + profile.aufrichtigkeit()
                            + " (" + profile.aufrichtigkeitBegruendung() + ")");
        }
        return person;
    }

    /**
     * Create a VoiceSentimentSample from the speaker profile.
     */
    public Person.VoiceSentimentSample toVoiceSentiment(Person.SpeakerProfile profile) {
        if (profile == null) return null;
        double valence = profile.emotionalValence();
        String label = valence > 0.6 ? "positiv" : valence < 0.4 ? "negativ" : "neutral";
        return new Person.VoiceSentimentSample(
                label,
                (valence - 0.5) * 2.0,  // scale to [-1, 1]
                "lusseyran-voice",
                Instant.now()
        );
    }
}
