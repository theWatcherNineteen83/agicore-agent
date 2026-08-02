package de.metis.kernel.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * DuckDB-basierte Analytics-Datenbank für Metis — OLAP (Online Analytical Processing).
 * <p>
 * Komplementär zu {@link H2Datastore} (OLTP). DuckDB excels bei:
 * <ul>
 *   <li>Zeitreihen-Aggregationen (Metrics-Trends über Tage/Wochen)</li>
 *   <li>Planner-Statistiken (Action-Verteilung, Erfolgsraten pro Modell)</li>
 *   <li>Eval-History (Gate-Entscheidungen, Score-Verläufe)</li>
 *   <li>Goal-Completion-Raten über Zeit</li>
 * </ul>
 * <p>
 * Native C++-Engine via duckdb_jdbc, Datei-basiert.
 * Kein separater Server — embedded wie SQLite/H2.
 */
public class DuckDBAnalyticsService implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(DuckDBAnalyticsService.class.getName());

    private final Connection conn;
    private final Path dbPath;

    public DuckDBAnalyticsService(Path dbDir) throws SQLException {
        try {
            Files.createDirectories(dbDir);
        } catch (Exception ignored) {}

        this.dbPath = dbDir.resolve("analytics.duckdb");
        String url = "jdbc:duckdb:" + dbPath.toAbsolutePath();
        boolean exists = Files.exists(dbPath);

        this.conn = DriverManager.getConnection(url);
        initSchema();
        LOG.info("DuckDBAnalytics: " + dbPath + (exists ? " (existing)" : " (new)"));
    }

    private void initSchema() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // Metrics time-series (high-cardinality: name × timestamp)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS metrics_ts (
                    metric_name VARCHAR NOT NULL,
                    metric_value DOUBLE NOT NULL,
                    tags VARCHAR,
                    recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_mts_name_time ON metrics_ts(metric_name, recorded_at)");

            // Planner action stats (per-tick action distribution)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS planner_stats (
                    tick INTEGER NOT NULL,
                    action_name VARCHAR NOT NULL,
                    call_count INTEGER NOT NULL DEFAULT 0,
                    error_count INTEGER NOT NULL DEFAULT 0,
                    recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_ps_tick ON planner_stats(tick)");

            // Eval gate history
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS eval_history (
                    id BIGINT PRIMARY KEY,
                    tier VARCHAR NOT NULL,
                    task_name VARCHAR NOT NULL,
                    score DOUBLE NOT NULL,
                    gate VARCHAR NOT NULL,
                    details VARCHAR,
                    recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_eval_tier ON eval_history(tier, recorded_at)");

            // Goal completion timeline (for throughput analysis)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS goal_completions (
                    goal_id VARCHAR NOT NULL,
                    horizon VARCHAR,
                    category VARCHAR,
                    ticks_to_complete INTEGER,
                    completed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_gc_date ON goal_completions(completed_at)");

            // Agent snapshots (daily state dumps for long-term trend analysis)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS daily_snapshots (
                    snapshot_date DATE PRIMARY KEY,
                    belief_count INTEGER,
                    goal_count INTEGER,
                    active_goals INTEGER,
                    done_goals INTEGER,
                    planner_success_rate DOUBLE,
                    avg_latency_ms DOUBLE,
                    confirmed_hypotheses INTEGER,
                    recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);

            LOG.info("DuckDBAnalytics schema initialized (5 tables)");
        }
    }

    // ── Metrics ──────────────────────────────────────────────────────

    public void recordMetric(String name, double value, String tags) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO metrics_ts (metric_name, metric_value, tags) VALUES (?, ?, ?)")) {
            ps.setString(1, name);
            ps.setDouble(2, value);
            ps.setString(3, tags != null ? tags : "");
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.fine("DuckDB recordMetric: " + e.getMessage());
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
            LOG.warning("DuckDB bulk metrics: " + ex.getMessage());
            try { conn.rollback(); conn.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    // ── Planner Stats ────────────────────────────────────────────────

    public void recordPlannerStats(int tick, Map<String, Integer> actionCallCounts,
                                   Map<String, Integer> actionErrorCounts) {
        try {
            conn.setAutoCommit(false);
            for (var entry : actionCallCounts.entrySet()) {
                String action = entry.getKey();
                int calls = entry.getValue();
                int errors = actionErrorCounts.getOrDefault(action, 0);
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
            LOG.fine("DuckDB plannerStats: " + e.getMessage());
            try { conn.rollback(); conn.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    // ── Eval History ─────────────────────────────────────────────────

    public void recordEval(long id, String tier, String taskName, double score,
                           String gate, String details) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO eval_history (id, tier, task_name, score, gate, details) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, id);
            ps.setString(2, tier);
            ps.setString(3, taskName);
            ps.setDouble(4, score);
            ps.setString(5, gate);
            ps.setString(6, details != null ? details : "");
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.fine("DuckDB recordEval: " + e.getMessage());
        }
    }

    // ── Goal Completions ─────────────────────────────────────────────

    public void recordGoalCompletion(String goalId, String horizon, String category, int ticksToComplete) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO goal_completions (goal_id, horizon, category, ticks_to_complete) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, goalId);
            ps.setString(2, horizon);
            ps.setString(3, category);
            ps.setInt(4, ticksToComplete);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.fine("DuckDB goalCompletion: " + e.getMessage());
        }
    }

    // ── Daily Snapshot ───────────────────────────────────────────────

    public void takeDailySnapshot(int beliefCount, int goalCount, int activeGoals,
                                  int doneGoals, double plannerSuccessRate,
                                  double avgLatencyMs, int confirmedHypotheses) {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO daily_snapshots (snapshot_date, belief_count, goal_count,
                    active_goals, done_goals, planner_success_rate, avg_latency_ms,
                    confirmed_hypotheses)
                VALUES (CURRENT_DATE, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (snapshot_date) DO UPDATE SET
                    belief_count = excluded.belief_count,
                    goal_count = excluded.goal_count,
                    active_goals = excluded.active_goals,
                    done_goals = excluded.done_goals,
                    planner_success_rate = excluded.planner_success_rate,
                    avg_latency_ms = excluded.avg_latency_ms,
                    confirmed_hypotheses = excluded.confirmed_hypotheses
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
            LOG.fine("DuckDB dailySnapshot: " + e.getMessage());
        }
    }

    // ── Analytics Queries ────────────────────────────────────────────

    /** Top-N actions by error rate over last N hours. */
    public List<Map<String, Object>> topErrorActions(int hours, int limit) {
        List<Map<String, Object>> results = new ArrayList<>();
        String sql = """
            SELECT action_name,
                   SUM(call_count) AS total_calls,
                   SUM(error_count) AS total_errors,
                   ROUND(SUM(error_count) * 100.0 / NULLIF(SUM(call_count), 0), 1) AS error_rate_pct
            FROM planner_stats
            WHERE recorded_at > CURRENT_TIMESTAMP - INTERVAL ? HOUR
            GROUP BY action_name
            HAVING SUM(call_count) > 0
            ORDER BY error_rate_pct DESC
            LIMIT ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
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
            LOG.warning("DuckDB topErrorActions: " + e.getMessage());
        }
        return results;
    }

    /** Metric trend over last N days. */
    public List<Map<String, Object>> metricTrend(String metricName, int days) {
        List<Map<String, Object>> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT recorded_at::DATE AS day,
                       AVG(metric_value) AS avg_value,
                       MIN(metric_value) AS min_value,
                       MAX(metric_value) AS max_value,
                       COUNT(*) AS samples
                FROM metrics_ts
                WHERE metric_name = ?
                  AND recorded_at > CURRENT_TIMESTAMP - INTERVAL ? DAY
                GROUP BY day
                ORDER BY day
                """)) {
            ps.setString(1, metricName);
            ps.setInt(2, days);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("day", rs.getString("day"));
                    row.put("avg", rs.getDouble("avg_value"));
                    row.put("min", rs.getDouble("min_value"));
                    row.put("max", rs.getDouble("max_value"));
                    row.put("samples", rs.getInt("samples"));
                    results.add(row);
                }
            }
        } catch (SQLException e) {
            LOG.warning("DuckDB metricTrend: " + e.getMessage());
        }
        return results;
    }

    /** Goal throughput: completions per day. */
    public List<Map<String, Object>> goalThroughput(int days) {
        List<Map<String, Object>> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT completed_at::DATE AS day,
                       COUNT(*) AS completions,
                       AVG(ticks_to_complete) AS avg_ticks,
                       COUNT(DISTINCT category) AS categories
                FROM goal_completions
                WHERE completed_at > CURRENT_TIMESTAMP - INTERVAL ? DAY
                GROUP BY day
                ORDER BY day
                """)) {
            ps.setInt(1, days);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("day", rs.getString("day"));
                    row.put("completions", rs.getInt("completions"));
                    row.put("avgTicks", rs.getDouble("avg_ticks"));
                    row.put("categories", rs.getInt("categories"));
                    results.add(row);
                }
            }
        } catch (SQLException e) {
            LOG.warning("DuckDB goalThroughput: " + e.getMessage());
        }
        return results;
    }

    // ── Status ───────────────────────────────────────────────────────

    public Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("dbType", "DuckDB");
        s.put("dbPath", dbPath.toString());
        try (Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM metrics_ts");
            s.put("metricCount", rs.next() ? rs.getInt(1) : 0);
            rs = stmt.executeQuery("SELECT COUNT(DISTINCT snapshot_date) FROM daily_snapshots");
            s.put("dailySnapshots", rs.next() ? rs.getInt(1) : 0);
            rs = stmt.executeQuery("SELECT COUNT(*) FROM planner_stats");
            s.put("plannerStatRows", rs.next() ? rs.getInt(1) : 0);
        } catch (SQLException ignored) {}
        s.put("recentErrors", topErrorActions(24, 5));
        return s;
    }

    @Override
    public void close() {
        try {
            conn.close();
            LOG.info("DuckDBAnalytics closed");
        } catch (SQLException e) {
            LOG.warning("DuckDB close: " + e.getMessage());
        }
    }
}
