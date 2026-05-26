package de.metis.modules.events;

import de.metis.modules.events.EventTrigger;
import de.metis.modules.Agent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Polls the weather.com PWS API and generates Metis goals when conditions change.
 * <p>
 * Monitors:
 * <ul>
 *   <li>Temperature trends (significant change)</li>
 *   <li>Rain probability (generates warning goal)</li>
 *   <li>Wind gusts (generates alert goal)</li>
 *   <li>UV index (generates advisory goal)</li>
 * </ul>
 */
public class WeatherPollingTrigger implements EventTrigger {

    private static final Logger LOG = Logger.getLogger(WeatherPollingTrigger.class.getName());

    private static final String PWS_URL = "https://api.weather.com/v2/pws/observations/current";
    private static final Duration POLL_INTERVAL = Duration.ofMinutes(15);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);

    private final String apiKey;
    private final String stationId;
    private final HttpClient http;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread pollingThread;

    // Previous values for trend detection
    private Double lastTempC;
    private Double lastHumidity;
    private Instant lastObservation;

    public WeatherPollingTrigger(String apiKey, String stationId) {
        this.apiKey = apiKey;
        this.stationId = stationId;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String name() { return "weather-polling"; }

    @Override
    public String description() {
        return "Polls weather.com PWS " + stationId + " every " 
                + POLL_INTERVAL.toMinutes() + " min for notable weather events";
    }

    @Override
    public void start(Agent agent) {
        if (running.getAndSet(true)) return;

        pollingThread = Thread.ofVirtual().name("weather-poll").start(() -> {
            // Initial observation
            pollAndEvaluate(agent);

            while (running.get()) {
                try {
                    Thread.sleep(POLL_INTERVAL.toMillis());
                    if (!running.get()) break;
                    pollAndEvaluate(agent);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });

        LOG.info("Weather polling started — station " + stationId 
                + " every " + POLL_INTERVAL.toMinutes() + "min");
    }

    @Override
    public void stop() {
        running.set(false);
        if (pollingThread != null) pollingThread.interrupt();
        LOG.info("Weather polling stopped");
    }

    /**
     * Poll the weather API and generate goals for significant changes.
     */
    private void pollAndEvaluate(Agent agent) {
        try {
            String url = PWS_URL + "?stationId=" + stationId 
                    + "&format=json&units=m&apiKey=" + apiKey;

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(HTTP_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                LOG.warning("Weather API returned " + resp.statusCode());
                return;
            }

            WeatherSnapshot snap = parseWeather(resp.body());
            if (snap == null) return;

            evaluateConditions(agent, snap);
            detectTrends(agent, snap);

            // Remember for next poll
            lastTempC = snap.tempC;
            lastHumidity = snap.humidity;
            lastObservation = Instant.now();

        } catch (Exception e) {
            LOG.warning("Weather polling error: " + e.getMessage());
        }
    }

    /**
     * Generate goals based on current conditions.
     */
    private void evaluateConditions(Agent agent, WeatherSnapshot w) {
        StringBuilder report = new StringBuilder();
        report.append(String.format("Wetter %s: %.1f°C, %d%% Feuchte, %.1f km/h Wind",
                stationId, w.tempC, (int) w.humidity, w.windKph));

        int priority = 50;
        double reward = 0.6;
        boolean noteworthy = false;

        // Rain detection
        if (w.precipRateMmh > 0) {
            report.append(String.format(", REGEN (%.1f mm/h)", w.precipRateMmh));
            priority = 80;
            reward = 0.85;
            noteworthy = true;
        }

        // High wind
        if (w.windKph > 50) {
            report.append(String.format(", STURM-BÖEN (%.1f km/h)", w.windGustKph));
            priority = 85;
            reward = 0.9;
            noteworthy = true;
        }

        // Temperature extremes
        if (w.tempC > 35) {
            report.append(", HITZE-WARNUNG (>35°C)");
            priority = 82;
            reward = 0.85;
            noteworthy = true;
        } else if (w.tempC < -5) {
            report.append(", FROST-WARNUNG (<-5°C)");
            priority = 82;
            reward = 0.85;
            noteworthy = true;
        }

        // UV extreme
        if (w.uvIndex > 8) {
            report.append(", EXTREMER UV-INDEX");
            priority = 75;
            reward = 0.8;
            noteworthy = true;
        }

        // Generate goal if noteworthy or first observation
        if (noteworthy || lastObservation == null) {
            String goal = "Weather observation " + stationId + ": " + report;
            agent.addGoal(goal, "weather", priority, reward, 1);
            LOG.fine("Weather goal: " + goal);
        }
    }

    /**
     * Detect significant changes since last observation.
     */
    private void detectTrends(Agent agent, WeatherSnapshot w) {
        if (lastTempC == null) return;

        double tempDelta = w.tempC - lastTempC;
        double humidityDelta = w.humidity - lastHumidity;

        // Significant temperature change (>3°C since last check)
        if (Math.abs(tempDelta) >= 3) {
            String dir = tempDelta > 0 ? "gestiegen" : "gefallen";
            String goal = String.format(
                    "Wetter-Trend %s: Temperatur um %.1f°C %s (%.1f → %.1f°C) in %d min",
                    stationId, Math.abs(tempDelta), dir, lastTempC, w.tempC,
                    Duration.between(lastObservation, Instant.now()).toMinutes());
            agent.addGoal(goal, "weather-trend", 65, 0.7, 1);
        }

        // Rapid humidity change (>20%)
        if (Math.abs(humidityDelta) >= 20) {
            String dir = humidityDelta > 0 ? "gestiegen" : "gefallen";
            String goal = String.format(
                    "Wetter-Trend %s: Luftfeuchte %s (%.0f → %.0f%%)",
                    stationId, dir, lastHumidity, w.humidity);
            agent.addGoal(goal, "weather-trend", 60, 0.65, 1);
        }
    }

    /**
     * Parse weather.com PWS JSON into a snapshot.
     */
    private WeatherSnapshot parseWeather(String json) {
        try {
            int obsStart = json.indexOf("\"observations\":[");
            if (obsStart < 0) return null;
            obsStart += "\"observations\":".length();

            int arrEnd = findMatchingBracket(json, obsStart - 1);
            if (arrEnd < 0) return null;

            int firstObj = json.indexOf('{', obsStart);
            if (firstObj < 0 || firstObj >= arrEnd) return null;
            int firstEnd = findMatchingBrace(json, firstObj);
            if (firstEnd < 0) return null;

            String obs = json.substring(firstObj, firstEnd + 1);

            // Extract metric values
            int metricStart = obs.indexOf("\"metric\":{");
            String metricSection = metricStart >= 0 ? obs.substring(metricStart) : obs;

            double temp = extractJsonDouble(metricSection, "temp");
            double humidity = extractJsonDouble(metricSection, "humidity");
            double wind = extractJsonDouble(metricSection, "windspeed");
            double gust = extractJsonDouble(metricSection, "windgust");
            double precip = extractJsonDouble(metricSection, "precipRate");
            double uv = extractJsonDouble(metricSection, "uv");

            return new WeatherSnapshot(temp, humidity, wind, gust, precip, uv);
        } catch (Exception e) {
            LOG.warning("Weather parse error: " + e.getMessage());
            return null;
        }
    }

    // ── JSON helpers ──────────────────────────────────────────────

    private double extractJsonDouble(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start < 0) return 0;
        start += search.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start < json.length() && json.charAt(start) == 'n') return 0; // null
        StringBuilder num = new StringBuilder();
        while (start < json.length() && (Character.isDigit(json.charAt(start)) 
                || json.charAt(start) == '.' || json.charAt(start) == '-')) {
            num.append(json.charAt(start++));
        }
        try { return Double.parseDouble(num.toString()); } 
        catch (NumberFormatException e) { return 0; }
    }

    private int findMatchingBracket(String json, int openPos) {
        char open = json.charAt(openPos);
        char close = open == '[' ? ']' : '{';
        int depth = 0;
        boolean inString = false;
        for (int i = openPos; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) inString = !inString;
            if (inString) continue;
            if (c == open) depth++;
            else if (c == close) { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    private int findMatchingBrace(String json, int openBrace) {
        return findMatchingBracket(json, openBrace);
    }

    // ── Value object ──────────────────────────────────────────────

    record WeatherSnapshot(double tempC, double humidity, double windKph, 
                           double windGustKph, double precipRateMmh, double uvIndex) {}
}
