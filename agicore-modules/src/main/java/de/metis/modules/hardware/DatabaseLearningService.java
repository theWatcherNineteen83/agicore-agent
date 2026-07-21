package de.metis.modules.hardware;

import de.metis.kernel.world.WorldModel;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Phase 14 — Metis lernt Datenbanken via SQLite+JDBC.
 *
 * <p>Curriculum: 14 SQL-Lektionen (DDL, DML, Joins, Indexes, FTS5, Transactions).
 * Analog zu {@link JavaLearningService}: Metis fuehrt autonom SQL-Uebungen aus
 * und speichert Ergebnisse als Beliefs.
 *
 * <p>Verwendet {@code sqlite-jdbc} (embedded, zero-setup).
 * Gleiche JDBC-API wie MariaDB/PostgreSQL — Konzepte sind uebertragbar.
 */
public class DatabaseLearningService {

    private static final Logger LOG = Logger.getLogger(DatabaseLearningService.class.getName());

    private final WorldModel worldModel;
    private final Path dbDir;
    private final Set<String> completedExercises = new HashSet<>();

    private int commandsTried = 0;
    private int commandsSucceeded = 0;

    public DatabaseLearningService(WorldModel worldModel) {
        this(worldModel, Path.of("/home/prometheus/metis/sql-sandbox"));
    }

    public DatabaseLearningService(WorldModel worldModel, Path dbDir) {
        this.worldModel = worldModel;
        this.dbDir = dbDir;
        try {
            Files.createDirectories(dbDir);
        } catch (IOException ignored) {}
        loadCompletedExercises();
    }

    /**
     * Generate and execute the next SQL exercise in the curriculum.
     * Each call advances one lesson.
     *
     * @return exercise name if successful, null if failed
     */
    public String generateAndRunExercise() {
        int nextLesson = completedExercises.size() + 1;
        String[] topic = getTopic(nextLesson);
        if (topic == null) {
            LOG.info("All 14 SQL exercises completed!");
            return null;
        }

        String tag = topic[0];   // e.g. "SQL01"
        String title = topic[1];  // e.g. "CREATE TABLE"
        String dbName = tag + "_" + title.replace(' ', '_') + ".db";
        Path dbFile = dbDir.resolve(dbName);

        commandsTried++;

        try {
            // Delete old DB for fresh exercise (except for lessons that build on prior)
            Files.deleteIfExists(dbFile);

            String jdbcUrl = "jdbc:sqlite:" + dbFile.toAbsolutePath();
            try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
                conn.setAutoCommit(false); // Transactional

                int statementsRun = 0;
                String summary;

                switch (tag) {
                    case "SQL01" -> {
                        execute(conn, "CREATE TABLE IF NOT EXISTS metis_log (" +
                                "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                                "  timestamp TEXT NOT NULL DEFAULT (datetime('now'))," +
                                "  level TEXT CHECK(level IN ('INFO','WARN','ERROR')) NOT NULL," +
                                "  message TEXT NOT NULL" +
                                ")");
                        execute(conn, "INSERT INTO metis_log (level, message) VALUES ('INFO', 'Metis SQL-Lektion 1: CREATE TABLE')");
                        execute(conn, "INSERT INTO metis_log (level, message) VALUES ('WARN', 'Erste Warnung von Metis')");
                        statementsRun = 3;
                        summary = "CREATE TABLE metis_log + 2 INSERTs";
                    }
                    case "SQL02" -> {
                        setupMetisLog(conn);
                        execute(conn, "SELECT * FROM metis_log ORDER BY id");
                        ResultSet rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM metis_log");
                        int count = rs.next() ? rs.getInt(1) : 0;
                        rs.close();
                        execute(conn, "SELECT level, COUNT(*) AS cnt FROM metis_log GROUP BY level");
                        execute(conn, "SELECT * FROM metis_log WHERE level = 'WARN'");
                        statementsRun = 3;
                        summary = "SELECT + COUNT + GROUP BY — " + count + " rows";
                    }
                    case "SQL03" -> {
                        setupMetisLog(conn);
                        execute(conn, "ALTER TABLE metis_log ADD COLUMN source TEXT DEFAULT 'metis'");
                        execute(conn, "UPDATE metis_log SET source = 'java-learner' WHERE id = 1");
                        execute(conn, "DELETE FROM metis_log WHERE level = 'WARN'");
                        statementsRun = 3;
                        summary = "ALTER TABLE + UPDATE + DELETE";
                    }
                    case "SQL04" -> {
                        // Create multi-table schema
                        execute(conn, "CREATE TABLE IF NOT EXISTS beliefs (" +
                                "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                                "  statement TEXT NOT NULL," +
                                "  confidence REAL CHECK(confidence BETWEEN 0 AND 1) DEFAULT 0.5," +
                                "  source TEXT," +
                                "  created_at TEXT DEFAULT (datetime('now'))" +
                                ")");
                        execute(conn, "CREATE TABLE IF NOT EXISTS belief_tags (" +
                                "  belief_id INTEGER REFERENCES beliefs(id)," +
                                "  tag TEXT NOT NULL," +
                                "  PRIMARY KEY (belief_id, tag)" +
                                ")");
                        execute(conn, "INSERT INTO beliefs (statement, confidence, source) VALUES " +
                                "('SQLite ist embedded', 0.99, 'ex1')," +
                                "('JDBC ist universell', 0.95, 'ex1')," +
                                "('FTS5 = Volltextsuche', 0.92, 'ex2')");
                        execute(conn, "INSERT INTO belief_tags VALUES (1, 'sql'), (2, 'java'), (2, 'jdbc'), (3, 'fts')");
                        statementsRun = 4;
                        summary = "Multi-Table Schema: beliefs + belief_tags";
                    }
                    case "SQL05" -> {
                        setupBeliefsSchema(conn);
                        execute(conn, "SELECT b.statement, b.confidence, GROUP_CONCAT(t.tag, ', ') AS tags " +
                                "FROM beliefs b LEFT JOIN belief_tags t ON b.id = t.belief_id " +
                                "GROUP BY b.id ORDER BY b.confidence DESC");
                        execute(conn, "SELECT b.statement, b.confidence " +
                                "FROM beliefs b INNER JOIN belief_tags t ON b.id = t.belief_id " +
                                "WHERE t.tag = 'java'");
                        statementsRun = 2;
                        summary = "LEFT JOIN + INNER JOIN + GROUP_CONCAT";
                    }
                    case "SQL06" -> {
                        setupBeliefsSchema(conn);
                        execute(conn, "SELECT tag, COUNT(*) AS belief_count, ROUND(AVG(confidence), 3) AS avg_conf " +
                                "FROM beliefs b INNER JOIN belief_tags t ON b.id = t.belief_id " +
                                "GROUP BY tag HAVING COUNT(*) >= 1 ORDER BY belief_count DESC");
                        execute(conn, "SELECT source, COUNT(*) AS cnt, MIN(confidence), MAX(confidence) " +
                                "FROM beliefs GROUP BY source");
                        statementsRun = 2;
                        summary = "GROUP BY + HAVING + Aggregatfunktionen";
                    }
                    case "SQL07" -> {
                        setupBeliefsSchema(conn);
                        execute(conn, "SELECT statement, confidence FROM beliefs WHERE confidence > 0.9 " +
                                "UNION ALL SELECT '--- threshold ---', 0.9 " +
                                "ORDER BY confidence DESC");
                        execute(conn, "SELECT statement, confidence FROM beliefs WHERE confidence > 0.5 " +
                                "INTERSECT SELECT statement, confidence FROM beliefs WHERE confidence > 0.9");
                        statementsRun = 2;
                        summary = "UNION ALL + INTERSECT (Set-Operationen)";
                    }
                    case "SQL08" -> {
                        setupBeliefsSchema(conn);
                        execute(conn, "SELECT statement, confidence," +
                                "CASE WHEN confidence >= 0.95 THEN 'sehr sicher'" +
                                "     WHEN confidence >= 0.8 THEN 'sicher'" +
                                "     ELSE 'unsicher' END AS bewertung FROM beliefs");
                        execute(conn, "SELECT COALESCE((SELECT confidence FROM beliefs WHERE source = 'nicht-existent'), 0.0)");
                        execute(conn, "SELECT NULLIF(confidence, 0.5) AS non_neutral FROM beliefs");
                        statementsRun = 3;
                        summary = "CASE-WHEN + COALESCE + NULLIF";
                    }
                    case "SQL09" -> {
                        setupBeliefsSchema(conn);
                        execute(conn, "CREATE INDEX IF NOT EXISTS idx_beliefs_confidence ON beliefs(confidence)");
                        execute(conn, "CREATE INDEX IF NOT EXISTS idx_beliefs_source ON beliefs(source)");
                        execute(conn, "EXPLAIN QUERY PLAN SELECT * FROM beliefs WHERE confidence > 0.9");
                        // Check index list
                        ResultSet rs = conn.createStatement().executeQuery(
                                "SELECT name FROM sqlite_master WHERE type='index'");
                        List<String> idx = new ArrayList<>();
                        while (rs.next()) idx.add(rs.getString("name"));
                        rs.close();
                        statementsRun = 3;
                        summary = "Indexes created: " + String.join(", ", idx);
                    }
                    case "SQL10" -> {
                        execute(conn, "CREATE VIRTUAL TABLE IF NOT EXISTS belief_fts USING fts5(" +
                                "statement, source, content='beliefs', content_rowid='id')");
                        // Populate FTS index
                        execute(conn, "INSERT INTO belief_fts(statement, source) " +
                                "SELECT statement, source FROM beliefs");
                        execute(conn, "SELECT statement, confidence, rank FROM belief_fts " +
                                "WHERE belief_fts MATCH 'JDBC OR SQLite' ORDER BY rank LIMIT 5");
                        statementsRun = 3;
                        summary = "FTS5 Volltext-Index erstellt + Query";
                    }
                    case "SQL11" -> {
                        setupBeliefsSchema(conn);
                        conn.setAutoCommit(false);
                        Savepoint sp = conn.setSavepoint("before_insert");
                        try {
                            execute(conn, "INSERT INTO beliefs (statement, confidence, source) " +
                                    "VALUES ('Metis lernt Transaktionen', 0.88, 'sql11')");
                            execute(conn, "INSERT INTO beliefs (statement, confidence, source) " +
                                    "VALUES ('Diese Belief wird zurueckgesetzt', 0.99, 'sql11')");
                            conn.rollback(sp);
                            execute(conn, "INSERT INTO beliefs (statement, confidence, source) " +
                                    "VALUES ('Transaktion erfolgreich committed', 0.95, 'sql11')");
                            conn.commit();
                        } catch (SQLException e) {
                            conn.rollback();
                        }
                        // Verify: only the committed row exists
                        ResultSet rs = conn.createStatement().executeQuery(
                                "SELECT statement FROM beliefs WHERE source='sql11'");
                        List<String> rows = new ArrayList<>();
                        while (rs.next()) rows.add(rs.getString("statement"));
                        rs.close();
                        statementsRun = 4;
                        summary = "Transactions + Savepoint: " + rows.size() + " rows (should be 1)";
                    }
                    case "SQL12" -> {
                        setupMetisLog(conn);
                        for (int i = 1; i <= 100; i++) {
                            execute(conn, "INSERT INTO metis_log (level, message) VALUES (" +
                                    (i % 20 == 0 ? "'WARN'" : "'INFO'") + ", 'Batch-Eintrag " + i + "')");
                        }
                        execute(conn, "SELECT COUNT(*) AS total FROM metis_log");
                        execute(conn, "SELECT level, COUNT(*) FROM metis_log GROUP BY level");
                        statementsRun = 102;
                        summary = "Batch-Insert: 100 rows inserted, WARN/INFO-Ratio 1:19";
                    }
                    case "SQL13" -> {
                        setupBeliefsSchema(conn);
                        // Subquery + EXISTS
                        execute(conn, "SELECT statement FROM beliefs b WHERE EXISTS " +
                                "(SELECT 1 FROM belief_tags t WHERE t.belief_id = b.id AND t.tag = 'java')");
                        // Window function (SQLite 3.25+, included in 3.49)
                        execute(conn, "SELECT statement, confidence, " +
                                "RANK() OVER (ORDER BY confidence DESC) AS rang FROM beliefs");
                        statementsRun = 2;
                        summary = "Subqueries + Window Functions (RANK OVER)";
                    }
                    case "SQL14" -> {
                        // Final project: build a mini knowledge-base schema
                        execute(conn, "CREATE TABLE IF NOT EXISTS knowledge (" +
                                "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                                "  topic TEXT NOT NULL," +
                                "  content TEXT NOT NULL," +
                                "  confidence REAL," +
                                "  learned_at TEXT DEFAULT (datetime('now'))" +
                                ")");
                        execute(conn, "CREATE VIRTUAL TABLE IF NOT EXISTS knowledge_fts USING fts5(" +
                                "topic, content, content='knowledge', content_rowid='id')");
                        execute(conn, "CREATE TABLE IF NOT EXISTS knowledge_links (" +
                                "  source_id INTEGER REFERENCES knowledge(id)," +
                                "  target_id INTEGER REFERENCES knowledge(id)," +
                                "  relation TEXT," +
                                "  PRIMARY KEY (source_id, target_id, relation)" +
                                ")");
                        execute(conn, "INSERT INTO knowledge (topic, content, confidence) VALUES " +
                                "('SQLite', 'Embedded SQL-Datenbank, ACID, FTS5 Volltextsuche', 0.99)," +
                                "('JDBC', 'Java Database Connectivity — universelle DB-API', 0.95)," +
                                "('Metis', 'Autonomer Agent mit Java+SQLite-Faehigkeiten', 0.98)");
                        execute(conn, "INSERT INTO knowledge_fts(topic, content) SELECT topic, content FROM knowledge");
                        execute(conn, "INSERT INTO knowledge_links VALUES (1, 3, 'used_by'), (2, 3, 'used_by')");
                        execute(conn, "SELECT k.topic, k.content, GROUP_CONCAT(kl.relation, ', ') " +
                                "FROM knowledge k LEFT JOIN knowledge_links kl ON k.id = kl.source_id " +
                                "GROUP BY k.id");
                        statementsRun = 6;
                        summary = "Mini-Knowledge-Base: knowledge + FTS + relations";
                    }
                    default -> {
                        summary = "unknown exercise";
                    }
                }

                conn.commit();
                commandsSucceeded++;
                completedExercises.add(tag);

                LOG.info("SQL " + tag + " '" + title + "': " + statementsRun + " statements → " + summary
                        + " saved to " + dbFile);

                // Check DB stats
                try (Statement stmt = conn.createStatement()) {
                    ResultSet rs = stmt.executeQuery(
                            "SELECT COUNT(*) AS cnt FROM sqlite_master WHERE type='table'");
                    int tables = rs.next() ? rs.getInt("cnt") : 0;
                    rs.close();
                    worldModel.update("SQL OK " + tag + " '" + title + "': "
                                    + statementsRun + " stmts, " + tables + " tables, "
                                    + summary + " (DB: " + dbName + ")",
                            0.95, "sql:completed:" + tag, true);
                } catch (Exception ignored) {
                    worldModel.update("SQL OK " + tag + " '" + title + "': "
                                    + statementsRun + " stmts — " + summary,
                            0.95, "sql:completed:" + tag, true);
                }
            }

            return tag;

        } catch (Exception e) {
            LOG.warning("SQL " + tag + " failed: " + e.getMessage());
            String shortMsg = e.getMessage() != null && e.getMessage().length() > 200
                    ? e.getMessage().substring(0, 200) + "…" : String.valueOf(e.getMessage());
            worldModel.update("SQL FAIL " + tag + ": " + shortMsg.replace('\n', ' '),
                    0.7, "sql:error", true);
            return null;
        }
    }

    /**
     * Execute a single non-query SQL statement.
     */
    private void execute(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    /**
     * Setup standard metis_log table with seed data.
     */
    private void setupMetisLog(Connection conn) throws SQLException {
        execute(conn, "CREATE TABLE IF NOT EXISTS metis_log (" +
                "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  timestamp TEXT NOT NULL DEFAULT (datetime('now'))," +
                "  level TEXT CHECK(level IN ('INFO','WARN','ERROR')) NOT NULL," +
                "  message TEXT NOT NULL" +
                ")");
        execute(conn, "INSERT INTO metis_log (level, message) VALUES ('INFO', 'Metis SQL-Lektion')");
        execute(conn, "INSERT INTO metis_log (level, message) VALUES ('WARN', 'Erste Warnung')");
    }

    /**
     * Setup multi-table belief schema with seed data.
     */
    private void setupBeliefsSchema(Connection conn) throws SQLException {
        execute(conn, "CREATE TABLE IF NOT EXISTS beliefs (" +
                "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  statement TEXT NOT NULL," +
                "  confidence REAL CHECK(confidence BETWEEN 0 AND 1) DEFAULT 0.5," +
                "  source TEXT," +
                "  created_at TEXT DEFAULT (datetime('now'))" +
                ")");
        execute(conn, "CREATE TABLE IF NOT EXISTS belief_tags (" +
                "  belief_id INTEGER REFERENCES beliefs(id)," +
                "  tag TEXT NOT NULL," +
                "  PRIMARY KEY (belief_id, tag)" +
                ")");
        execute(conn, "INSERT OR IGNORE INTO beliefs (id, statement, confidence, source) VALUES " +
                "(1, 'SQLite ist embedded', 0.99, 'ex1')," +
                "(2, 'JDBC ist universell', 0.95, 'ex1')," +
                "(3, 'FTS5 = Volltextsuche', 0.92, 'ex2')");
        execute(conn, "INSERT OR IGNORE INTO belief_tags VALUES (1, 'sql'), (2, 'java'), (2, 'jdbc'), (3, 'fts')");
    }

    /**
     * Simple SQL exploration: run a query from a list of known-safe exploration queries.
     */
    public int exploreOneQuery() {
        String[] queries = {
                "SELECT sqlite_version()",
                "SELECT datetime('now', 'localtime')",
                "SELECT name FROM sqlite_master WHERE type='table'",
                "SELECT COUNT(*) FROM sqlite_master WHERE type='index'",
                "SELECT * FROM pragma_database_list",
                "SELECT * FROM pragma_table_info('sqlite_master')",
        };

        commandsTried++;
        String sql = queries[commandsTried % queries.length];
        String dbName = "exploration.db";
        Path dbFile = dbDir.resolve(dbName);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                ResultSetMetaData meta = rs.getMetaData();
                int cols = meta.getColumnCount();
                List<String> rows = new ArrayList<>();
                while (rs.next() && rows.size() < 5) {
                    StringBuilder row = new StringBuilder();
                    for (int i = 1; i <= cols; i++) row.append(rs.getString(i)).append(" ");
                    rows.add(row.toString().trim());
                }
                commandsSucceeded++;
                worldModel.update("SQL explore: " + sql + " → " + String.join(" | ", rows),
                        0.9, "sql:explore", false);
                return 1;
            }
        } catch (Exception e) {
            worldModel.update("SQL explore FAIL: " + sql + " → " + e.getMessage(),
                    0.6, "sql:explore:error", false);
            return 0;
        }
    }

    private static String[] getTopic(int lesson) {
        return switch (lesson) {
            case 1 -> new String[]{"SQL01", "CREATE TABLE"};
            case 2 -> new String[]{"SQL02", "SELECT"};
            case 3 -> new String[]{"SQL03", "INSERT UPDATE DELETE"};
            case 4 -> new String[]{"SQL04", "Multi-Table Schema"};
            case 5 -> new String[]{"SQL05", "JOIN"};
            case 6 -> new String[]{"SQL06", "GROUP BY HAVING"};
            case 7 -> new String[]{"SQL07", "UNION Subquery"};
            case 8 -> new String[]{"SQL08", "CASE COALESCE"};
            case 9 -> new String[]{"SQL09", "Indexes"};
            case 10 -> new String[]{"SQL10", "FTS5 Fulltext"};
            case 11 -> new String[]{"SQL11", "Transactions"};
            case 12 -> new String[]{"SQL12", "Batch Insert"};
            case 13 -> new String[]{"SQL13", "Window Functions"};
            case 14 -> new String[]{"SQL14", "Mini Knowledge Base"};
            default -> null;
        };
    }

    private void loadCompletedExercises() {
        worldModel.all().stream()
                .filter(b -> b.source() != null && b.source().startsWith("sql:completed:"))
                .forEach(b -> {
                    String src = b.source();
                    // Extract SQLxx from "sql:completed:SQLxx"
                    int colon = src.lastIndexOf(':');
                    if (colon >= 0) completedExercises.add(src.substring(colon + 1));
                });
        LOG.info("Loaded " + completedExercises.size() + " completed SQL exercises from beliefs");
    }

    public int commandsTried() { return commandsTried; }
    public int commandsSucceeded() { return commandsSucceeded; }
    public int completedExerciseCount() { return completedExercises.size(); }
    public Path dbDir() { return dbDir; }
}
