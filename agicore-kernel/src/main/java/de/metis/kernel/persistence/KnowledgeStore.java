package de.metis.kernel.persistence;

import de.metis.kernel.memory.Experience;
import de.metis.kernel.world.Belief;

import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * SQLite-basierte Wissensdatenbank für Metis.
 * <p>
 * Persistiert:
 * <ul>
 *   <li>WorldModel-Beliefs (statement, confidence, source, evidence)</li>
 *   <li>Erfahrungen/Experiences (goal, action, success, prediction error)</li>
 *   <li>Planner-Mappings (goal-keyword + action → success rate)</li>
 *   <li>Evolution-History (accepted/rejected mutations mit Timestamp)</li>
 * </ul>
 * <p>
 * Eine Datei: {@code metis-knowledge.db} im Arbeitsverzeichnis.
 * Überlebt Neustarts, keine externe DB nötig.
 */
public class KnowledgeStore implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(KnowledgeStore.class.getName());

    private final Connection conn;

    public KnowledgeStore(Path dbPath) throws SQLException {
        boolean exists = Files.exists(dbPath);
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        // Concurrency-safe defaults so external feed scripts (Python feed_batch.py)
        // can write while Metis is running without locking each other out.
        try (Statement pragma = conn.createStatement()) {
            pragma.execute("PRAGMA journal_mode=WAL");
            pragma.execute("PRAGMA synchronous=NORMAL");
            pragma.execute("PRAGMA busy_timeout=30000");
        } catch (SQLException e) {
            LOG.warning("KnowledgeStore: PRAGMA setup failed: " + e.getMessage());
        }
        initSchema();
        LOG.info("KnowledgeStore: " + dbPath + (exists ? " (existing)" : " (new)") + " [WAL mode]");
    }

    public KnowledgeStore() throws SQLException {
        this(Path.of("metis-knowledge.db"));
    }

    // ── Schema ──────────────────────────────────────────────────────

    private void initSchema() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS beliefs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        statement TEXT NOT NULL UNIQUE,
                        confidence REAL NOT NULL DEFAULT 0.5,
                        source TEXT NOT NULL DEFAULT 'unknown',
                        evidence INTEGER NOT NULL DEFAULT 1,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS experiences (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        goal_description TEXT NOT NULL,
                        action_name TEXT NOT NULL,
                        success INTEGER NOT NULL DEFAULT 0,
                        prediction_error REAL NOT NULL DEFAULT 0.5,
                        salience REAL NOT NULL DEFAULT 0.5,
                        body TEXT,
                        timestamp TEXT NOT NULL
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS planner_mappings (
                        goal_keyword TEXT NOT NULL,
                        action_name TEXT NOT NULL,
                        attempts INTEGER NOT NULL DEFAULT 0,
                        successes INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (goal_keyword, action_name)
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS evolution_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        module_name TEXT NOT NULL,
                        accepted INTEGER NOT NULL DEFAULT 0,
                        fitness REAL,
                        message TEXT,
                        timestamp TEXT NOT NULL
                    )
                    """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_beliefs_conf ON beliefs(confidence DESC)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_experiences_time ON experiences(timestamp DESC)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_experiences_action ON experiences(action_name)");

            // Conversation support (Phase 2)
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS conversation_messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        session_id TEXT NOT NULL,
                        role TEXT NOT NULL CHECK(role IN ('user','assistant','system')),
                        content TEXT NOT NULL,
                        timestamp TEXT NOT NULL
                    )
                    """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_conv_session ON conversation_messages(session_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_conv_time ON conversation_messages(timestamp)");
        }
    }

    // ── Beliefs ─────────────────────────────────────────────────────

    public void saveBelief(Belief belief) {
        String now = Instant.now().toString();
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO beliefs (statement, confidence, source, evidence, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(statement) DO UPDATE SET
                    confidence = excluded.confidence,
                    source = excluded.source,
                    evidence = excluded.evidence,
                    updated_at = excluded.updated_at
                """)) {
            ps.setString(1, belief.statement());
            ps.setDouble(2, belief.confidence());
            ps.setString(3, belief.source());
            ps.setInt(4, belief.evidence());
            ps.setString(5, now);
            ps.setString(6, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.fine("Failed to save belief: " + e.getMessage());
        }
    }

    public List<Belief> loadBeliefs() {
        List<Belief> beliefs = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT statement, confidence, source, evidence FROM beliefs ORDER BY confidence DESC")) {
            while (rs.next()) {
                beliefs.add(new Belief(
                        rs.getString("statement"),
                        rs.getDouble("confidence"),
                        rs.getString("source")));
            }
        } catch (SQLException e) {
            LOG.warning("Failed to load beliefs: " + e.getMessage());
        }
        return beliefs;
    }

    /**
     * Load only the top-N highest-confidence beliefs (warm-start cache).
     */
    public List<Belief> loadTopBeliefs(int limit) {
        List<Belief> beliefs = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT statement, confidence, source FROM beliefs ORDER BY confidence DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    beliefs.add(new Belief(
                            rs.getString("statement"),
                            rs.getDouble("confidence"),
                            rs.getString("source")));
                }
            }
        } catch (SQLException e) {
            LOG.warning("Failed to load top beliefs: " + e.getMessage());
        }
        return beliefs;
    }

    public void deleteWeakBeliefs(double threshold) {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM beliefs WHERE confidence < ?")) {
            ps.setDouble(1, threshold);
            int deleted = ps.executeUpdate();
            if (deleted > 0) LOG.fine("Cleaned " + deleted + " weak beliefs (<" + threshold + ")");
        } catch (SQLException e) {
            LOG.fine("Failed to clean weak beliefs: " + e.getMessage());
        }
    }

    /**
     * Search beliefs by keyword match (SQL LIKE).
     */
    public List<Belief> searchBeliefs(List<String> keywords, int limit) {
        List<Belief> results = new ArrayList<>();
        if (keywords.isEmpty()) return results;

        StringBuilder sql = new StringBuilder(
                "SELECT statement, confidence, source FROM beliefs WHERE ");
        for (int i = 0; i < keywords.size(); i++) {
            if (i > 0) sql.append(" OR ");
            sql.append("LOWER(statement) LIKE ?");
        }
        sql.append(" ORDER BY confidence DESC LIMIT ?");

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < keywords.size(); i++) {
                ps.setString(i + 1, "%" + keywords.get(i).toLowerCase() + "%");
            }
            ps.setInt(keywords.size() + 1, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new Belief(
                            rs.getString("statement"),
                            rs.getDouble("confidence"),
                            rs.getString("source")));
                }
            }
        } catch (SQLException e) {
            LOG.warning("Belief search failed: " + e.getMessage());
        }
        return results;
    }

    // ── Experiences ─────────────────────────────────────────────────

    public void saveExperience(Experience exp) {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO experiences (goal_description, action_name, success,
                    prediction_error, salience, body, timestamp)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setString(1, exp.goalDescription());
            ps.setString(2, exp.actionName());
            ps.setInt(3, exp.success() ? 1 : 0);
            ps.setDouble(4, exp.predictionError());
            ps.setDouble(5, exp.salience());
            ps.setString(6, exp.body() != null ? exp.body().substring(0, Math.min(exp.body().length(), 2000)) : "");
            ps.setString(7, exp.timestamp().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.fine("Failed to save experience: " + e.getMessage());
        }
    }

    /** Load recent experiences, newest first. */
    public List<Experience> loadRecentExperiences(int limit) {
        List<Experience> experiences = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT * FROM experiences ORDER BY timestamp DESC LIMIT " + limit)) {
            while (rs.next()) {
                experiences.add(new Experience(
                        rs.getString("goal_description"),
                        rs.getString("action_name"),
                        rs.getInt("success") == 1,
                        rs.getString("body"),
                        rs.getDouble("prediction_error"),
                        new double[0]));
            }
        } catch (SQLException e) {
            LOG.fine("Failed to load experiences: " + e.getMessage());
        }
        return experiences;
    }

    // ── Planner-Mappings ───────────────────────────────────────────

    public void savePlannerMapping(String goalKeyword, String actionName,
                                    int attempts, int successes) {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO planner_mappings (goal_keyword, action_name, attempts, successes)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(goal_keyword, action_name) DO UPDATE SET
                    attempts = excluded.attempts,
                    successes = excluded.successes
                """)) {
            ps.setString(1, goalKeyword);
            ps.setString(2, actionName);
            ps.setInt(3, attempts);
            ps.setInt(4, successes);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.fine("Failed to save planner mapping: " + e.getMessage());
        }
    }

    public Map<String, Map<String, int[]>> loadPlannerMappings() {
        Map<String, Map<String, int[]>> mappings = new LinkedHashMap<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT goal_keyword, action_name, attempts, successes FROM planner_mappings")) {
            while (rs.next()) {
                String keyword = rs.getString("goal_keyword");
                String action = rs.getString("action_name");
                int att = rs.getInt("attempts");
                int succ = rs.getInt("successes");
                mappings.computeIfAbsent(keyword, k -> new LinkedHashMap<>())
                        .put(action, new int[]{att, succ});
            }
        } catch (SQLException e) {
            LOG.fine("Failed to load planner mappings: " + e.getMessage());
        }
        return mappings;
    }

    // ── Evolution-History ──────────────────────────────────────────

    public void recordEvolution(String moduleName, boolean accepted,
                                 double fitness, String message) {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO evolution_history (module_name, accepted, fitness, message, timestamp)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            ps.setString(1, moduleName);
            ps.setInt(2, accepted ? 1 : 0);
            ps.setDouble(3, fitness);
            ps.setString(4, message != null ? message.substring(0, Math.min(message.length(), 500)) : "");
            ps.setString(5, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.fine("Failed to record evolution: " + e.getMessage());
        }
    }

    // ── Stats ───────────────────────────────────────────────────────

    public int beliefCount() {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM beliefs")) {
            return rs.getInt(1);
        } catch (SQLException e) { return 0; }
    }

    public int experienceCount() {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM experiences")) {
            return rs.getInt(1);
        } catch (SQLException e) { return 0; }
    }

    public int mappingCount() {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM planner_mappings")) {
            return rs.getInt(1);
        } catch (SQLException e) { return 0; }
    }

    public int evolutionHistoryCount() {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM evolution_history")) {
            return rs.getInt(1);
        } catch (SQLException e) { return 0; }
    }

    /**
     * Sprint #2-Followup (08.06.2026): Count beliefs whose source starts
     * with the given prefix. Used by SuttaIngestionService to skip
     * already-ingested sources on JVM restart.
     */
    public int countBeliefsBySourcePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) return 0;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM beliefs WHERE source LIKE ?")) {
            ps.setString(1, prefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            LOG.fine("countBeliefsBySourcePrefix failed: " + e.getMessage());
            return 0;
        }
    }

    // ── Conversation (Phase 2) ────────────────────────────────────

    public record ChatMessage(String sessionId, String role, String content, String timestamp) {}

    public void saveConversationMessage(String sessionId, String role, String content) {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO conversation_messages (session_id, role, content, timestamp)
                VALUES (?, ?, ?, ?)
                """)) {
            ps.setString(1, sessionId);
            ps.setString(2, role);
            ps.setString(3, content);
            ps.setString(4, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.fine("Failed to save conversation message: " + e.getMessage());
        }
    }

    public List<ChatMessage> loadConversation(String sessionId, int limit) {
        List<ChatMessage> messages = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT session_id, role, content, timestamp FROM conversation_messages " +
                "WHERE session_id = ? ORDER BY timestamp ASC LIMIT ?")) {
            ps.setString(1, sessionId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    messages.add(new ChatMessage(
                            rs.getString("session_id"),
                            rs.getString("role"),
                            rs.getString("content"),
                            rs.getString("timestamp")));
                }
            }
        } catch (SQLException e) {
            LOG.fine("Failed to load conversation: " + e.getMessage());
        }
        return messages;
    }

    /**
     * Summarize conversation history for context injection.
     * Uses primacy/recency windowing to avoid "lost in the middle" (Huyen Kap.6):
     * keeps first 2 exchanges (primacy) + last N exchanges (recency),
     * marks omitted middle messages.
     */
    public String conversationSummary(String sessionId, int maxMessages) {
        // Load extra messages to have headroom for windowing
        List<ChatMessage> msgs = loadConversation(sessionId, maxMessages * 2);
        if (msgs.isEmpty()) return "(new conversation)";

        // Short conversations: no windowing needed
        if (msgs.size() <= maxMessages) {
            return formatMessages(msgs);
        }

        // Primacy/recency windowing: keep first 4 + last maxMessages
        int keepHead = Math.min(4, msgs.size() / 3);
        int keepTail = Math.min(maxMessages, msgs.size() - keepHead);
        int omitted = msgs.size() - keepHead - keepTail;

        List<ChatMessage> windowed = new ArrayList<>();
        for (int i = 0; i < keepHead; i++) windowed.add(msgs.get(i));
        if (omitted > 0) {
            windowed.add(new ChatMessage(sessionId, "system",
                    "[" + omitted + " earlier messages omitted to stay in context window]",
                    Instant.now().toString()));
        }
        for (int i = msgs.size() - keepTail; i < msgs.size(); i++) windowed.add(msgs.get(i));

        return formatMessages(windowed);
    }

    /** Format chat messages into a compact string for context injection. */
    private String formatMessages(List<ChatMessage> msgs) {
        StringBuilder sb = new StringBuilder();
        for (var msg : msgs) {
            sb.append(msg.role()).append(": ").append(msg.content());
            if (msg.content().length() > 120) {
                sb.setLength(sb.length() - msg.content().length() + 120);
                sb.append("...");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    public List<String> listConversationSessions() {
        List<String> sessions = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT DISTINCT session_id FROM conversation_messages ORDER BY MAX(timestamp) DESC")) {
            while (rs.next()) {
                sessions.add(rs.getString(1));
            }
        } catch (SQLException e) {
            LOG.fine("Failed to list conversation sessions: " + e.getMessage());
        }
        return sessions;
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }

    public Connection connection() { return conn; }
}
