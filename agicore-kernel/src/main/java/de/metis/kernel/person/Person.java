package de.metis.kernel.person;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Phase 11 — strukturiertes Personenmodell.
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
        List<SentimentSample> sentimentHistory
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
    }

    public record SentimentSample(String label, double score, Instant at) {}

    public Person withInteraction() {
        return new Person(id, name, roles, trustLevel, preferences, bannedTopics,
                knownFacts, firstSeenAt, Instant.now(), interactionCount + 1,
                sentimentHistory);
    }

    public Person withTrust(TrustLevel t) {
        return new Person(id, name, roles, t, preferences, bannedTopics, knownFacts,
                firstSeenAt, lastSeenAt, interactionCount, sentimentHistory);
    }

    public Person withFact(String fact) {
        if (fact == null || fact.isBlank()) return this;
        java.util.List<String> next = new java.util.ArrayList<>(knownFacts);
        if (!next.contains(fact)) next.add(fact);
        return new Person(id, name, roles, trustLevel, preferences, bannedTopics,
                java.util.List.copyOf(next), firstSeenAt, lastSeenAt,
                interactionCount, sentimentHistory);
    }

    public Person withSentiment(SentimentSample s) {
        if (s == null) return this;
        java.util.List<SentimentSample> next = new java.util.ArrayList<>(sentimentHistory);
        next.add(s);
        // keep last 20
        if (next.size() > 20) next = next.subList(next.size() - 20, next.size());
        return new Person(id, name, roles, trustLevel, preferences, bannedTopics,
                knownFacts, firstSeenAt, lastSeenAt, interactionCount,
                java.util.List.copyOf(next));
    }
}
