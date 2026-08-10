package de.metis.modules.action;

import de.metis.kernel.action.Action;
import de.metis.kernel.action.ActionResult;
import de.metis.kernel.person.Person;
import de.metis.kernel.person.PersonStore;
import de.metis.kernel.person.VoiceSincerityAdjuster;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.logging.Logger;

/**
 * Phase 13c — LusseyranPersonIntegration.
 * <p>
 * Bridge zwischen LusseyranEvaluator (13b) und PersonModel (11).
 * Nimmt das kombinierte JSON aus LusseyranEvaluatorAction (Features + Lusseyran-Profil)
 * und aktualisiert das PersonModel:
 * <ol>
 *   <li>Extrahiert SpeakerProfile aus der Lusseyran-Evaluation</li>
 *   <li>Setzt speakerProfile + VoiceSentiment in der Person</li>
 *   <li>Passt TrustLevel via VoiceSincerityAdjuster an</li>
 *   <li>Persistiert die aktualisierte Person via PersonStore</li>
 * </ol>
 * <p>
 * Approval: NOTIFY — read-mostly mit PersonStore-Update.
 */
public class LusseyranPersonIntegration implements Action {

    private static final Logger LOG = Logger.getLogger(LusseyranPersonIntegration.class.getName());
    public static final String NAME = "lusseyran_person_integration";

    private final String personId;       // Telegram-ID o.ä.
    private final String lusseyranJson;  // kombiniertes JSON aus LusseyranEvaluatorAction
    private final PersonStore personStore;
    private final VoiceSincerityAdjuster adjuster;

    public LusseyranPersonIntegration(String personId, String lusseyranJson,
                                       PersonStore personStore) {
        this.personId = personId;
        this.lusseyranJson = lusseyranJson;
        this.personStore = personStore;
        this.adjuster = new VoiceSincerityAdjuster();
    }

    @Override
    public String name() { return NAME; }

    @Override
    public String category() { return "analyze"; }

    @Override
    public ApprovalLevel approvalLevel() { return ApprovalLevel.NOTIFY; }

    @Override
    public ActionResult execute() {
        Instant start = Instant.now();

        if (personId == null || personId.isBlank()) {
            return ActionResult.fail(NAME, "personId required", start);
        }
        if (lusseyranJson == null || lusseyranJson.isBlank()) {
            return ActionResult.fail(NAME, "lusseyranJson required", start);
        }

        try {
            // 1. Get or create person
            var optPerson = personStore.get(personId);
            Person person = optPerson.orElseGet(() -> {
                Person p = new Person(personId, personId,
                        java.util.List.of("user"),
                        de.metis.kernel.person.TrustLevel.GUEST,
                        java.util.Map.of(), java.util.List.of(), java.util.List.of(),
                        Instant.now(), Instant.now(), 0, java.util.List.of());
                return personStore.upsert(p);
            });
            String personName = person.name();
            LOG.fine("LusseyranPersonIntegration: person=" + personName
                    + " trust=" + person.trustLevel());

            // 2. Parse Lusseyran evaluation JSON
            Person.SpeakerProfile profile = parseSpeakerProfile(lusseyranJson);
            if (profile == null) {
                return ActionResult.fail(NAME,
                        "Failed to parse speaker profile from JSON", start);
            }

            // 3. Set speaker profile on person
            person = person.withSpeakerProfile(profile);
            LOG.info("LusseyranPersonIntegration: " + personName
                    + " speakerProfile=" + profile.stimmcharakter()
                    + " aufrichtigkeit=" + profile.aufrichtigkeit());

            // 4. TrustLevel adjustment via voice sincerity
            person = adjuster.adjust(person, profile);

            // 5. Voice sentiment fusion
            Person.VoiceSentimentSample vs = adjuster.toVoiceSentiment(profile);
            if (vs != null) {
                person = person.withVoiceSentiment(vs);
            }

            // 6. Persist
            personStore.upsert(person);

            String summary = String.format(
                    "{\"person\":\"%s\",\"stimmcharakter\":\"%s\",\"aufrichtigkeit\":\"%s\",\"trust\":\"%s\",\"archetyp\":\"%s\"}",
                    person.name(),
                    profile.stimmcharakter(),
                    profile.aufrichtigkeit(),
                    person.trustLevel().name(),
                    profile.archetyp());

            return ActionResult.ok(NAME, summary, start);

        } catch (Exception e) {
            return ActionResult.fail(NAME, "Integration failed: " + e.getMessage(), start);
        }
    }

    /**
     * Parse SpeakerProfile from the combined 13a+13b JSON output.
     * Extracts the "lusseyran_evaluation" sub-object.
     */
    static Person.SpeakerProfile parseSpeakerProfile(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            // Find lusseyran_evaluation object
            String search = "\"lusseyran_evaluation\":";
            int idx = json.indexOf(search);
            if (idx < 0) {
                // Try as direct profile JSON (from LusseyranEvaluator only)
                return parseDirectProfile(json);
            }
            idx += search.length();
            // Find the JSON object (braces)
            int braceStart = json.indexOf('{', idx);
            if (braceStart < 0) return null;
            int depth = 0;
            int braceEnd = braceStart;
            for (int i = braceStart; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') { depth--; if (depth == 0) { braceEnd = i; break; } }
            }
            String profileJson = json.substring(braceStart, braceEnd + 1);
            return parseFromJson(profileJson);
        } catch (Exception e) {
            LOG.fine("parseSpeakerProfile failed: " + e.getMessage());
            return null;
        }
    }

    private static Person.SpeakerProfile parseDirectProfile(String json) {
        int b1 = json.indexOf('{');
        int b2 = json.lastIndexOf('}');
        if (b1 < 0 || b2 <= b1) return null;
        return parseFromJson(json.substring(b1, b2 + 1));
    }

    private static Person.SpeakerProfile parseFromJson(String json) {
        return new Person.SpeakerProfile(
                extractJsonStr(json, "stimmcharakter"),
                extractJsonStr(json, "emotionale_verfassung"),
                extractJsonStr(json, "aufrichtigkeit"),
                extractJsonStr(json, "aufrichtigkeit_begruendung"),
                extractJsonStr(json, "archetyp"),
                extractJsonStr(json, "vignette"),
                Instant.now()
        );
    }

    private static String extractJsonStr(String json, String key) {
        // Try both underscore and camelCase variants
        String[] keys = {key, key.replace("_", "")};
        for (String k : keys) {
            String search = "\"" + k + "\":\"";
            int start = json.indexOf(search);
            if (start < 0) {
                search = "\"" + k + "\": \"";
                start = json.indexOf(search);
            }
            if (start < 0) continue;
            start += search.length();
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '\\' && i + 1 < json.length()) {
                    char next = json.charAt(i + 1);
                    switch (next) {
                        case 'n' -> { sb.append('\n'); i++; }
                        case '"' -> { sb.append('"'); i++; }
                        case '\\' -> { sb.append('\\'); i++; }
                        default -> sb.append(c);
                    }
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                }
            }
            if (!sb.isEmpty()) return sb.toString();
        }
        return "";
    }

    @Override
    public String toString() {
        return "LusseyranPersonIntegration[" + personId + "]";
    }
}
