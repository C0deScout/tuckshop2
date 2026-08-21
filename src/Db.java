import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.sql.*;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Thrown to reject an operation. Caught centrally and returned as 400. */
class Reject extends RuntimeException {
    Reject(String message) { super(message); }
}

class Db {
    static String FILE = "tuck.db";

    // All writes go through one lock. A tuck shop is one process serving a
    // handful of leaders, so serialising writers is the simplest correct
    // answer to section 9, and it is obvious rather than implied.
    private static final Object GATE = new Object();

    interface Body<T> { T run(Connection c) throws Exception; }

    static Connection open() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + FILE);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA busy_timeout=5000");
        }
        return c;
    }

    static <T> T read(Body<T> body) {
        try (Connection c = open()) {
            return body.run(c);
        } catch (Reject r) {
            throw r;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** One operation, one transaction. Anything thrown rolls the whole lot back. */
    static <T> T write(Body<T> body) {
        synchronized (GATE) {
            try (Connection c = open()) {
                c.setAutoCommit(false);
                try {
                    T result = body.run(c);
                    c.commit();
                    return result;
                } catch (Exception e) {
                    c.rollback();
                    throw e;
                }
            } catch (Reject r) {
                throw r;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    // ---- small SQL helpers ----

    static PreparedStatement st(Connection c, String sql, Object... ps) throws SQLException {
        PreparedStatement s = c.prepareStatement(sql);
        for (int i = 0; i < ps.length; i++) {
            if (ps[i] == null) s.setNull(i + 1, Types.INTEGER);
            else if (ps[i] instanceof Integer n) s.setInt(i + 1, n);
            else if (ps[i] instanceof Long n) s.setLong(i + 1, n);
            else s.setString(i + 1, ps[i].toString());
        }
        return s;
    }

    static int exec(Connection c, String sql, Object... ps) throws SQLException {
        try (PreparedStatement s = st(c, sql, ps)) { return s.executeUpdate(); }
    }

    static long scalar(Connection c, String sql, Object... ps) throws SQLException {
        try (PreparedStatement s = st(c, sql, ps); ResultSet r = s.executeQuery()) {
            return r.next() ? r.getLong(1) : 0;
        }
    }

    static Integer scalarOrNull(Connection c, String sql, Object... ps) throws SQLException {
        try (PreparedStatement s = st(c, sql, ps); ResultSet r = s.executeQuery()) {
            if (!r.next()) return null;
            int v = r.getInt(1);
            return r.wasNull() ? null : v;
        }
    }

    static String scalarText(Connection c, String sql, Object... ps) throws SQLException {
        try (PreparedStatement s = st(c, sql, ps); ResultSet r = s.executeQuery()) {
            return r.next() ? r.getString(1) : null;
        }
    }

    // ---- schema ----

    static void init() throws Exception {
        try (Connection c = open(); Statement s = c.createStatement()) {
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS Users (
                    Id       INTEGER PRIMARY KEY,
                    Username TEXT    NOT NULL UNIQUE,
                    Hash     TEXT    NOT NULL
                )""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS People (
                    Id        INTEGER PRIMARY KEY,
                    Name      TEXT    NOT NULL,
                    BalanceP  INTEGER NOT NULL DEFAULT 0,
                    Version   INTEGER NOT NULL DEFAULT 0,
                    DeletedAt TEXT    NULL,
                    CHECK (BalanceP >= 0)
                )""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS Items (
                    Id        INTEGER PRIMARY KEY,
                    Name      TEXT    NOT NULL,
                    PriceP    INTEGER NOT NULL DEFAULT 0,
                    SpentP    INTEGER NOT NULL DEFAULT 0,
                    Qty       INTEGER NOT NULL DEFAULT 0,
                    Sold      INTEGER NOT NULL DEFAULT 0,
                    Version   INTEGER NOT NULL DEFAULT 0,
                    DeletedAt TEXT    NULL,
                    CHECK (Qty >= 0 AND Sold >= 0 AND PriceP >= 0 AND SpentP >= 0)
                )""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS Orders (
                    Id             INTEGER PRIMARY KEY,
                    At             TEXT    NOT NULL,
                    PersonId       INTEGER NOT NULL REFERENCES People(Id),
                    TotalP         INTEGER NOT NULL,
                    UserId         INTEGER NOT NULL REFERENCES Users(Id),
                    IdempotencyKey TEXT    NOT NULL UNIQUE,
                    ReversedAt     TEXT    NULL
                )""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS OrderLines (
                    Id      INTEGER PRIMARY KEY,
                    OrderId INTEGER NOT NULL REFERENCES Orders(Id),
                    ItemId  INTEGER NOT NULL REFERENCES Items(Id),
                    Units   INTEGER NOT NULL,
                    PriceP  INTEGER NOT NULL
                )""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS LogEntries (
                    Id            INTEGER PRIMARY KEY,
                    At            TEXT    NOT NULL,
                    Action        TEXT    NOT NULL,
                    PersonId      INTEGER NULL REFERENCES People(Id),
                    ItemId        INTEGER NULL REFERENCES Items(Id),
                    OrderId       INTEGER NULL REFERENCES Orders(Id),
                    AmountP       INTEGER NULL,
                    BalanceAfterP INTEGER NULL,
                    UserId        INTEGER NOT NULL REFERENCES Users(Id),
                    Detail        TEXT    NOT NULL DEFAULT ''
                )""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS LogArchive (
                    Id            INTEGER,
                    At            TEXT,
                    Action        TEXT,
                    PersonId      INTEGER,
                    ItemId        INTEGER,
                    OrderId       INTEGER,
                    AmountP       INTEGER,
                    BalanceAfterP INTEGER,
                    UserId        INTEGER,
                    Detail        TEXT,
                    ArchivedAt    TEXT
                )""");
        }
    }

    // ---- passwords ----

    static String hashPassword(String pw) {
        try {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            KeySpec spec = new PBEKeySpec(pw.toCharArray(), salt, 100_000, 256);
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(key);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static boolean verifyPassword(String pw, String stored) {
        try {
            String[] parts = stored.split(":");
            if (parts.length != 2) return false;
            byte[] salt = Base64.getDecoder().decode(parts[0]);
            byte[] key = Base64.getDecoder().decode(parts[1]);
            KeySpec spec = new PBEKeySpec(pw.toCharArray(), salt, 100_000, 256);
            byte[] test = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return java.security.MessageDigest.isEqual(key, test);
        } catch (Exception e) {
            return false;
        }
    }

    static void addUser(String username, String password) throws Exception {
        try (Connection c = open()) {
            exec(c, "INSERT INTO Users (Username, Hash) VALUES (?, ?)", username, hashPassword(password));
        }
    }

    // ---- money ----

    /** Pounds typed as a decimal string in, pence out. Unparseable yields 0. */
    static int parseP(String s) {
        if (s == null) return 0;
        s = s.trim().replace("£", "").replace(",", "");
        try {
            java.math.BigDecimal d = new java.math.BigDecimal(s);
            if (d.signum() < 0) return 0;
            return d.multiply(java.math.BigDecimal.valueOf(100))
                    .setScale(0, java.math.RoundingMode.HALF_UP).intValueExact();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Unparseable or negative yields 0. */
    static int parseQty(String s) {
        if (s == null) return 0;
        try {
            int n = Integer.parseInt(s.trim());
            return n < 0 ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }

    static String fmt(long pence) {
        return String.format("£%.2f", pence / 100.0);
    }
}
