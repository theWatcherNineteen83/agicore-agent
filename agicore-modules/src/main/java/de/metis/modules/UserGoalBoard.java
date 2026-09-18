package de.metis.modules;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Externes Kanban-Board für vom Menschen eingestellte Goals.
 * <p>
 * Zweite Dashboard-Seite: Georg stellt Goals der Kategorien
 * WISSEN_ANEIGNEN oder AUFGABE ein. Metis übernimmt OFFENE Goals
 * in seinen normalen Zyklus (die {@link UserGoalBridge} erzeugt dafür
 * interne {@link Goal}s), arbeitet sie ab und setzt sie nach bestandenem
 * maschinellem Vorabtest auf ZU_TESTEN. Georg reviewed dann:
 * bestanden → FERTIG, Fehler gefunden → zurück auf IN_ARBEIT mit Kommentar.
 * <p>
 * Statusfluss: OFFEN → IN_ARBEIT → ZU_TESTEN → FERTIG
 * <p>
 * Persistenz: eigene JSON-Datei (user-goals.json) im Metis-Verzeichnis —
 * getrennt von Metis' internem Goal-Management, überlebt Neustarts.
 */
public class UserGoalBoard {

    private static final Logger LOG = Logger.getLogger(UserGoalBoard.class.getName());

    /** Externe Goal-Kategorien (Georgs Taxonomie). */
    public enum Category {
        /** Wissen einlesen und daraus lernen → weitere Beliefs aufbauen. Metis plant selbst. */
        WISSEN_ANEIGNEN,
        /** Konkrete Aufgabe — Metis plant selbst mit seinen Aktionen. */
        AUFGABE
    }

    /** Board-Status. */
    public enum Status {
        /** Eingestellt, wartet darauf, dass Metis es übernimmt. */
        OFFEN,
        /** Metis hat das Goal in Arbeit (internes Goal aktiv). */
        IN_ARBEIT,
        /** Metis fertig + maschineller Vorabtest bestanden — wartet auf Review. */
        ZU_TESTEN,
        /** Von Georg nach Review freigegeben. */
        FERTIG
    }

    /** Ein Kommentar an einer Goal-Karte (Review-Hinweise, Fehler, Notizen). */
    public record Comment(String author, String text, String at) {}

    /** Ein externes User-Goal auf dem Board. */
    public static final class UserGoal {
        public final String id;
        public final String description;
        public final Category category;
        public final int priority;
        public volatile Status status;
        public final String createdAt;
        public volatile String updatedAt;
        public final List<Comment> comments = new ArrayList<>();
        /** Id des internen Metis-Goals, falls gerade in Arbeit. */
        public volatile String metisGoalId;
        /** Ergebnis/Begründung des maschinellen Vorabtests. */
        public volatile String testReport;
        /** WISSEN_ANEIGNEN: Überblick über das Gelernte (neue Beliefs). */
        public volatile String learnedSummary;
        /** AUFGABE: Ergebnis bzw. Pfad zum Ergebnis. */
        public volatile String result;
        /** AUFGABE: Testprotokoll (welche Aktionen liefen, was geprüft wurde). */
        public volatile String testProtocol;
        /** Anzahl Aufnahme-Versuche durch Metis. */
        public volatile int attempts;
        /** Frühester Zeitpunkt (epoch millis), zu dem die Bridge das Goal wieder aufnehmen darf. */
        public volatile long retryAfterEpochMs;

        public UserGoal(String id, String description, Category category, int priority,
                        Status status, String createdAt, String updatedAt) {
            this.id = id;
            this.description = description;
            this.category = category;
            this.priority = priority;
            this.status = status;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }

        public synchronized void addComment(String author, String text) {
            comments.add(new Comment(author, text, Instant.now().toString()));
        }
    }

    private final Map<String, UserGoal> goals = new ConcurrentHashMap<>();
    private final Path persistFile;
    private final Object lock = new Object();

    public UserGoalBoard(Path persistFile) {
        this.persistFile = persistFile;
        load();
    }

    // ── API für die HTTP-Schicht ────────────────────────────────

    /** Neues Goal anlegen (Status OFFEN). */
    public UserGoal create(String description, Category category, int priority) {
        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        UserGoal g = new UserGoal(id, description, category, clampPriority(priority),
                Status.OFFEN, now, now);
        goals.put(id, g);
        save();
        LOG.info("UserGoalBoard: neu OFFEN [" + category + "] prio=" + g.priority
                + " — " + truncate(description));
        return g;
    }

    /** Alle Goals, neueste zuerst. */
    public List<UserGoal> list() {
        return goals.values().stream()
                .sorted(Comparator.comparing((UserGoal g) -> g.createdAt).reversed())
                .toList();
    }

    /** Goals in einem bestimmten Status. */
    public List<UserGoal> byStatus(Status s) {
        return goals.values().stream().filter(g -> g.status == s).toList();
    }

    public Optional<UserGoal> get(String id) {
        return Optional.ofNullable(goals.get(id));
    }

    /**
     * Manueller Statuswechsel durch Georg (Review).
     * Erlaubt: ZU_TESTEN→FERTIG, ZU_TESTEN→IN_ARBEIT (Kommentar), beliebig→OFFEN.
     */
    public boolean manualTransition(String id, Status to, String comment) {
        UserGoal g = goals.get(id);
        if (g == null) return false;
        synchronized (g) {
            Status from = g.status;
            boolean ok = switch (to) {
                case FERTIG -> from == Status.ZU_TESTEN;
                case IN_ARBEIT -> from == Status.ZU_TESTEN;
                case OFFEN -> true; // manuell jederzeit auf OFFEN zurücksetzbar
                default -> false;
            };
            if (!ok) return false;
            if ((to == Status.IN_ARBEIT || to == Status.OFFEN)
                    && (comment == null || comment.isBlank())) {
                return false; // Rücksprung erfordert Kommentar
            }
            g.status = to;
            g.updatedAt = Instant.now().toString();
            if (to == Status.FERTIG) {
                g.addComment("georg", "Freigegeben: " + (comment == null ? "" : comment));
            } else {
                g.addComment("georg", comment == null ? "" : comment);
            }
            if (to == Status.IN_ARBEIT || to == Status.OFFEN) {
                // Fehler gefunden / neu geöffnet → Metis soll es erneut versuchen
                g.metisGoalId = null;
                g.testReport = null;
                g.learnedSummary = null;
                g.result = null;
                g.testProtocol = null;
                g.retryAfterEpochMs = 0;
            }
            LOG.info("UserGoalBoard: manuell " + from + " → " + to + " (" + id + ")");
        }
        save();
        return true;
    }

    /** Kommentar an eine Karte hängen (ohne Statuswechsel). */
    public boolean addComment(String id, String author, String text) {
        UserGoal g = goals.get(id);
        if (g == null || text == null || text.isBlank()) return false;
        g.addComment(author, text.trim());
        g.updatedAt = Instant.now().toString();
        save();
        return true;
    }

    // ── Übergänge durch die Bridge (Metis) ──────────────────────

    /** OFFEN → IN_ARBEIT: Metis nimmt das Goal auf. */
    public boolean markInArbeit(UserGoal g, String metisGoalId) {
        if (g.status != Status.OFFEN) return false;
        g.status = Status.IN_ARBEIT;
        g.metisGoalId = metisGoalId;
        g.updatedAt = Instant.now().toString();
        save();
        return true;
    }

    /** IN_ARBEIT → ZU_TESTEN: maschineller Vorabtest bestanden. */
    public boolean markZuTesten(UserGoal g, String testReport) {
        return markZuTesten(g, testReport, null, null, null);
    }

    /**
     * IN_ARBEIT → ZU_TESTEN mit Detailfeldern.
     * <ul>
     *   <li>{@code learnedSummary} — WISSEN_ANEIGNEN: Überblick über das Gelernte.</li>
     *   <li>{@code result} — AUFGABE: Ergebnis bzw. Pfad zum Ergebnis.</li>
     *   <li>{@code testProtocol} — AUFGABE: Testprotokoll (was geprüft wurde).</li>
     * </ul>
     */
    public boolean markZuTesten(UserGoal g, String testReport, String learnedSummary,
                                String result, String testProtocol) {
        if (g.status != Status.IN_ARBEIT) return false;
        g.status = Status.ZU_TESTEN;
        g.testReport = testReport;
        g.learnedSummary = learnedSummary;
        g.result = result;
        g.testProtocol = testProtocol;
        g.updatedAt = Instant.now().toString();
        g.addComment("metis", "Maschineller Vorabtest: " + testReport);
        save();
        LOG.info("UserGoalBoard: ZU_TESTEN ← " + truncate(g.description)
                + " (Test: " + testReport + ")");
        return true;
    }

    /** IN_ARBEIT → OFFEN: Metis kam nicht voran / Test fehlgeschlagen. */
    public boolean markZurueck(UserGoal g, String reason, long retryAfterEpochMs) {
        if (g.status != Status.IN_ARBEIT) return false;
        g.status = Status.OFFEN;
        g.metisGoalId = null;
        g.retryAfterEpochMs = retryAfterEpochMs;
        g.updatedAt = Instant.now().toString();
        g.addComment("metis", reason);
        save();
        LOG.info("UserGoalBoard: OFFEN ← " + truncate(g.description) + " (" + reason + ")");
        return true;
    }

    /** Retry-Cooldown setzen (nach Fehlschlag). */
    public void setRetryAfter(UserGoal g, long epochMs) {
        g.retryAfterEpochMs = epochMs;
    }

    /** Darf die Bridge dieses OFFENE Goal (wieder) aufnehmen? */
    public boolean retryReady(UserGoal g) {
        return g.status == Status.OFFEN
                && System.currentTimeMillis() >= g.retryAfterEpochMs;
    }

    // ── Persistenz ──────────────────────────────────────────────

    private static final String JSON_START = "{\"goals\":[";
    private static final String JSON_END = "]}";

    private void load() {
        if (persistFile == null || !Files.exists(persistFile)) return;
        try {
            String json = Files.readString(persistFile, StandardCharsets.UTF_8);
            int arrStart = json.indexOf('[');
            int arrEnd = json.lastIndexOf(']');
            if (arrStart < 0 || arrEnd <= arrStart) return;
            String inner = json.substring(arrStart + 1, arrEnd).trim();
            if (inner.isEmpty()) return;
            for (String obj : splitObjects(inner)) {
                UserGoal g = parseGoal(obj);
                if (g != null) goals.put(g.id, g);
            }
            LOG.info("UserGoalBoard: " + goals.size() + " Goals aus "
                    + persistFile.getFileName() + " geladen");
        } catch (Exception e) {
            LOG.warning("UserGoalBoard: Laden fehlgeschlagen: " + e.getMessage());
        }
    }

    private void save() {
        if (persistFile == null) return;
        synchronized (lock) {
            try {
                StringBuilder sb = new StringBuilder(JSON_START);
                boolean first = true;
                for (UserGoal g : goals.values()) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append(toJson(g));
                }
                sb.append(JSON_END);
                Files.createDirectories(persistFile.getParent());
                Path tmp = persistFile.resolveSibling(persistFile.getFileName() + ".tmp");
                Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
                Files.move(tmp, persistFile,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                LOG.warning("UserGoalBoard: Speichern fehlgeschlagen: " + e.getMessage());
            }
        }
    }

    private static String toJson(UserGoal g) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"id\":\"").append(jsonEscape(g.id)).append('"');
        sb.append(",\"description\":\"").append(jsonEscape(g.description)).append('"');
        sb.append(",\"category\":\"").append(g.category.name()).append('"');
        sb.append(",\"priority\":").append(g.priority);
        sb.append(",\"status\":\"").append(g.status.name()).append('"');
        sb.append(",\"createdAt\":\"").append(jsonEscape(g.createdAt)).append('"');
        sb.append(",\"updatedAt\":\"").append(jsonEscape(g.updatedAt)).append('"');
        sb.append(",\"metisGoalId\":").append(g.metisGoalId == null
                ? "null" : "\"" + jsonEscape(g.metisGoalId) + "\"");
        sb.append(",\"testReport\":").append(g.testReport == null
                ? "null" : "\"" + jsonEscape(g.testReport) + "\"");
        sb.append(",\"learnedSummary\":").append(g.learnedSummary == null
                ? "null" : "\"" + jsonEscape(g.learnedSummary) + "\"");
        sb.append(",\"result\":").append(g.result == null
                ? "null" : "\"" + jsonEscape(g.result) + "\"");
        sb.append(",\"testProtocol\":").append(g.testProtocol == null
                ? "null" : "\"" + jsonEscape(g.testProtocol) + "\"");
        sb.append(",\"attempts\":").append(g.attempts);
        sb.append(",\"retryAfterEpochMs\":").append(g.retryAfterEpochMs);
        sb.append(",\"comments\":[");
        boolean firstC = true;
        for (Comment c : g.comments) {
            if (!firstC) sb.append(',');
            firstC = false;
            sb.append("{\"author\":\"").append(jsonEscape(c.author()))
              .append("\",\"text\":\"").append(jsonEscape(c.text()))
              .append("\",\"at\":\"").append(jsonEscape(c.at())).append("\"}");
        }
        sb.append(']');
        sb.append('}');
        return sb.toString();
    }

    private static UserGoal parseGoal(String obj) {
        try {
            String id = jsonField(obj, "id");
            String description = jsonField(obj, "description");
            String category = jsonField(obj, "category");
            String status = jsonField(obj, "status");
            if (id == null || description == null || category == null) return null;
            int priority = parseIntField(obj, "priority", 50);
            String createdAt = jsonField(obj, "createdAt");
            String updatedAt = jsonField(obj, "updatedAt");
            if (createdAt == null) createdAt = Instant.now().toString();
            if (updatedAt == null) updatedAt = createdAt;
            UserGoal g = new UserGoal(id, description,
                    Category.valueOf(category),
                    priority,
                    status != null ? Status.valueOf(status) : Status.OFFEN,
                    createdAt, updatedAt);
            String mid = jsonField(obj, "metisGoalId");
            g.metisGoalId = mid;
            String report = jsonField(obj, "testReport");
            g.testReport = report;
            g.learnedSummary = jsonField(obj, "learnedSummary");
            g.result = jsonField(obj, "result");
            g.testProtocol = jsonField(obj, "testProtocol");
            g.attempts = parseIntField(obj, "attempts", 0);
            g.retryAfterEpochMs = parseLongField(obj, "retryAfterEpochMs", 0L);
            String commentsRaw = jsonField(obj, "comments");
            if (commentsRaw != null && commentsRaw.length() > 2) {
                for (String co : splitObjects(commentsRaw)) {
                    String a = jsonField(co, "author");
                    String t = jsonField(co, "text");
                    String at = jsonField(co, "at");
                    if (t != null) g.comments.add(new Comment(a == null ? "?" : a, t,
                            at == null ? Instant.now().toString() : at));
                }
            }
            return g;
        } catch (Exception e) {
            LOG.warning("UserGoalBoard: Goal-Parsing fehlgeschlagen: " + e.getMessage());
            return null;
        }
    }

    /** JSON-Array-Objekte auf oberster Ebene grob aufteilen (verschachtelte Klammern zählen). */
    private static List<String> splitObjects(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        boolean inStr = false;
        char prev = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                cur.append(c);
                if (c == '"' && prev != '\\') inStr = false;
            } else {
                if (c == '"') { inStr = true; cur.append(c); }
                else if (c == '{') { depth++; cur.append(c); }
                else if (c == '}') { depth--; cur.append(c); if (depth == 0) { out.add(cur.toString()); cur.setLength(0); } }
                else if (depth > 0) cur.append(c);
            }
            prev = c;
        }
        return out;
    }

    private static String jsonField(String obj, String key) {
        String search = "\"" + key + "\":";
        int idx = obj.indexOf(search);
        if (idx < 0) return null;
        idx += search.length();
        if (idx >= obj.length()) return null;
        char c = obj.charAt(idx);
        if (c == '"') {
            StringBuilder sb = new StringBuilder();
            boolean esc = false;
            for (int i = idx + 1; i < obj.length(); i++) {
                char ch = obj.charAt(i);
                if (esc) {
                    switch (ch) {
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            if (i + 4 < obj.length()) {
                                try {
                                    sb.append((char) Integer.parseInt(obj.substring(i + 1, i + 5), 16));
                                    i += 4;
                                } catch (NumberFormatException nfe) {
                                    sb.append(ch);
                                }
                            } else {
                                sb.append(ch);
                            }
                            break;
                        default: sb.append(ch); break; // \\ \" \/ etc.
                    }
                    esc = false;
                }
                else if (ch == '\\') esc = true;
                else if (ch == '"') break;
                else sb.append(ch);
            }
            return sb.toString();
        } else if (c == 'n' && obj.startsWith("null", idx)) {
            return null;
        } else if (c == '[' || c == '{') {
            int depth = 0; boolean in = false; char p = 0;
            StringBuilder sb = new StringBuilder();
            for (int i = idx; i < obj.length(); i++) {
                char ch = obj.charAt(i);
                sb.append(ch);
                if (in) { if (ch == '"' && p != '\\') in = false; }
                else { if (ch == '"') in = true;
                    else if (ch == '[' || ch == '{') depth++;
                    else if (ch == ']' || ch == '}') { depth--; if (depth == 0) return sb.toString(); } }
                p = ch;
            }
            return sb.toString();
        }
        // Zahl o.ä.
        StringBuilder sb = new StringBuilder();
        for (int i = idx; i < obj.length() && obj.charAt(i) != ',' && obj.charAt(i) != '}'; i++) {
            sb.append(obj.charAt(i));
        }
        return sb.toString().trim();
    }

    private static int parseIntField(String obj, String key, int dflt) {
        String v = jsonField(obj, key);
        if (v == null) return dflt;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return dflt; }
    }

    private static long parseLongField(String obj, String key, long dflt) {
        String v = jsonField(obj, key);
        if (v == null) return dflt;
        try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) { return dflt; }
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static int clampPriority(int p) {
        return Math.max(1, Math.min(100, p));
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 80 ? s : s.substring(0, 80) + "…";
    }
}
