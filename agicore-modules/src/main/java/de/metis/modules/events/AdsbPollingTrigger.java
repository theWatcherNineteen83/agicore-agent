package de.metis.modules.events;

import de.metis.kernel.world.Belief;
import de.metis.modules.Agent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Polls the readsb JSON API and generates Metis goals when aircraft activity is notable.
 * <p>
 * Monitors:
 * <ul>
 *   <li>New/unseen aircraft (by hex code)</li>
 *   <li>Unusual altitude or speed patterns</li>
 *   <li>Military/special squawk codes (emergency, hijack, radio failure)</li>
 *   <li>High traffic density (>10 aircraft visible)</li>
 *   <li>Aircraft proximity to home location (Coburg/Heldburg)</li>
 * </ul>
 * <p>
 * Stores aircraft observations as WorldModel beliefs with confidence decay.
 */
public class AdsbPollingTrigger implements EventTrigger {

    private static final Logger LOG = Logger.getLogger(AdsbPollingTrigger.class.getName());

    private static final String READS_URL = "http://192.168.22.200:8085/data/aircraft.json";
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(60);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    // Home location (Coburg/Heldburg area)
    private static final double HOME_LAT = 50.26;
    private static final double HOME_LON = 10.73;

    private final HttpClient http;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread pollingThread;

    // State tracking
    private final Set<String> knownHexCodes = ConcurrentHashMap.newKeySet();
    private final Map<String, Instant> lastSeen = new ConcurrentHashMap<>();
    private final Map<String, AircraftSnapshot> lastSnapshots = new ConcurrentHashMap<>();
    private int lastAircraftCount = 0;
    private Instant lastPoll = null;

    public AdsbPollingTrigger() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String name() { return "adsb-polling"; }

    @Override
    public String description() {
        return "Polls readsb JSON API every " + POLL_INTERVAL.toSeconds()
                + "s for aircraft activity near Coburg/Heldburg";
    }

    @Override
    public void start(Agent agent) {
        if (running.getAndSet(true)) return;

        pollingThread = Thread.ofVirtual().name("adsb-poll").start(() -> {
            while (running.get()) {
                try {
                    pollAndEvaluate(agent);
                    Thread.sleep(POLL_INTERVAL.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });

        LOG.info("ADS-B polling started — readsb every " + POLL_INTERVAL.toSeconds() + "s");
    }

    @Override
    public void stop() {
        running.set(false);
        if (pollingThread != null) pollingThread.interrupt();
        LOG.info("ADS-B polling stopped");
    }

    /**
     * Poll readsb and evaluate aircraft activity.
     */
    private void pollAndEvaluate(Agent agent) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(READS_URL))
                    .timeout(HTTP_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                LOG.warning("Readsb returned " + resp.statusCode());
                return;
            }

            List<AircraftSnapshot> aircraft = parseAircraft(resp.body());
            if (aircraft.isEmpty()) {
                LOG.fine("No aircraft in range");
                return;
            }

            int count = aircraft.size();
            int newAircraft = 0;
            int unusualAircraft = 0;
            boolean trafficSpike = false;

            for (AircraftSnapshot a : aircraft) {
                boolean isNew = knownHexCodes.add(a.hex);
                if (isNew) {
                    newAircraft++;
                    lastSeen.put(a.hex, Instant.now());
                    storeAircraftBelief(agent, a);
                }

                // Detect unusual patterns
                if (isUnusual(a)) {
                    unusualAircraft++;
                    generateUnusualGoal(agent, a);
                }

                // Proximity check
                double distKm = haversineKm(HOME_LAT, HOME_LON, a.lat, a.lon);
                if (distKm < 50 && a.altBaro != null && a.altBaro < 10000) {
                    generateProximityGoal(agent, a, distKm);
                }

                // Track snapshot for trend detection
                lastSnapshots.put(a.hex, a);
            }

            // Traffic spike detection
            if (lastAircraftCount > 0 && count > lastAircraftCount * 2 && count > 5) {
                trafficSpike = true;
            }

            // Generate summary goal if noteworthy
            StringBuilder summary = new StringBuilder();
            summary.append("ADS-B: ").append(count).append(" Flugzeuge im Bereich");

            if (newAircraft > 0) {
                summary.append(", ").append(newAircraft).append(" neu");
            }
            if (unusualAircraft > 0) {
                summary.append(", ").append(unusualAircraft).append(" ungewöhnlich");
            }
            if (trafficSpike) {
                summary.append(", VERKEHRSSPITZE (vorher ").append(lastAircraftCount).append(")");
            }

            int priority = 40;
            double reward = 0.5;

            if (newAircraft > 2 || unusualAircraft > 0 || trafficSpike) {
                priority = 65;
                reward = 0.7;
                agent.addGoal(summary.toString(), "adsb", priority, reward, 5);
            }

            // Store traffic density as belief
            agent.worldModel().update(
                    "Aktuell " + count + " Flugzeuge im ADS-B-Empfangsbereich",
                    0.8,
                    "ADSB_POLL",
                    true
            );

            lastAircraftCount = count;
            lastPoll = Instant.now();

        } catch (Exception e) {
            LOG.warning("ADS-B polling error: " + e.getMessage());
        }
    }

    /**
     * Store an aircraft as a WorldModel belief.
     */
    private void storeAircraftBelief(Agent agent, AircraftSnapshot a) {
        StringBuilder desc = new StringBuilder();
        desc.append("Flugzeug ").append(a.hex);
        if (a.flight != null && !a.flight.isBlank()) {
            desc.append(" (").append(a.flight.trim()).append(")");
        }
        if (a.altBaro != null) {
            desc.append(" auf ").append(String.format("%.0f ft", a.altBaro));
        }
        if (a.gs != null) {
            desc.append(", ").append(String.format("%.0f kts", a.gs));
        }
        if (a.lat != null && a.lon != null) {
            double dist = haversineKm(HOME_LAT, HOME_LON, a.lat, a.lon);
            desc.append(", ").append(String.format("%.0f km entfernt", dist));
        }

        agent.worldModel().update(
                desc.toString(),
                0.7,
                "ADSB_POLL",
                true
        );
    }

    /**
     * Generate a goal for an unusual aircraft (squawk, altitude, speed).
     */
    private void generateUnusualGoal(Agent agent, AircraftSnapshot a) {
        StringBuilder goal = new StringBuilder("ADS-B ungewöhnlich: ");
        goal.append(a.hex);
        if (a.flight != null && !a.flight.isBlank()) {
            goal.append(" (").append(a.flight.trim()).append(")");
        }
        goal.append(" — ");

        if (a.squawk != null) {
            String sq = a.squawk.trim();
            switch (sq) {
                case "7700" -> goal.append("NOTFALL (Squawk 7700)");
                case "7600" -> goal.append("FUNKAUSFALL (Squawk 7600)");
                case "7500" -> goal.append("ENTFÜHRUNG (Squawk 7500)");
                default -> {
                    if (sq.startsWith("00") || sq.startsWith("77")) {
                        goal.append("Militär-Squawk ").append(sq);
                    } else {
                        goal.append("Ungewöhnlicher Squawk ").append(sq);
                    }
                }
            }
        }

        if (a.altBaro != null && a.altBaro > 45000) {
            goal.append(", Extremhöhe ").append(String.format("%.0f ft", a.altBaro));
        }
        if (a.gs != null && a.gs > 600) {
            goal.append(", Überschall? ").append(String.format("%.0f kts", a.gs));
        }
        if (a.altBaro != null && a.altBaro < 1000 && a.gs != null && a.gs > 200) {
            goal.append(", Tiefflug schnell");
        }

        agent.addGoal(goal.toString(), "adsb-unusual", 75, 0.8, 3);
    }

    /**
     * Generate a goal for an aircraft close to home.
     */
    private void generateProximityGoal(Agent agent, AircraftSnapshot a, double distKm) {
        String flight = (a.flight != null && !a.flight.isBlank())
                ? a.flight.trim() : a.hex;
        String goal = String.format(
                "ADS-B Nähe: %s in %.0f km Entfernung auf %.0f ft",
                flight, distKm, a.altBaro != null ? a.altBaro : 0);
        agent.addGoal(goal, "adsb-proximity", 70, 0.75, 3);
    }

    /**
     * Determine if an aircraft's telemetry is unusual.
     */
    private boolean isUnusual(AircraftSnapshot a) {
        // Emergency/hijack/radio-failure squawks
        if (a.squawk != null) {
            String sq = a.squawk.trim();
            if ("7700".equals(sq) || "7600".equals(sq) || "7500".equals(sq)) {
                return true;
            }
        }

        // Extreme altitude
        if (a.altBaro != null && a.altBaro > 45000) return true;

        // High speed
        if (a.gs != null && a.gs > 600) return true;

        // Low + fast
        if (a.altBaro != null && a.altBaro < 1000 
                && a.gs != null && a.gs > 200) return true;

        return false;
    }

    // ── JSON Parsing ──────────────────────────────────────────────

    /**
     * Parse readsb ajax/aircraft JSON array into snapshots.
     * Format: {"aircraft": [{"hex":"...", "flight":"...", ...}]}
     */
    @SuppressWarnings("unchecked")
    private List<AircraftSnapshot> parseAircraft(String json) {
        List<AircraftSnapshot> result = new ArrayList<>();

        try {
            // Find the "aircraft" array
            int arrStart = json.indexOf("\"aircraft\":");
            if (arrStart < 0) arrStart = json.indexOf("\"aircraft\" :");
            if (arrStart < 0) return result;

            int bracketStart = json.indexOf('[', arrStart);
            if (bracketStart < 0) return result;
            int bracketEnd = findMatchingBracket(json, bracketStart);
            if (bracketEnd < 0) return result;

            String arrayContent = json.substring(bracketStart + 1, bracketEnd);

            // Split by aircraft objects (each is {...})
            int pos = 0;
            while (pos < arrayContent.length()) {
                int objStart = arrayContent.indexOf('{', pos);
                if (objStart < 0) break;
                int objEnd = findMatchingBrace(arrayContent, objStart);
                if (objEnd < 0) break;

                String acJson = arrayContent.substring(objStart, objEnd + 1);
                AircraftSnapshot snap = parseSingleAircraft(acJson);
                if (snap != null && snap.lat != null && snap.lon != null) {
                    result.add(snap);
                }

                pos = objEnd + 1;
            }
        } catch (Exception e) {
            LOG.warning("ADS-B parse error: " + e.getMessage());
        }

        return result;
    }

    private AircraftSnapshot parseSingleAircraft(String json) {
        String hex = extractJsonString(json, "hex");
        String flight = extractJsonString(json, "flight");
        String squawk = extractJsonString(json, "squawk");
        Double lat = extractJsonDouble(json, "lat");
        Double lon = extractJsonDouble(json, "lon");
        Double altBaro = extractJsonDouble(json, "alt_baro");
        Double gs = extractJsonDouble(json, "gs");
        Double track = extractJsonDouble(json, "track");
        Double seen = extractJsonDouble(json, "seen");

        if (hex == null || hex.isBlank()) return null;

        return new AircraftSnapshot(hex, flight, squawk, lat, lon, altBaro, gs, track, seen);
    }

    // ── JSON helpers ──────────────────────────────────────────────

    private String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) {
            // Try with spaces
            search = "\"" + key + "\": \"";
            start = json.indexOf(search);
        }
        if (start < 0) return null;

        start = json.indexOf('"', start + search.length() - 1);
        if (start < 0) return null;
        start++; // skip opening quote

        StringBuilder val = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                i++;
                continue;
            }
            if (c == '"') break;
            val.append(c);
        }
        return val.toString();
    }

    private Double extractJsonDouble(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start < 0) {
            search = "\"" + key + "\": ";
            start = json.indexOf(search);
        }
        if (start < 0) return null;
        start += search.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;

        if (start >= json.length()) return null;
        if (json.charAt(start) == 'n') return null; // null

        StringBuilder num = new StringBuilder();
        while (start < json.length() && (Character.isDigit(json.charAt(start))
                || json.charAt(start) == '.' || json.charAt(start) == '-'
                || json.charAt(start) == 'e' || json.charAt(start) == 'E')) {
            num.append(json.charAt(start++));
        }
        try {
            return num.isEmpty() ? null : Double.parseDouble(num.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int findMatchingBracket(String json, int openPos) {
        char open = json.charAt(openPos);
        char close = open == '[' ? ']' : '}';
        int depth = 0;
        boolean inString = false;
        for (int i = openPos; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) inString = !inString;
            if (inString) continue;
            if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private int findMatchingBrace(String json, int openBrace) {
        return findMatchingBracket(json, openBrace);
    }

    // ── Geospatial ─────────────────────────────────────────────────

    /**
     * Haversine distance in kilometers.
     */
    static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    // ── Accessors ──────────────────────────────────────────────────

    public int knownAircraftCount() { return knownHexCodes.size(); }
    public int lastCount() { return lastAircraftCount; }
    public Instant lastPollTime() { return lastPoll; }

    // ── Value object ───────────────────────────────────────────────

    record AircraftSnapshot(
            String hex, String flight, String squawk,
            Double lat, Double lon, Double altBaro,
            Double gs, Double track, Double seen) {}
}
