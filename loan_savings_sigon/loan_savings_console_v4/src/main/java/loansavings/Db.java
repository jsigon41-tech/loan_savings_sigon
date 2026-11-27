package loansavings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Database access helpers (SQLite via JDBC). */
final class Db {
    static final String DEMO_USER_PASSWORD = "user123";
    static final String DEMO_ADMIN_PASSWORD = "admin123";

    /** Folder that holds the project (contains the "database" folder with the SQL scripts). */
    private static final Path HOME = findHome();

    private Db() {}

    /**
     * Find the project folder no matter where the program is started from (IDE, terminal, jar).
     * Order: -Dloan.home, current folder, sub-folders of the current folder, then the folders
     * around the compiled classes / jar.
     */
    private static Path findHome() {
        List<Path> starts = new ArrayList<>();
        String prop = System.getProperty("loan.home");
        if (prop != null && !prop.isBlank()) starts.add(Path.of(prop).toAbsolutePath());
        starts.add(Path.of("").toAbsolutePath());
        try {
            Path code = Path.of(Db.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            starts.add(code.toAbsolutePath());
        } catch (Exception ignored) {
            // code location not available; the other candidates are enough
        }
        for (Path start : starts) {
            // walk up the tree (e.g. target/classes -> project folder)
            for (Path p = start; p != null; p = p.getParent()) {
                if (hasScripts(p)) return p;
            }
            // look downwards (e.g. IDE started in the outer folder)
            Path found = searchDown(start, 4);
            if (found != null) return found;
        }
        return Path.of("").toAbsolutePath();
    }

    private static boolean hasScripts(Path dir) {
        return Files.isRegularFile(dir.resolve("database").resolve("schema.sql"));
    }

    private static Path searchDown(Path dir, int depth) {
        if (hasScripts(dir)) return dir;
        if (depth == 0 || !Files.isDirectory(dir)) return null;
        try (var kids = Files.list(dir)) {
            for (Path k : (Iterable<Path>) kids::iterator) {
                String n = k.getFileName().toString();
                if (!Files.isDirectory(k) || n.startsWith(".") || n.equals("target") || n.equals("out")) continue;
                Path r = searchDown(k, depth - 1);
                if (r != null) return r;
            }
        } catch (IOException ignored) {
            // unreadable folder: skip it
        }
        return null;
    }

    /** Read a SQL script from the database folder, or from the jar when it is packaged inside. */
    private static String readScript(String name) throws IOException {
        Path file = HOME.resolve("database").resolve(name);
        if (Files.isRegularFile(file)) return Files.readString(file);
        try (var in = Db.class.getResourceAsStream("/database/" + name)) {
            if (in != null) return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        throw new IOException("Cannot find database/" + name + " (looked in " + HOME
                + "). Start the program from the project folder or use -Dloan.home=<project folder>.");
    }

    static Path dbPath() {
        String env = System.getenv("LOAN_DB");
        return env != null ? Path.of(env) : HOME.resolve("loan_savings.db");
    }

    @FunctionalInterface
    interface Work { void run() throws SQLException; }

    static Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath());
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
        }
        return c;
    }

    static boolean exists() {
        if (!Files.exists(dbPath())) return false;
        try (Connection c = connect()) {
            return !query(c, "SELECT 1 AS x FROM sqlite_master WHERE name = 'users'").isEmpty();
        } catch (SQLException e) {
            return false;
        }
    }

    /** Create schema, load sample data and give every sample account a demo password. */
    static void init(boolean reset) throws SQLException, IOException {
        if (reset) Files.deleteIfExists(dbPath());
        try (Connection c = connect()) {
            runScript(c, "schema.sql");
            runScript(c, "sample_data.sql");
            tx(c, () -> {
                for (Row r : query(c, "SELECT user_id FROM users"))
                    update(c, "UPDATE users SET password_hash = ? WHERE user_id = ?",
                            Security.hash(DEMO_USER_PASSWORD), r.lng("user_id"));
                for (Row r : query(c, "SELECT admin_id FROM administrators"))
                    update(c, "UPDATE administrators SET password_hash = ? WHERE admin_id = ?",
                            Security.hash(DEMO_ADMIN_PASSWORD), r.lng("admin_id"));
            });
        }
    }

    static void runScript(Connection c, String name) throws SQLException, IOException {
        for (String sql : splitSql(readScript(name))) {
            if (sql.toUpperCase().startsWith("PRAGMA")) continue;
            try (Statement st = c.createStatement()) {
                st.execute(sql);
            }
        }
    }

    /** Split a SQL script into statements (handles -- comments and quoted text). */
    static List<String> splitSql(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < script.length(); i++) {
            char ch = script.charAt(i);
            if (!quoted && ch == '-' && i + 1 < script.length() && script.charAt(i + 1) == '-') {
                while (i < script.length() && script.charAt(i) != '\n') i++;
                cur.append('\n');
                continue;
            }
            if (ch == '\'') quoted = !quoted;
            if (ch == ';' && !quoted) {
                String s = cur.toString().trim();
                if (!s.isEmpty()) out.add(s);
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        String tail = cur.toString().trim();
        if (!tail.isEmpty()) out.add(tail);
        return out;
    }

    // ---------- query helpers ----------
    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
    }

    static List<Row> query(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                List<Row> rows = new ArrayList<>();
                while (rs.next()) {
                    Row r = new Row();
                    for (int i = 1; i <= md.getColumnCount(); i++)
                        r.put(md.getColumnLabel(i).toLowerCase(), rs.getObject(i));
                    rows.add(r);
                }
                return rows;
            }
        }
    }

    /** First row or null. */
    static Row one(Connection c, String sql, Object... params) throws SQLException {
        List<Row> rows = query(c, sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    static int update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    /** Run an INSERT and return the new row id. */
    static long insert(Connection c, String sql, Object... params) throws SQLException {
        update(c, sql, params);
        return one(c, "SELECT last_insert_rowid() AS id").lng("id");
    }

    /** All-or-nothing transaction. */
    static void tx(Connection c, Work work) throws SQLException {
        c.setAutoCommit(false);
        try {
            work.run();
            c.commit();
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
    }

    static String newRef(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    static void log(Connection c, long adminId, String action, String table, long targetId, String desc)
            throws SQLException {
        update(c, "INSERT INTO admin_activity_logs (admin_id, action, target_table, target_id, description) "
                + "VALUES (?, ?, ?, ?, ?)", adminId, action, table, targetId, desc);
    }

    static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
