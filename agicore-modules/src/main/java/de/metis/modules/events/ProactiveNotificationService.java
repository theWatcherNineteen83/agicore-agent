package de.metis.modules.events;

import de.metis.modules.telegram.TelegramBotService;
import de.metis.kernel.goal.Goal;
import de.metis.kernel.person.InitiativePolicy;
import de.metis.kernel.person.Person;
import de.metis.kernel.person.PersonStore;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Watches Metis's goal queue for high-priority events and pushes
 * notifications to the user via Telegram.
 */
public class ProactiveNotificationService {

    private static final Logger LOG = Logger.getLogger(ProactiveNotificationService.class.getName());

    private static final Duration CHECK_INTERVAL = Duration.ofSeconds(10);
    private static final Duration MIN_NOTIFY_INTERVAL = Duration.ofSeconds(15);
    private static final Duration CATEGORY_COOLDOWN = Duration.ofMinutes(5);
    private static final int HIGH_PRIORITY = 80;
    private static final int MEDIUM_PRIORITY = 65;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread watchdogThread;

    private TelegramBotService telegramBot;
    private long ownerChatId;
    private InitiativePolicy initiativePolicy;
    private PersonStore personStore;

    private Instant lastNotification = Instant.EPOCH;
    private final Map<String, Instant> categoryCooldowns = new ConcurrentHashMap<>();
    private final Set<String> seenGoalDescriptions = new HashSet<>();
    private final Set<String> notifiedWeatherEvents = new HashSet<>();

    public ProactiveNotificationService(TelegramBotService telegramBot, long ownerChatId) {
        this.telegramBot = telegramBot;
        this.ownerChatId = ownerChatId;
    }

    /** Setzt InitiativePolicy für Phase 11.5 Gating. */
    public void setInitiativePolicy(InitiativePolicy policy) {
        this.initiativePolicy = policy;
    }

    /** Setzt PersonStore für Person-Lookup beim Initiative-Gate. */
    public void setPersonStore(PersonStore store) {
        this.personStore = store;
    }

    public void start() {
        if (running.getAndSet(true)) return;

        watchdogThread = Thread.ofVirtual().name("proactive-notify").start(() -> {
            LOG.info("Proactive notification service started — watching for high-priority events");
            while (running.get()) {
                try {
                    Thread.sleep(CHECK_INTERVAL.toMillis());
                    if (!running.get()) break;
                    // Polling is handled externally — this is a notification bridge only
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });

        LOG.info("Proactive notifications active → Telegram chat " + ownerChatId);
    }

    /**
     * Called whenever a new goal is added to Metis. Evaluates if it warrants
     * a notification.
     */
    public void onGoalAdded(Goal goal) {
        if (telegramBot == null || ownerChatId == 0) return;
        if (goal.priority() < MEDIUM_PRIORITY) return;

        String description = goal.description();
        String category = goal.category() != null ? goal.category() : "unknown";

        // Deduplicate
        if (!seenGoalDescriptions.add(description)) return;

        // ── Phase 11.5: InitiativePolicy-Gate ──
        if (initiativePolicy != null) {
            Person owner = personStore != null
                    ? personStore.get(String.valueOf(ownerChatId)).orElse(null)
                    : null;
            if (!initiativePolicy.mayInitiate(owner, mapCategoryToInitiativeCategory(category), goal.priority())) {
                return;
            }
        }

        // Rate limit
        if (Duration.between(lastNotification, Instant.now()).compareTo(MIN_NOTIFY_INTERVAL) < 0) {
            return;
        }

        // Category cooldown
        Instant lastInCategory = categoryCooldowns.get(category);
        if (lastInCategory != null && 
            Duration.between(lastInCategory, Instant.now()).compareTo(CATEGORY_COOLDOWN) < 0) {
            return;
        }

        // Build notification message
        String message = buildNotification(goal, category);
        if (message != null) {
            telegramBot.sendMessage(ownerChatId, message);
            lastNotification = Instant.now();
            categoryCooldowns.put(category, Instant.now());
            // Phase 11.5: Budget tracken
            if (initiativePolicy != null) {
                Person owner = personStore != null
                        ? personStore.get(String.valueOf(ownerChatId)).orElse(null)
                        : null;
                initiativePolicy.recordOutreach(owner);
            }
            LOG.info("Proactive notification sent: " + message);
        }
    }

    private String buildNotification(Goal goal, String category) {
        String desc = goal.description();

        // Weather events
        if (category.equals("weather") || category.equals("weather-trend")) {
            String weatherMsg = formatWeatherAlert(desc);
            if (weatherMsg != null && notifiedWeatherEvents.add(extractWeatherKey(desc))) {
                return weatherMsg;
            }
            return null;
        }

        // MQTT events (motion, door, alarm)
        if (category.equals("mqtt-event") && goal.priority() >= HIGH_PRIORITY) {
            return "🔔 " + formatMqttAlert(desc);
        }

        // HA events
        if (category.equals("ha-event") && goal.priority() >= HIGH_PRIORITY) {
            return "🏠 " + desc;
        }

        // Battery/Solar events (from MQTT)
        if (category.equals("mqtt-event") && desc.contains("battery")) {
            // Extract SOC percentage
            String soc = extractSoc(desc);
            if (soc != null && desc.contains("wechselte")) {
                return "🔋 Batterie-Update: " + soc;
            }
        }

        return null;
    }

    private String formatWeatherAlert(String desc) {
        if (desc.contains("REGEN")) return "🌧️ Regenwarnung: " + extractCondition(desc);
        if (desc.contains("STURM")) return "💨 Sturmwarnung: " + extractCondition(desc);
        if (desc.contains("HITZE")) return "🥵 Hitzewarnung: " + extractCondition(desc);
        if (desc.contains("FROST")) return "🥶 Frostwarnung: " + extractCondition(desc);
        if (desc.contains("Temperatur") && desc.contains("gestiegen")) return "🌡️ " + desc;
        if (desc.contains("EXTREMER UV")) return "☀️ UV-Warnung: hoher UV-Index";
        return null;
    }

    private String formatMqttAlert(String desc) {
        if (desc.contains("motion") && desc.contains("ON")) return "🚨 Bewegung erkannt: " + desc;
        if (desc.contains("door")) return "🚪 Tür-Event: " + desc;
        if (desc.contains("smoke") || desc.contains("gas")) return "⚠️ ALARM: " + desc;
        return "📡 " + desc;
    }

    private String extractCondition(String desc) {
        // Extract the key condition part after the colon
        int colon = desc.indexOf(": ");
        return colon > 0 ? desc.substring(colon + 2) : desc;
    }

    private String extractWeatherKey(String desc) {
        // Create a deduplication key for weather events
        int colon = desc.indexOf(": ");
        return colon > 0 ? desc.substring(0, Math.min(desc.length(), 40)) : desc.substring(0, Math.min(desc.length(), 40));
    }

    private String extractSoc(String desc) {
        int socIdx = desc.indexOf("SOC");
        if (socIdx > 0) {
            int start = desc.indexOf(":", socIdx);
            if (start > 0 && start + 6 < desc.length()) {
                return desc.substring(start + 1, Math.min(start + 20, desc.length())).trim();
            }
        }
        return null;
    }

    /** Mappt interne Goal-Kategorie auf InitiativePolicy-Kategorie. */
    private String mapCategoryToInitiativeCategory(String category) {
        if (category == null) return "other";
        return switch (category) {
            case "weather", "weather-trend" -> "weather";
            case "mqtt-event" -> "alert";
            case "ha-event" -> "alert";
            case "server", "server-down" -> "server";
            case "security", "smoke", "gas" -> "security";
            default -> "other";
        };
    }

    public void stop() {
        running.set(false);
        if (watchdogThread != null) watchdogThread.interrupt();
        LOG.info("Proactive notification service stopped");
    }
}
