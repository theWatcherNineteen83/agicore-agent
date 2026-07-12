package de.metis.kernel.person;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Phase 11.5 — Policy-Engine für proaktive Kommunikation.
 *
 * <p>Entscheidet pro Person und Kategorie, ob Metis von sich aus
 * eine Nachricht senden darf. Kombiniert:
 * <ul>
 *   <li>{@link InitiativeLevel} (via TrustLevel-Mapping)</li>
 *   <li>InitiativeBudget pro Person/Tag</li>
 *   <li>QuietHours (standardmäßig 22:00–08:00 Europe/Berlin)</li>
 *   <li>Kritische Kategorien, die QuietHours überschreiben dürfen</li>
 * </ul>
 *
 * <p>Thread-safe. Designed für Integration in
 * {@code ProactiveNotificationService} und {@code TelegramBotService}.
 */
public class InitiativePolicy {

    private static final Logger LOG = Logger.getLogger(InitiativePolicy.class.getName());

    // ── Budget-Konfiguration ──────────────────────────────────
    private static final int DEFAULT_DAILY_BUDGET = 12; // max proaktive Nachrichten/Tag

    // ── QuietHours ─────────────────────────────────────────────
    private final LocalTime quietStart;
    private final LocalTime quietEnd;
    private final ZoneId timezone;

    // ── Kritische Kategorien (dürfen QuietHours brechen) ──────
    // Reihenfolge: security/smoke/gas > server-down > extreme-weather
    private static final int CRITICAL_THRESHOLD_HIGH = 95;   // security, smoke, gas
    private static final int CRITICAL_THRESHOLD_MED = 90;    // server-down, extreme-storm
    private static final int CRITICAL_THRESHOLD_LOW = 85;    // extreme-weather

    // ── State ──────────────────────────────────────────────────
    private final Map<String, DailyBudget> budgets = new ConcurrentHashMap<>();
    private final Map<String, InitiativeLevel> overrides = new ConcurrentHashMap<>();

    /** Person-id → anpassbare Zuordnung (optional, sonst TrustLevel-Default). */
    private final Map<String, InitiativeLevel> customLevels = new ConcurrentHashMap<>();

    public InitiativePolicy() {
        this(LocalTime.of(22, 0), LocalTime.of(8, 0), ZoneId.of("Europe/Berlin"));
    }

    public InitiativePolicy(LocalTime quietStart, LocalTime quietEnd, ZoneId timezone) {
        this.quietStart = quietStart;
        this.quietEnd = quietEnd;
        this.timezone = timezone;
    }

    // ── Public API ─────────────────────────────────────────────

    /**
     * Darf Metis diese Person proaktiv kontaktieren?
     *
     * @param person   die Zielperson (mit TrustLevel)
     * @param category Nachrichtenkategorie (alert, suggestion, weather, chat, …)
     * @param priority Ziel-Priorität (0–100, für QuietHours-Override)
     * @return true wenn die Nachricht gesendet werden darf
     */
    public boolean mayInitiate(Person person, String category, int priority) {
        if (person == null) return false;

        InitiativeLevel level = effectiveLevel(person);

        // 1. Kategorie-Prüfung
        if (!level.allowsCategory(category)) {
            LOG.fine(() -> "Initiative blockiert (Level " + level
                    + " erlaubt keine Kategorie '" + category + "') für " + person.name());
            return false;
        }

        // 2. QuietHours-Prüfung (mit Override für kritische Events)
        if (isQuietHours() && !isCriticalOverride(category, priority)) {
            LOG.fine(() -> "Initiative blockiert (QuietHours) für " + person.name()
                    + " Kategorie=" + category + " priority=" + priority);
            return false;
        }

        // 3. Budget-Prüfung
        DailyBudget budget = budgets.computeIfAbsent(person.id(),
                k -> new DailyBudget(DEFAULT_DAILY_BUDGET));
        if (budget.isExhausted()) {
            LOG.fine(() -> "Initiative blockiert (Budget erschöpft) für " + person.name());
            return false;
        }

        return true;
    }

    /**
     * Overload für Notifications ohne explizite Priority
     * (Default: keine QuietHours-Überschreibung).
     */
    public boolean mayInitiate(Person person, String category) {
        return mayInitiate(person, category, 0);
    }

    /**
     * Registriert, dass Metis eine proaktive Nachricht gesendet hat.
     * Dekrementiert das Tagesbudget.
     */
    public void recordOutreach(Person person) {
        if (person == null) return;
        DailyBudget budget = budgets.computeIfAbsent(person.id(),
                k -> new DailyBudget(DEFAULT_DAILY_BUDGET));
        budget.consume();
        LOG.fine(() -> "Outreach recorded: " + person.name()
                + " (remaining=" + budget.remaining() + "/" + budget.dailyLimit + ")");
    }

    /**
     * Setzt einen benutzerdefinierten InitiativeLevel für eine Person.
     * Überschreibt das TrustLevel-Mapping.
     */
    public void setCustomLevel(String personId, InitiativeLevel level) {
        if (level == null) {
            customLevels.remove(personId);
        } else {
            customLevels.put(personId, level);
        }
        LOG.info("InitiativeLevel override: " + personId + " → " + level);
    }

    /**
     * Setzt temporären Override (z. B. "heute bitte still").
     */
    public void setTemporaryOverride(String personId, InitiativeLevel level) {
        if (level == null) {
            overrides.remove(personId);
        } else {
            overrides.put(personId, level);
        }
        LOG.info("Temporary InitiativeLevel override: " + personId + " → " + level);
    }

    public void clearTemporaryOverride(String personId) {
        overrides.remove(personId);
    }

    // ── QuietHours ─────────────────────────────────────────────

    public boolean isQuietHours() {
        LocalTime now = LocalTime.now(timezone);
        if (quietStart.isBefore(quietEnd)) {
            // z. B. 08:00–22:00 → quiet ist außerhalb
            return now.isBefore(quietStart) || now.isAfter(quietEnd);
        } else {
            // Über Mitternacht: z. B. 22:00–08:00
            return !now.isBefore(quietStart) || now.isBefore(quietEnd);
            // quiet wenn: jetzt >= 22:00 ODER jetzt < 08:00
        }
    }

    private boolean isCriticalOverride(String category, int priority) {
        if (priority >= CRITICAL_THRESHOLD_HIGH) return true;
        if (priority >= CRITICAL_THRESHOLD_MED
                && ("security".equals(category) || "alert".equals(category))) return true;
        if (priority >= CRITICAL_THRESHOLD_LOW
                && "weather".equals(category)) return true;
        return false;
    }

    // ── Effective Level ────────────────────────────────────────

    private InitiativeLevel effectiveLevel(Person person) {
        // Temporäre Overrides haben höchste Priorität
        InitiativeLevel temp = overrides.get(person.id());
        if (temp != null) return temp;

        // Benutzerdefinierte Levels
        InitiativeLevel custom = customLevels.get(person.id());
        if (custom != null) return custom;

        // Default: TrustLevel-Mapping
        return InitiativeLevel.fromTrustLevel(person.trustLevel());
    }

    // ── Metriken ───────────────────────────────────────────────

    public Map<String, BudgetSnapshot> budgetSnapshots() {
        Map<String, BudgetSnapshot> snap = new ConcurrentHashMap<>();
        budgets.forEach((id, b) -> snap.put(id, new BudgetSnapshot(b.dailyLimit, b.remaining(), b.lastReset)));
        return Map.copyOf(snap);
    }

    public record BudgetSnapshot(int dailyLimit, int remaining, Instant lastReset) {}

    public String quietHoursDescription() {
        return quietStart + "–" + quietEnd + " " + timezone;
    }

    // ── Budget-Klasse ──────────────────────────────────────────

    private static class DailyBudget {
        final int dailyLimit;
        int remaining;
        Instant lastReset;

        DailyBudget(int limit) {
            this.dailyLimit = limit;
            this.remaining = limit;
            this.lastReset = Instant.now();
        }

        synchronized boolean isExhausted() {
            resetIfNewDay();
            return remaining <= 0;
        }

        synchronized void consume() {
            resetIfNewDay();
            if (remaining > 0) remaining--;
        }

        synchronized int remaining() {
            resetIfNewDay();
            return remaining;
        }

        private void resetIfNewDay() {
            Instant now = Instant.now();
            if (Duration.between(lastReset, now).toDays() >= 1) {
                remaining = dailyLimit;
                lastReset = now;
            }
        }
    }
}
