package de.metis.kernel.person;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Phase 11/13c — strukturiertes Personenmodell mit Lusseyran-Stimmprofil.
 *
 * <p>Eine Person ist mehr als eine {@code chat_id}. Felder:
 * <ul>
 *   <li><b>id</b> — Telegram-Id oder anderer kanonischer Bezeichner</li>
 *   <li><b>name</b> — wie Metis die Person anredet</li>
 *   <li><b>roles</b> — z. B. "owner", "guest", "stranger"</li>
 *   <li><b>trustLevel</b> — TrustLevel enum (0..4), beeinflusst Approval-Gate</li>
 *   <li><b>preferences</b> — Map (z. B. "language" -> "Deutsch", "style" -> "direct")</li>
 *   <li><b>bannedTopics</b> — was Metis bei dieser Person nicht ansprechen darf</li>
 *   <li><b>knownFacts</b> — vom Belief-System unabhängige Fakten über die Person</li>
 *   <li><b>lastSeenAt</b>, <b>firstSeenAt</b>, <b>interactionCount</b></li>
 *   <li><b>sentimentHistory</b> — die letzten N (mood, timestamp) Tupel</li>
 *   <li><b>speakerProfile</b> — Lusseyran-Stimmprofil (Phase 13c), nullable</li>
 *   <li><b>voiceSentimentHistory</b> — Stimmungs-Historie aus Stimmanalyse (Phase 13c)</li>
 * </ul>
 *
 * <p>Immutable Record. Updates per {@code withInteraction()},
 * {@code withTrust()}, {@code withFact()} etc.
 */
public record Person(
        String id,
        String name,
        List<String> roles,
        TrustLevel trustLevel,
        Map<String, String> preferences,
        List<String> bannedTopics,
        List<String> knownFacts,
        Instant firstSeenAt,
        Instant lastSeenAt,
        long interactionCount,
        List<SentimentSample> sentimentHistory,
        SpeakerProfile speakerProfile,
        List<VoiceSentimentSample> voiceSentimentHistory
) {
    public Person {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id required");
        if (name == null || name.isBlank()) name = id;
        if (roles == null) roles = List.of("user");
        if (trustLevel == null) trustLevel = TrustLevel.GUEST;
        if (preferences == null) preferences = Map.of();
        if (bannedTopics == null) bannedTopics = List.of();
        if (knownFacts == null) knownFacts = List.of();
        if (firstSeenAt == null) firstSeenAt = Instant.now();
        if (lastSeenAt == null) lastSeenAt = firstSeenAt;
        if (interactionCount < 0) interactionCount = 0;
        if (sentimentHistory == null) sentimentHistory = List.of();
        if (voiceSentimentHistory == null) voiceSentimentHistory = List.of();
    }

    /** Shortcut for backward compat (no speakerProfile). */
    public Person(String id, String name, List<String> roles, TrustLevel trustLevel,
                  Map<String, String> preferences, List<String> bannedTopics,
                  List<String> knownFacts, Instant firstSeenAt, Instant lastSeenAt,
                  long interactionCount, List<SentimentSample> sentimentHistory) {
        this(id, name, roles, trustLevel, preferences, bannedTopics, knownFacts,
                firstSeenAt, lastSeenAt, interactionCount, sentimentHistory,
                null, List.of());
    }

    public record SentimentSample(String label, double score, Instant at) {}

    /**
     * Phase 13c — Lusseyran-Sprecherprofil aus Stimmanalyse.
     * <p>
     * Enthält die LLM-interpretierte Charakterisierung der Stimme einer Person
     * nach den Prinzipien von Jacques Lusseyran.
     */
    public record SpeakerProfile(
            String stimmcharakter,       // z.B. "warm-tief-lebendig"
            String emotionaleVerfassung,  // 1 Satz zur emotionalen Tönung
            String aufrichtigkeit,        // "hoch"|"mittel"|"niedrig"|"unklar"
            String aufrichtigkeitBegruendung,
            String archetyp,             // z.B. "Der ruhige Weise"
            String vignette,             // poetische Lusseyran-Beschreibung
            Instant analyzedAt           // wann wurde analysiert?
    ) {
        /** Numerischer Sincerity-Wert für TrustLevel-Adjustment. */
        public double sincerityScore() {
            return switch (aufrichtigkeit) {
                case "hoch" -> 0.8;
                case "mittel" -> 0.5;
                case "niedrig" -> 0.2;
                default -> 0.5;
            };
        }

        /** Emotionaler Score aus der Verfassung. */
        public double emotionalValence() {
            String v = emotionaleVerfassung != null ? emotionaleVerfassung.toLowerCase() : "";
            if (v.contains("positiv") || v.contains("freude") || v.contains("gelassen")
                    || v.contains("ruhig") || v.contains("warm")) return 0.7;
            if (v.contains("negativ") || v.contains("angespannt") || v.contains("traurig")
                    || v.contains("wütend") || v.contains("nervös")) return 0.3;
            return 0.5;
        }
    }

    /**
     * Phase 13c — Sentiment aus Stimmanalyse (parallel zu Text-Sentiment).
     */
    public record VoiceSentimentSample(String label, double score, String source, Instant at) {}

    // ── Builder-style methods ────────────────────────────────────

    private Person rebuild(String id, String name, List<String> roles, TrustLevel trustLevel,
                        Map<String, String> preferences, List<String> bannedTopics,
                        List<String> knownFacts, Instant firstSeenAt, Instant lastSeenAt,
                        long interactionCount, List<SentimentSample> sentimentHistory,
                        SpeakerProfile speakerProfile, List<VoiceSentimentSample> voiceSentimentHistory) {
        return new Person(id, name, roles, trustLevel, preferences, bannedTopics, knownFacts,
                firstSeenAt, lastSeenAt, interactionCount, sentimentHistory,
                speakerProfile, voiceSentimentHistory);
    }

    public Person withInteraction() {
        return rebuild(id, name, roles, trustLevel, preferences, bannedTopics,
                knownFacts, firstSeenAt, Instant.now(), interactionCount + 1,
                sentimentHistory, speakerProfile, voiceSentimentHistory);
    }

    public Person withTrust(TrustLevel t) {
        return rebuild(id, name, roles, t, preferences, bannedTopics, knownFacts,
                firstSeenAt, lastSeenAt, interactionCount, sentimentHistory,
                speakerProfile, voiceSentimentHistory);
    }

    public Person withFact(String fact) {
        if (fact == null || fact.isBlank()) return this;
        java.util.List<String> next = new java.util.ArrayList<>(knownFacts);
        if (!next.contains(fact)) next.add(fact);
        return rebuild(id, name, roles, trustLevel, preferences, bannedTopics,
                java.util.List.copyOf(next), firstSeenAt, lastSeenAt,
                interactionCount, sentimentHistory, speakerProfile, voiceSentimentHistory);
    }

    public Person withSentiment(SentimentSample s) {
        if (s == null) return this;
        java.util.List<SentimentSample> next = new java.util.ArrayList<>(sentimentHistory);
        next.add(s);
        if (next.size() > 20) next = next.subList(next.size() - 20, next.size());
        return rebuild(id, name, roles, trustLevel, preferences, bannedTopics,
                knownFacts, firstSeenAt, lastSeenAt, interactionCount,
                java.util.List.copyOf(next), speakerProfile, voiceSentimentHistory);
    }

    /** Phase 13c — Sprecherprofil setzen/aktualisieren. */
    public Person withSpeakerProfile(SpeakerProfile sp) {
        if (sp == null) return this;
        Person p = this;
        if (sp.stimmcharakter() != null && !sp.stimmcharakter().isBlank()) {
            p = p.withFact("Stimmcharakter: " + sp.stimmcharakter());
        }
        if (sp.archetyp() != null && !sp.archetyp().isBlank()) {
            p = p.withFact("Stimm-Archetyp: " + sp.archetyp());
        }
        return rebuild(id, name, roles, trustLevel, preferences, bannedTopics,
                p.knownFacts(), firstSeenAt, lastSeenAt, interactionCount,
                sentimentHistory, sp, voiceSentimentHistory);
    }

    /** Phase 13c — Voice-Sentiment hinzufügen. */
    public Person withVoiceSentiment(VoiceSentimentSample vs) {
        if (vs == null) return this;
        java.util.List<VoiceSentimentSample> next = new java.util.ArrayList<>(voiceSentimentHistory);
        next.add(vs);
        if (next.size() > 10) next = next.subList(next.size() - 10, next.size());
        return rebuild(id, name, roles, trustLevel, preferences, bannedTopics,
                knownFacts, firstSeenAt, lastSeenAt, interactionCount,
                sentimentHistory, speakerProfile, java.util.List.copyOf(next));
    }
}
