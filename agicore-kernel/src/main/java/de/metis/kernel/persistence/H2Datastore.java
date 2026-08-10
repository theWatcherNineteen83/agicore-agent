package de.metis.kernel.persistence;

import de.metis.kernel.goal.Goal;
import de.metis.kernel.memory.Experience;
import de.metis.kernel.world.Belief;
import de.metis.kernel.world.CausalHypothesis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * H2-basierte Hauptdatenbank für Metis — das "Gehirn".
 * <p>
 * PostgreSQL-Kompatibilitätsmodus, Pure Java Embedded, Datei-basiert.
 * Ersetzt NICHT {@link KnowledgeStore} (SQLite) — läuft parallel.
 * H2 ist für OLTP (viele kleine Transaktionen): Beliefs, Goals, Hypotheses, Metrics.
 * <p>
 * Tabellen:
 * <ul>
 *   <li>beliefs — Kern-Wissen (Statement, Confidence, Source, Evidence)</li>
 *   <li>goals — Alle Goals mit Status, Horizon, Parent-Child-Beziehungen</li>
 *   <li>hypotheses — Kausale Hypothesen (Phase 10)</li>
 *   <li>metrics — Zeitreihen-Metriken (Planner-Stats, Eval-Scores)</li>
 *   <li>evolution — Mutations-Historie</li>
 *   <li>experiences — Erfahrungen (Goal → Action → Outcome)</li>
 *   <li>agent_snapshots — Periodische Zustands-Checkpoints</li>
 * </ul>
 */
public class H2Datastore implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(H2Datastore.class.getName());

    private final Connection conn;
    private final Path dbDir;

    /**
     * Open/create the H2 datastore at {@code dbDir/metis} (H2 appends .mv.db).
     */
    public H2Datastore(Path dbDir) throws SQLException {
        this.dbDir = dbDir;
        try {
            Files.createDirectories(dbDir);
        } catch (Exception ignored) {}

        Path dbFile = dbDir.resolve("metis");
        String url = "jdbc:h2:file:" + dbFile.toAbsolutePath()
                + ";MODE=PostgreSQL"
                + ";AUTO_RECONNECT=TRUE";

        boolean exists = Files.exists(dbDir.resolve("metis.mv.db"));
        this.conn = DriverManager.getConnection(url, "sa", "");
        initSchema();
        LOG.info("H2Datastore: " + dbFile + (exists ? " (existing)" : " (new)") + " [PostgreSQL mode]");
    }

    // ── Schema ───────────────────────────────────────────────────────

    private void initSchema() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // Beliefs
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS beliefs (
                    id SERIAL PRIMARY KEY,
                    statement VARCHAR(4096) NOT NULL UNIQUE,
                    confidence DOUBLE PRECISION NOT NULL DEFAULT 0.5,
                    source VARCHAR(256) NOT NULL DEFAULT 'unknown',
                    evidence INT NOT NULL DEFAULT 1,
                    category VARCHAR(128),
                    tags VARCHAR(1024),
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_beliefs_conf ON beliefs(confidence DESC)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_beliefs_source ON beliefs(source)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_beliefs_category ON beliefs(category)");

            // Goals (hierarchical)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS goals (
                    id VARCHAR(36) PRIMARY KEY,
                    parent_id VARCHAR(36),
                    description VARCHAR(4096) NOT NULL,
                    category VARCHAR(128),
                    horizon VARCHAR(20) NOT NULL DEFAULT 'OPERATIONAL',
                    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
                    priority INT NOT NULL DEFAULT 50,
                    expected_reward DOUBLE PRECISION NOT NULL DEFAULT 0.5,
                    resource_cost INT NOT NULL DEFAULT 1,
                    service_class VARCHAR(20) NOT NULL DEFAULT 'STANDARD',
                    resource_type VARCHAR(20) NOT NULL DEFAULT 'LIGHT',
                    progress DOUBLE PRECISION NOT NULL DEFAULT 0.0,
                    child_count INT NOT NULL DEFAULT 0,
                    done_count INT NOT NULL DEFAULT 0,
                    deadline TIMESTAMP,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    completed_at TIMESTAMP,
                    FOREIGN KEY (parent_id) REFERENCES goals(id) ON DELETE SET NULL
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_goals_status ON goals(status, horizon)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_goals_parent ON goals(parent_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_goals_created ON goals(created_at DESC)");

            // Hypotheses (Phase 10)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS hypotheses (
                    id VARCHAR(36) PRIMARY KEY,
                    cause VARCHAR(1024) NOT NULL,
                    condition_text VARCHAR(1024),
                    effect VARCHAR(1024) NOT NULL,
                    predicted_direction VARCHAR(10) NOT NULL DEFAULT 'UP',
                    predicted_magnitude DOUBLE PRECISION NOT NULL DEFAULT 0.5,
                    rationale VARCHAR(4096),
                    planned_action VARCHAR(1024),
                    status VARCHAR(20) NOT NULL DEFAULT 'PROPOSED',
                    observed_direction VARCHAR(10),
                    observed_magnitude DOUBLE PRECISION,
                    result_note VARCHAR(4096),
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    tested_at TIMESTAMP
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_hypo_status ON hypotheses(status)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_hypo_cause ON hypotheses(cause, effect)");

            // Metrics (time-series)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS metrics (
                    id SERIAL PRIMARY KEY,
                    metric_name VARCHAR(128) NOT NULL,
                    metric_value DOUBLE PRECISION NOT NULL,
                    tags VARCHAR(1024),
                    recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_metrics_name_time ON metrics(metric_name, recorded_at DESC)");

            // Evolution history
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS evolution (
                    id SERIAL PRIMARY KEY,
                    module_name VARCHAR(256) NOT NULL,
                    accepted BOOLEAN NOT NULL DEFAULT FALSE,
                    fitness DOUBLE PRECISION,
                    message VARCHAR(4096),
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_evolution_time ON evolution(created_at DESC)");

            // Experiences
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS experiences (
                    id SERIAL PRIMARY KEY,
                    goal_description VARCHAR(4096) NOT NULL,
                    action_name VARCHAR(256) NOT NULL,
                    success BOOLEAN NOT NULL DEFAULT FALSE,
                    prediction_error DOUBLE PRECISION NOT NULL DEFAULT 0.5,
                    salience DOUBLE PRECISION NOT NULL DEFAULT 0.5,
                    body VARCHAR(8192),
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_exp_action ON experiences(action_name)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_exp_time ON experiences(created_at DESC)");

            // Agent snapshots (periodic checkpoints)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS agent_snapshots (
                    id SERIAL PRIMARY KEY,
                    snapshot_type VARCHAR(64) NOT NULL,
                    payload VARCHAR(32768) NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_snap_type_time ON agent_snapshots(snapshot_type, created_at DESC)");

            // Phase 14c: Analytics tables (OLAP in H2 — Window Functions, CTEs)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS metrics_ts (
                    id SERIAL PRIMARY KEY,
                    metric_name VARCHAR(128) NOT NULL,
                    metric_value DOUBLE PRECISION NOT NULL,
                    tags VARCHAR(1024),
                    recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_mts_name_time ON metrics_ts(metric_name, recorded_at)");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS planner_stats (
                    id SERIAL PRIMARY KEY,
                    tick INTEGER NOT NULL,
                    action_name VARCHAR(128) NOT NULL,
                    call_count INTEGER NOT NULL DEFAULT 0,
                    error_count INTEGER NOT NULL DEFAULT 0,
                    recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_ps_tick ON planner_stats(tick)");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS eval_history (
                    id BIGINT PRIMARY KEY,
                    tier VARCHAR(32) NOT NULL,
                    task_name VARCHAR(256) NOT NULL,
                    score DOUBLE PRECISION NOT NULL,
                    gate VARCHAR(16) NOT NULL,
                    details VARCHAR(4096),
                    recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_eval_time ON eval_history(recorded_at)");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS goal_completions (
                    id SERIAL PRIMARY KEY,
                    goal_id VARCHAR(36) NOT NULL,
                    horizon VARCHAR(20),
                    category VARCHAR(128),
                    ticks_to_complete INTEGER,
                    completed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_gc_date ON goal_completions(completed_at)");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS daily_snapshots (
                    snapshot_date DATE PRIMARY KEY,
                    belief_count INTEGER,
                    goal_count INTEGER,
                    active_goals INTEGER,
                    done_goals INTEGER,
                    planner_success_rate DOUBLE PRECISION,
                    avg_latency_ms DOUBLE PRECISION,
                    confirmed_hypotheses INTEGER,
                    recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);

            LOG.info("H2Datastore schema initialized (7 operational + 5 analytics = 12 tables)");
        }
    }

    // ── Beliefs ──────────────────────────────────────────────────────

    public void saveBelief(Belief belief) {
        try (PreparedStatement ps = conn.prepareStatement("""
                MERGE INTO beliefs (statement, confidence, source, evidence, updated_at)
                KEY (statement) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                """)) {
            ps.setString(1, belief.statement());
            ps.setDouble(2, belief.confidence());
            ps.setString(3, belief.source());
            ps.setInt(4, belief.evidence());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.fine("H2 saveBelief failed: " + e.getMessage());
        }
    }

    public void saveBeliefs(Collection<Belief> beliefs) {
        try {
            conn.setAutoCommit(false);
            for (Belief b : beliefs) saveBelief(b);
            conn.commit();
            conn.setAutoCommit(true);
        } catch (SQLException e) {
            LOG.warning("H2 bulk saveBeliefs failed: " + e.getMessage());
            try { conn.rollback(); conn.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    public List<Belief> loadBeliefs() {
        return loadBeliefs(null, 0);
    }

    public List<Belief> loadBeliefs(String category, int limit) {
        List<Belief> list = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT statement, confidence, source, evidence FROM beliefs");
        if (category != null && !category.isBlank()) {
            sql.append(" WHERE category = ?");
        }
        sql.append(" ORDER BY confidence DESC");
        if (limit > 0) sql.append(" LIMIT ").append(limit);

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            if (category != null && !category.isBlank()) {
                ps.setString(1, category);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new Belief(
                            rs.getString("statement"),
                            rs.getDouble("confidence"),
                            rs.getString("source")));
                }
            }
        } catch (SQLException e) {
            LOG.warning("H2 loadBeliefs failed: " + e.getMessage());
        }
        return list;
    }

    public int countBeliefs() {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM beliefs")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            return -1;
        }
    }

    public void deleteWeakBeliefs(double threshold) {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM beliefs WHERE confidence < ?")) {
            ps.setDouble(1, threshold);
            int n = ps.executeUpdate();
            if (n > 0) LOG.info("H2: deleted " + n + " weak beliefs (<" + threshold + ")");
        } catch (SQLException e) {
            LOG.fine("H2 deleteWeakBeliefs: " + e.getMessage());
        }
    }

    // ── Goals ────────────────────────────────────────────────────────

    public void saveGoal(Goal goal, String horizon, String status, UUID parentId,
                         double progress, int childCount, int doneCount) {
        try (PreparedStatement ps = conn.prepareStatement("""
                MERGE INTO goals (id, parent_id, description, category, horizon, status,
                    priority, expected_reward, resource_cost, service_class, resource_type,
                    progress, child_count, done_count, deadline, completed_at)
                KEY (id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setString(1, goal.id().toString());
            ps.setString(2, parentId != null ? parentId.toString() : null);
            ps.setString(3, goal.description());
            ps.setString(4, goal.category());
            ps.setString(5, horizon != null ? horizon : "OPERATIONAL");
            ps.setString(6, status != null ? status : "ACTIVE");
            ps.setInt(7, goal.priority());
            ps.setDouble(8, goal.expectedReward());
            ps.setInt(9, goal.resourceCost());
            ps.setString(10, goal.serviceClass().name());
            ps.setString(11, goal.resourceType().name());
            ps.setDouble(12, progress);
            ps.setInt(13, childCount);
            ps.setInt(14, doneCount);
            ps.setTimestamp(15, goal.deadline() != null
                    ? Timestamp.from(goal.deadline()) : null);
            ps.setTimestamp(16, "DONE".equals(status) ? Timestamp.from(Instant.now()) : null);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.fine("H2 saveGoal failed: " + e.getMessage());
        }
    }

    public List<Map<String, Object>> loadGoals(String statusFilter, String horizonFilter, int limit) {
        List<Map<String, Object>> list = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT id, parent_id, description, category, horizon, status, "
                + "priority, progress, child_count, done_count, created_at, completed_at "
                + "FROM goals WHERE 1=1");
        List<String> params = new ArrayList<>();
        if (statusFilter != null) {
            sql.append(" AND status = ?");
            params.add(statusFilter);
        }
        if (horizonFilter != null) {
            sql.append(" AND horizon = ?");
            params.add(horizonFilter);
        }
        sql.append(" ORDER BY created_at DESC");
        if (limit > 0) sql.append(" LIMIT ").append(limit);

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString("id"));
                    row.put("parentId", rs.getString("parent_id"));
                    row.put("description", rs.getString("description"));
                    row.put("category", rs.getString("category"));
                    row.put("horizon", rs.getString("horizon"));
                    row.put("status", rs.getString("status"));
                    row.put("priority", rs.getInt("priority"));
                    row.put("progress", rs.getDouble("progress"));
                    row.put("childCount", rs.getInt("child_count"));
                    row.put("doneCount", rs.getInt("done_count"));
                    row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    Timestamp completed = rs.getTimestamp("completed_at");
                    row.put("completedAt", completed != null ? completed.toInstant().toString() : null);
                    list.add(row);
                }
            }
        } catch (SQLException e) {
            LOG.warning("H2 loadGoals failed: " + e.getMessage());
        }
        return list;
    }

    public int countGoalsByStatus(String status) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM goals WHERE status = ?")) {
            ps.setString(1, status);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            return -1;
        }
    }

    // ── Hypotheses ───────────────────────────────────────────────────

    public void saveHypothesis(CausalHypothesis h) {
        try (PreparedStatement ps = conn.prepareStatement("""
                MERGE INTO hypotheses (id, cause, condition_text, effect,
                    predicted_direction, predicted_magnitude, rationale,
                    planned_action, status, observed_direction,
                    observed_magnitude, result_note, tested_at)
                KEY (id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setString(1, h.id().toString());
            ps.setString(2, h.cause());
            ps.setString(3, h.condition());
            ps.setString(4, h.effect());
            ps.setString(5, h.predictedDirection().name());
            ps.setDouble(6, h.predictedMagnitude());
            ps.setString(7, h.rationale());
            ps.setString(8, h.plannedAction());
            ps.setString(9, h.status().name());
            ps.setString(10, h.observedDirection() != null ? h.observedDirection().name() : null);
            ps.setDouble(11, h.observedMagnitude());
            ps.setString(12, h.resultNote());
            ps.setTimestamp(13, h.testedAt() != null ? Timestamp.from(h.testedAt()) : null);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.fine("H2 saveHypothesis failed: " + e.getMessage());
        }
    }

    public Map<String, Long> countHypothesesByStatus() {
        Map<String, Long> map = new LinkedHashMap<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT status, COUNT(*) as cnt FROM hypotheses GROUP BY status")) {
            while (rs.next()) {
                map.put(rs.getString("status"), rs.getLong("cnt"));
            }
        } catch (SQLException e) {
            LOG.warning("H2 countHypotheses: " + e.getMessage());
        }
        return map;
    }

    // ── Metrics ──────────────────────────────────────────────────────

    public void recordMetric(String name, double value, String tags) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO metrics (metric_name, metric_value, tags) VALUES (?, ?, ?)")) {
            ps.setString(1, name);
            ps.setDouble(2, value);
            ps.setString(3, tags);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.fine("H2 recordMetric failed: " + e.getMessage());
        }
    }

    public void recordMetrics(Map<String, Double> metrics, String tags) {
        try {
            conn.setAutoCommit(false);
            for (var e : metrics.entrySet()) {
                recordMetric(e.getKey(), e.getValue(), tags);
            }
            conn.commit();
            conn.setAutoCommit(true);
        } catch (SQLException ex) {
            LOG.warning("H2 bulk recordMetrics failed: " + ex.getMessage());
            try { conn.rollback(); conn.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    // ── Migration: SQLite KnowledgeStore → H2 ────────────────────────

    /**
     * Import all beliefs from the existing SQLite {@link KnowledgeStore}.
     * @return number of beliefs imported
     */
    public int importFromKnowledgeStore(KnowledgeStore ks) {
        if (ks == null) return 0;
        List<Belief> beliefs = ks.loadBeliefs();
        int before = countBeliefs();
        saveBeliefs(beliefs);
        int after = countBeliefs();
        int imported = after - before;
        LOG.info("H2 import from KnowledgeStore: " + imported + " beliefs ("
                + before + " → " + after + ")");
        return imported;
    }

    // ── Analytics (Phase 14c) — OLAP in H2 ──────────────────────────

    /** Record a metric for trend analysis. */
    public void recordAnalyticsMetric(String name, double value, String tags) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO metrics_ts (metric_name, metric_value, tags) VALUES (?, ?, ?)")) {
            ps.setString(1, name);
            ps.setDouble(2, value);
            ps.setString(3, tags != null ? tags : "");
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.fine("H2 analytics metric: " + e.getMessage());
        }
    }

    /** Bulk metric recording. */
    public void recordAnalyticsMetrics(Map<String, Double> metrics, String tags) {
        try {
            conn.setAutoCommit(false);
            for (var e : metrics.entrySet()) {
                recordAnalyticsMetric(e.getKey(), e.getValue(), tags);
            }
            conn.commit();
            conn.setAutoCommit(true);
        } catch (SQLException ex) {
            LOG.warning("H2 bulk analytics: " + ex.getMessage());
            try { conn.rollback(); conn.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    /** Record planner action stats for error-trend analysis. */
    public void recordPlannerStats(int tick, Map<String, Integer> callCounts,
                                   Map<String, Integer> errorCounts) {
        try {
            conn.setAutoCommit(false);
            for (var entry : callCounts.entrySet()) {
                String action = entry.getKey();
                int calls = entry.getValue();
                int errors = errorCounts.getOrDefault(action, 0);
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO planner_stats (tick, action_name, call_count, error_count) VALUES (?, ?, ?, ?)")) {
                    ps.setInt(1, tick);
                    ps.setString(2, action);
                    ps.setInt(3, calls);
                    ps.setInt(4, errors);
                    ps.executeUpdate();
                }
            }
            conn.commit();
            conn.setAutoCommit(true);
        } catch (SQLException e) {
            LOG.fine("H2 plannerStats: " + e.getMessage());
            try { conn.rollback(); conn.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    /** Top-N actions by error rate (last N hours). */
    public List<Map<String, Object>> topErrorActions(int hours, int limit) {
        List<Map<String, Object>> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT action_name,
                       SUM(call_count) AS total_calls,
                       SUM(error_count) AS total_errors,
                       ROUND(SUM(error_count) * 100.0 / NULLIF(SUM(call_count), 0), 1) AS error_rate_pct
                FROM planner_stats
                WHERE recorded_at > DATEADD('HOUR', -?, CURRENT_TIMESTAMP)
                GROUP BY action_name
                HAVING SUM(call_count) > 0
                ORDER BY error_rate_pct DESC
                LIMIT ?
                """)) {
            ps.setInt(1, hours);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("action", rs.getString("action_name"));
                    row.put("calls", rs.getLong("total_calls"));
                    row.put("errors", rs.getLong("total_errors"));
                    row.put("errorRate", rs.getDouble("error_rate_pct"));
                    results.add(row);
                }
            }
        } catch (SQLException e) {
            LOG.warning("H2 topErrorActions: " + e.getMessage());
        }
        return results;
    }

    /** Daily state snapshot for long-term trend analysis. */
    public void takeDailySnapshot(int beliefCount, int goalCount, int activeGoals,
                                  int doneGoals, double plannerSuccessRate,
                                  double avgLatencyMs, int confirmedHypotheses) {
        try (PreparedStatement ps = conn.prepareStatement("""
                MERGE INTO daily_snapshots (snapshot_date, belief_count, goal_count,
                    active_goals, done_goals, planner_success_rate, avg_latency_ms,
                    confirmed_hypotheses)
                KEY (snapshot_date) VALUES (CURRENT_DATE, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setInt(1, beliefCount);
            ps.setInt(2, goalCount);
            ps.setInt(3, activeGoals);
            ps.setInt(4, doneGoals);
            ps.setDouble(5, plannerSuccessRate);
            ps.setDouble(6, avgLatencyMs);
            ps.setInt(7, confirmedHypotheses);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.fine("H2 dailySnapshot: " + e.getMessage());
        }
    }

    // ── Status / Health ──────────────────────────────────────────────

    public Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("dbType", "H2");
        s.put("mode", "PostgreSQL");
        s.put("dbDir", dbDir.toString());
        s.put("beliefCount", countBeliefs());
        s.put("goalCount", countGoalsByStatus(null));
        s.put("activeGoals", countGoalsByStatus("ACTIVE"));
        s.put("doneGoals", countGoalsByStatus("DONE"));
        s.put("hypothesisCounts", countHypothesesByStatus());
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM metrics WHERE recorded_at > DATEADD('HOUR', -24, CURRENT_TIMESTAMP)")) {
            s.put("metrics24h", rs.next() ? rs.getInt(1) : 0);
        } catch (SQLException ignored) {}
        // Analytics stats
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM metrics_ts")) {
            s.put("analyticsMetrics", rs.next() ? rs.getInt(1) : 0);
        } catch (SQLException ignored) {}
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(DISTINCT snapshot_date) FROM daily_snapshots")) {
            s.put("dailySnapshots", rs.next() ? rs.getInt(1) : 0);
        } catch (SQLException ignored) {}
        s.put("recentErrors", topErrorActions(24, 5));
        return s;
    }

    @Override
    public void close() {
        try {
            // H2 SHUTDOWN compactiert die DB
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SHUTDOWN COMPACT");
            }
            conn.close();
            LOG.info("H2Datastore closed (compact)");
        } catch (SQLException e) {
            LOG.warning("H2 close failed: " + e.getMessage());
        }
    }

    /** For admin operations (VACUUM, backup, etc.) */
    public Connection rawConnection() { return conn; }
}
