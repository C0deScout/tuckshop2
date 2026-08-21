import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static java.nio.charset.StandardCharsets.UTF_8;

public class TuckShop {

    static final Map<String, Integer> SESSIONS = new ConcurrentHashMap<>();
    static final DateTimeFormatter STORE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    static final DateTimeFormatter SHOW = DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale.UK);
    static final String COOKIE_SECURE = Boolean.parseBoolean(
            System.getenv().getOrDefault("TUCK_SECURE_COOKIES", "false")) ? "; Secure" : "";
    static Path web = Paths.get("wwwroot");

    public static void main(String[] args) throws Exception {
        Class.forName("org.sqlite.JDBC");
        if (System.getenv("TUCK_DB") != null) Db.FILE = System.getenv("TUCK_DB");
        if (System.getenv("TUCK_WEB") != null) web = Paths.get(System.getenv("TUCK_WEB"));
        Db.init();

        // java -cp ... TuckShop adduser russell hunter2
        if (args.length == 3 && args[0].equals("adduser")) {
            Db.addUser(args[1], args[2]);
            System.out.println("Added leader '" + args[1] + "'.");
            return;
        }

        try (Connection c = Db.open()) {
            if (Db.scalar(c, "SELECT COUNT(*) FROM Users") == 0) {
                String pw = System.getenv("TUCK_PASSWORD");
                if (pw == null) pw = "changeme";
                Db.addUser("leader", pw);
                System.out.println("*** Created first leader: username 'leader', password '" + pw + "' ***");
                System.out.println("*** Add more: java -cp app:lib/sqlite-jdbc.jar TuckShop adduser NAME PASSWORD ***");
            }
        }

        int port = Integer.parseInt(System.getenv().getOrDefault("TUCK_PORT", "5000"));
        String bind = System.getenv().getOrDefault("TUCK_BIND", "127.0.0.1");
        HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        server.createContext("/api/", TuckShop::route);
        server.createContext("/", TuckShop::serveStatic);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("Tuck shop listening on http://" + bind + ":" + port);
    }

    // ---------------------------------------------------------------- routing

    static void route(HttpExchange ex) throws IOException {
        try {
            String[] s = ex.getRequestURI().getPath().split("/");   // "", "api", ...
            String head = s.length > 2 ? s[2] : "";
            String m = ex.getRequestMethod();

            if (head.equals("health") && m.equals("GET")) {
                json(ex, 200, "{\"status\":\"ok\"}");
                return;
            }
            if (head.equals("login") && m.equals("POST")) { login(ex); return; }
            if (head.equals("logout")) { logout(ex); return; }

            Integer uid = userId(ex);
            if (uid == null) { json(ex, 401, "{\"error\":\"Sign in again.\"}"); return; }

            switch (head) {
                case "state" -> state(ex, uid);
                case "log" -> {
                    if (s.length > 3 && s[3].equals("clear")) clearLog(ex, uid);
                    else showLog(ex);
                }
                case "orders" -> {
                    if (s.length > 4 && s[4].equals("undo")) undo(ex, uid, Integer.parseInt(s[3]));
                    else sell(ex, uid);
                }
                case "people" -> {
                    if (s.length > 4 && s[4].equals("topup")) topUp(ex, uid, Integer.parseInt(s[3]));
                    else if (s.length > 3 && m.equals("PUT")) editPerson(ex, uid, Integer.parseInt(s[3]));
                    else if (s.length > 3 && m.equals("DELETE")) deletePerson(ex, uid, Integer.parseInt(s[3]));
                    else addPerson(ex, uid);
                }
                case "items" -> {
                    if (s.length > 3 && m.equals("PUT")) editItem(ex, uid, Integer.parseInt(s[3]));
                    else if (s.length > 3 && m.equals("DELETE")) deleteItem(ex, uid, Integer.parseInt(s[3]));
                    else addStock(ex, uid);
                }
                default -> json(ex, 404, "{\"error\":\"No such endpoint.\"}");
            }
        } catch (Reject r) {
            json(ex, 400, "{\"error\":" + jq(r.getMessage()) + "}");
        } catch (Exception e) {
            e.printStackTrace();
            json(ex, 500, "{\"error\":\"Something went wrong on the server.\"}");
        }
    }

    // ---------------------------------------------------------------- auth

    static void login(HttpExchange ex) throws Exception {
        Map<String, String> f = form(ex);
        String user = f.getOrDefault("username", "");
        String pass = f.getOrDefault("password", "");

        String hash = Db.read(c -> Db.scalarText(c, "SELECT Hash FROM Users WHERE Username = ?", user));
        if (hash == null || !Db.verifyPassword(pass, hash)) {
            json(ex, 400, "{\"error\":\"Wrong username or password.\"}");
            return;
        }
        int id = (int) (long) Db.read(c -> Db.scalar(c, "SELECT Id FROM Users WHERE Username = ?", user));

        byte[] raw = new byte[24];
        new SecureRandom().nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        SESSIONS.put(token, id);
        ex.getResponseHeaders().add("Set-Cookie", "tuck=" + token
                + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=1209600" + COOKIE_SECURE);
        json(ex, 200, "{\"user\":" + jq(user) + "}");
    }

    static void logout(HttpExchange ex) throws IOException {
        String t = cookie(ex);
        if (t != null) SESSIONS.remove(t);
        ex.getResponseHeaders().add("Set-Cookie", "tuck=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0" + COOKIE_SECURE);
        json(ex, 200, "{}");
    }

    static String cookie(HttpExchange ex) {
        List<String> hs = ex.getRequestHeaders().get("Cookie");
        if (hs == null) return null;
        for (String h : hs)
            for (String part : h.split(";")) {
                String p = part.trim();
                if (p.startsWith("tuck=")) return p.substring(5);
            }
        return null;
    }

    static Integer userId(HttpExchange ex) {
        String t = cookie(ex);
        return t == null ? null : SESSIONS.get(t);
    }

    static String username(Connection c, int uid) throws SQLException {
        return Db.scalarText(c, "SELECT Username FROM Users WHERE Id = ?", uid);
    }

    // ---------------------------------------------------------------- state

    static void state(HttpExchange ex, int uid) throws Exception {
        String body = Db.read(c -> {
            StringBuilder people = new StringBuilder("[");
            long owed = 0;
            try (PreparedStatement s = Db.st(c,
                    "SELECT Id, Name, BalanceP, Version FROM People WHERE DeletedAt IS NULL ORDER BY Name COLLATE NOCASE");
                 ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    if (people.length() > 1) people.append(',');
                    people.append("{\"id\":").append(r.getInt(1))
                          .append(",\"name\":").append(jq(r.getString(2)))
                          .append(",\"balanceP\":").append(r.getInt(3))
                          .append(",\"version\":").append(r.getInt(4)).append('}');
                    owed += r.getInt(3);
                }
            }
            people.append(']');

            StringBuilder items = new StringBuilder("[");
            long shelf = 0;
            try (PreparedStatement s = Db.st(c,
                    "SELECT Id, Name, PriceP, SpentP, Qty, Sold, Version FROM Items WHERE DeletedAt IS NULL ORDER BY Name COLLATE NOCASE");
                 ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    int priceP = r.getInt(3), spentP = r.getInt(4), qty = r.getInt(5), sold = r.getInt(6);
                    int bought = qty + sold;
                    int costEach = bought == 0 ? 0 : spentP / bought;   // integer division, per spec
                    if (items.length() > 1) items.append(',');
                    items.append("{\"id\":").append(r.getInt(1))
                         .append(",\"name\":").append(jq(r.getString(2)))
                         .append(",\"priceP\":").append(priceP)
                         .append(",\"spentP\":").append(spentP)
                         .append(",\"qty\":").append(qty)
                         .append(",\"sold\":").append(sold)
                         .append(",\"version\":").append(r.getInt(7))
                         .append(",\"bought\":").append(bought)
                         .append(",\"costEachP\":").append(costEach)
                         .append(",\"marginP\":").append((long) sold * (priceP - costEach)).append('}');
                    shelf += (long) qty * costEach;
                }
            }
            items.append(']');

            // Revenue and cost count soft-deleted items too: their history still happened.
            long revenue = Db.scalar(c, "SELECT COALESCE(SUM(Sold * PriceP), 0) FROM Items");
            long cost = Db.scalar(c, "SELECT COALESCE(SUM(SpentP), 0) FROM Items");
            Integer lastOrder = Db.scalarOrNull(c, "SELECT Id FROM Orders WHERE ReversedAt IS NULL ORDER BY Id DESC LIMIT 1");

            return "{\"user\":" + jq(username(c, uid)) +
                   ",\"people\":" + people + ",\"items\":" + items +
                   ",\"totals\":{\"owedP\":" + owed + ",\"revenueP\":" + revenue +
                   ",\"costP\":" + cost + ",\"profitP\":" + (revenue - cost) +
                   ",\"shelfP\":" + shelf + "}" +
                   ",\"lastOrderId\":" + (lastOrder == null ? "null" : lastOrder) + "}";
        });
        json(ex, 200, body);
    }

    // ---------------------------------------------------------------- sell

    static void sell(HttpExchange ex, int uid) throws Exception {
        Map<String, String> f = form(ex);
        int personId = Db.parseQty(f.get("personId"));
        String key = f.getOrDefault("idempotencyKey", "");
        String linesRaw = f.getOrDefault("lines", "");   // "3:2,7:1"

        String body = Db.write(c -> {
            Integer existing = Db.scalarOrNull(c, "SELECT Id FROM Orders WHERE IdempotencyKey = ?", key);
            if (existing != null) return "{\"orderId\":" + existing + ",\"duplicate\":true}";
            if (key.isBlank()) throw new Reject("Missing order key. Start the basket again.");

            Map<Integer, Integer> lines = new LinkedHashMap<>();
            for (String part : linesRaw.split(",")) {
                if (part.isBlank()) continue;
                String[] kv = part.split(":");
                int id = Db.parseQty(kv[0]), units = kv.length > 1 ? Db.parseQty(kv[1]) : 0;
                if (units > 0) lines.merge(id, units, Integer::sum);
            }
            if (lines.isEmpty()) throw new Reject("The basket is empty.");

            String personName;
            int balanceP;
            try (PreparedStatement s = Db.st(c, "SELECT Name, BalanceP FROM People WHERE Id = ? AND DeletedAt IS NULL", personId);
                 ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new Reject("That person is no longer in the list.");
                personName = r.getString(1);
                balanceP = r.getInt(2);
            }

            long total = 0;
            List<int[]> priced = new ArrayList<>();   // itemId, units, priceP
            for (Map.Entry<Integer, Integer> e : lines.entrySet()) {
                try (PreparedStatement s = Db.st(c, "SELECT Name, PriceP, Qty FROM Items WHERE Id = ? AND DeletedAt IS NULL", e.getKey());
                     ResultSet r = s.executeQuery()) {
                    if (!r.next()) throw new Reject("An item in the basket is no longer on sale.");
                    String name = r.getString(1);
                    int priceP = r.getInt(2), qty = r.getInt(3);
                    if (e.getValue() > qty) throw new Reject(name + ": only " + qty + " left.");
                    priced.add(new int[]{e.getKey(), e.getValue(), priceP});
                    total += (long) e.getValue() * priceP;
                }
            }

            if (total > balanceP)
                throw new Reject(personName + " has " + Db.fmt(balanceP) + ", the order comes to " + Db.fmt(total) + ".");

            for (int[] l : priced)
                Db.exec(c, "UPDATE Items SET Qty = Qty - ?, Sold = Sold + ?, Version = Version + 1 WHERE Id = ?",
                        l[1], l[1], l[0]);
            Db.exec(c, "UPDATE People SET BalanceP = BalanceP - ?, Version = Version + 1 WHERE Id = ?",
                    (int) total, personId);

            String now = LocalDateTime.now().format(STORE);
            Db.exec(c, "INSERT INTO Orders (At, PersonId, TotalP, UserId, IdempotencyKey) VALUES (?, ?, ?, ?, ?)",
                    now, personId, (int) total, uid, key);
            int orderId = (int) Db.scalar(c, "SELECT last_insert_rowid()");

            for (int[] l : priced)
                Db.exec(c, "INSERT INTO OrderLines (OrderId, ItemId, Units, PriceP) VALUES (?, ?, ?, ?)",
                        orderId, l[0], l[1], l[2]);

            int after = balanceP - (int) total;
            log(c, now, "SALE", uid, personId, null, orderId, (int) total, after, "");
            return "{\"orderId\":" + orderId + ",\"balanceAfterP\":" + after + "}";
        });
        json(ex, 200, body);
    }

    static void undo(HttpExchange ex, int uid, int orderId) throws Exception {
        String body = Db.write(c -> {
            int personId, totalP;
            try (PreparedStatement s = Db.st(c, "SELECT PersonId, TotalP, ReversedAt FROM Orders WHERE Id = ?", orderId);
                 ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new Reject("That order no longer exists.");
                if (r.getString(3) != null) throw new Reject("That order has already been undone.");
                personId = r.getInt(1);
                totalP = r.getInt(2);
            }

            List<int[]> lines = new ArrayList<>();
            try (PreparedStatement s = Db.st(c, "SELECT ItemId, Units FROM OrderLines WHERE OrderId = ?", orderId);
                 ResultSet r = s.executeQuery()) {
                while (r.next()) lines.add(new int[]{r.getInt(1), r.getInt(2)});
            }

            for (int[] l : lines)
                Db.exec(c, "UPDATE Items SET Qty = Qty + ?, Sold = Sold - ?, Version = Version + 1 WHERE Id = ?",
                        l[1], l[1], l[0]);
            Db.exec(c, "UPDATE People SET BalanceP = BalanceP + ?, Version = Version + 1 WHERE Id = ?", totalP, personId);

            String now = LocalDateTime.now().format(STORE);
            Db.exec(c, "UPDATE Orders SET ReversedAt = ? WHERE Id = ?", now, orderId);
            int after = (int) Db.scalar(c, "SELECT BalanceP FROM People WHERE Id = ?", personId);
            log(c, now, "UNDO", uid, personId, null, orderId, totalP, after, "");
            return "{\"balanceAfterP\":" + after + "}";
        });
        json(ex, 200, body);
    }

    // ---------------------------------------------------------------- people

    static void addPerson(HttpExchange ex, int uid) throws Exception {
        Map<String, String> f = form(ex);
        String name = f.getOrDefault("name", "").trim();
        if (name.isEmpty()) throw new Reject("Enter a name.");
        int opening = Db.parseP(f.get("amount"));

        Db.write(c -> {
            Db.exec(c, "INSERT INTO People (Name, BalanceP) VALUES (?, ?)", name, opening);
            int id = (int) Db.scalar(c, "SELECT last_insert_rowid()");
            log(c, LocalDateTime.now().format(STORE), "PERSON", uid, id, null, null, opening, null, "");
            return null;
        });
        json(ex, 200, "{}");
    }

    static void topUp(HttpExchange ex, int uid, int id) throws Exception {
        int amount = Db.parseP(form(ex).get("amount"));
        if (amount <= 0) throw new Reject("Enter an amount to top up.");

        String body = Db.write(c -> {
            int rows = Db.exec(c, "UPDATE People SET BalanceP = BalanceP + ?, Version = Version + 1 WHERE Id = ? AND DeletedAt IS NULL",
                    amount, id);
            if (rows == 0) throw new Reject("That person is no longer in the list.");
            int after = (int) Db.scalar(c, "SELECT BalanceP FROM People WHERE Id = ?", id);
            log(c, LocalDateTime.now().format(STORE), "TOPUP", uid, id, null, null, amount, after, "");
            return "{\"balanceAfterP\":" + after + "}";
        });
        json(ex, 200, body);
    }

    static void editPerson(HttpExchange ex, int uid, int id) throws Exception {
        Map<String, String> f = form(ex);
        String name = f.getOrDefault("name", "").trim();
        if (name.isEmpty()) throw new Reject("Enter a name.");
        int balance = Db.parseP(f.get("balance"));
        int version = Db.parseQty(f.get("version"));

        Db.write(c -> {
            String oldName;
            int oldBalance;
            try (PreparedStatement s = Db.st(c, "SELECT Name, BalanceP FROM People WHERE Id = ? AND DeletedAt IS NULL", id);
                 ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new Reject("That person is no longer in the list.");
                oldName = r.getString(1);
                oldBalance = r.getInt(2);
            }
            int rows = Db.exec(c, "UPDATE People SET Name = ?, BalanceP = ?, Version = Version + 1 WHERE Id = ? AND Version = ?",
                    name, balance, id, version);
            if (rows == 0) throw new Reject("Someone else changed this person while you had it open. Reopen and try again.");

            List<String> parts = new ArrayList<>();
            if (!oldName.equals(name)) parts.add(oldName + " to " + name);
            if (oldBalance != balance) parts.add(Db.fmt(oldBalance) + " to " + Db.fmt(balance));
            if (parts.isEmpty()) parts.add(name + " unchanged");
            log(c, LocalDateTime.now().format(STORE), "EDIT", uid, id, null, null, null, null,
                    "person " + String.join(", ", parts));
            return null;
        });
        json(ex, 200, "{}");
    }

    static void deletePerson(HttpExchange ex, int uid, int id) throws Exception {
        Db.write(c -> {
            String name;
            int balance;
            try (PreparedStatement s = Db.st(c, "SELECT Name, BalanceP FROM People WHERE Id = ? AND DeletedAt IS NULL", id);
                 ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new Reject("That person is no longer in the list.");
                name = r.getString(1);
                balance = r.getInt(2);
            }
            String now = LocalDateTime.now().format(STORE);
            Db.exec(c, "UPDATE People SET DeletedAt = ?, Version = Version + 1 WHERE Id = ?", now, id);
            log(c, now, "DELETE", uid, id, null, null, null, null,
                    "person " + name + ", holding " + Db.fmt(balance));
            return null;
        });
        json(ex, 200, "{}");
    }

    // ---------------------------------------------------------------- stock

    static void addStock(HttpExchange ex, int uid) throws Exception {
        Map<String, String> f = form(ex);
        String name = f.getOrDefault("name", "").trim();
        if (name.isEmpty()) throw new Reject("Enter an item name.");
        int qty = Db.parseQty(f.get("qty"));
        int costP = Db.parseP(f.get("cost"));
        int newPrice = Db.parseP(f.get("price"));

        Db.write(c -> {
            Integer found = Db.scalarOrNull(c, "SELECT Id FROM Items WHERE Name = ? AND DeletedAt IS NULL", name);
            int id, priceP;
            if (found == null) {
                priceP = newPrice;
                Db.exec(c, "INSERT INTO Items (Name, PriceP, SpentP, Qty) VALUES (?, ?, ?, ?)", name, priceP, costP, qty);
                id = (int) Db.scalar(c, "SELECT last_insert_rowid()");
            } else {
                // Existing item: the price box is ignored, the existing price is kept.
                id = found;
                priceP = (int) Db.scalar(c, "SELECT PriceP FROM Items WHERE Id = ?", id);
                Db.exec(c, "UPDATE Items SET Qty = Qty + ?, SpentP = SpentP + ?, Version = Version + 1 WHERE Id = ?",
                        qty, costP, id);
            }
            log(c, LocalDateTime.now().format(STORE), "STOCK", uid, null, id, null, costP, null,
                    "added " + qty + " x " + name + " at " + Db.fmt(priceP) + " each, cost " + Db.fmt(costP));
            return null;
        });
        json(ex, 200, "{}");
    }

    static void editItem(HttpExchange ex, int uid, int id) throws Exception {
        Map<String, String> f = form(ex);
        String name = f.getOrDefault("name", "").trim();
        if (name.isEmpty()) throw new Reject("Enter an item name.");
        int priceP = Db.parseP(f.get("price"));
        int qty = Db.parseQty(f.get("qty"));
        int spentP = Db.parseP(f.get("spent"));
        int version = Db.parseQty(f.get("version"));

        Db.write(c -> {
            String oldName;
            int oldPrice, oldQty, oldSpent;
            try (PreparedStatement s = Db.st(c, "SELECT Name, PriceP, Qty, SpentP FROM Items WHERE Id = ? AND DeletedAt IS NULL", id);
                 ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new Reject("That item is no longer on sale.");
                oldName = r.getString(1);
                oldPrice = r.getInt(2);
                oldQty = r.getInt(3);
                oldSpent = r.getInt(4);
            }
            int rows = Db.exec(c,
                    "UPDATE Items SET Name = ?, PriceP = ?, Qty = ?, SpentP = ?, Version = Version + 1 WHERE Id = ? AND Version = ?",
                    name, priceP, qty, spentP, id, version);
            if (rows == 0) throw new Reject("Someone else changed this item while you had it open. Reopen and try again.");

            List<String> parts = new ArrayList<>();
            if (!oldName.equals(name)) parts.add(oldName + " to " + name);
            if (oldPrice != priceP) parts.add(Db.fmt(oldPrice) + " to " + Db.fmt(priceP));
            if (oldQty != qty) parts.add(oldQty + " to " + qty + " left");
            if (oldSpent != spentP) parts.add("spent " + Db.fmt(oldSpent) + " to " + Db.fmt(spentP));
            if (parts.isEmpty()) parts.add(name + " unchanged");
            log(c, LocalDateTime.now().format(STORE), "EDIT", uid, null, id, null, null, null,
                    String.join(", ", parts));
            return null;
        });
        json(ex, 200, "{}");
    }

    static void deleteItem(HttpExchange ex, int uid, int id) throws Exception {
        Db.write(c -> {
            String name;
            int qty, sold;
            try (PreparedStatement s = Db.st(c, "SELECT Name, Qty, Sold FROM Items WHERE Id = ? AND DeletedAt IS NULL", id);
                 ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new Reject("That item is no longer on sale.");
                name = r.getString(1);
                qty = r.getInt(2);
                sold = r.getInt(3);
            }
            String now = LocalDateTime.now().format(STORE);
            Db.exec(c, "UPDATE Items SET DeletedAt = ?, Version = Version + 1 WHERE Id = ?", now, id);
            log(c, now, "DELETE", uid, null, id, null, null, null,
                    "item " + name + ", " + qty + " left, " + sold + " sold");
            return null;
        });
        json(ex, 200, "{}");
    }

    // ---------------------------------------------------------------- log

    static void showLog(HttpExchange ex) throws Exception {
        String body = Db.read(c -> {
            List<String> out = new ArrayList<>();
            List<Object[]> rows = new ArrayList<>();
            try (PreparedStatement s = Db.st(c, """
                    SELECT l.At, l.Action, l.OrderId, l.AmountP, l.BalanceAfterP, l.Detail, p.Name
                    FROM LogEntries l
                    LEFT JOIN People p ON p.Id = l.PersonId
                    ORDER BY l.Id DESC LIMIT 500""");
                 ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    Object orderId = r.getObject(3);
                    rows.add(new Object[]{r.getString(1), r.getString(2), orderId,
                            r.getInt(4), r.getInt(5), r.getString(6),
                            r.getString(7) == null ? "?" : r.getString(7)});
                }
            }

            for (Object[] row : rows) {
                LocalDateTime at = LocalDateTime.parse((String) row[0], STORE);
                String action = (String) row[1];
                Integer orderId = row[2] == null ? null : ((Number) row[2]).intValue();
                int amountP = (Integer) row[3], afterP = (Integer) row[4];
                String detail = (String) row[5], person = (String) row[6];

                String bodyText = switch (action) {
                    case "SALE" -> person + " - " + basket(c, orderId) + " - " + Db.fmt(amountP) + " - " + Db.fmt(afterP) + " left";
                    case "UNDO" -> person + " - " + Db.fmt(amountP) + " refunded - " + Db.fmt(afterP) + " left";
                    case "TOPUP" -> person + " + " + Db.fmt(amountP) + " - " + Db.fmt(afterP) + " total";
                    case "PERSON" -> "added " + person + " with " + Db.fmt(amountP);
                    default -> detail;
                };
                out.add(at.format(SHOW) + "  " + pad(action) + " " + bodyText);
            }

            StringBuilder sb = new StringBuilder("{\"lines\":[");
            for (int i = 0; i < out.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(jq(out.get(i)));
            }
            return sb.append("]}").toString();
        });
        json(ex, 200, body);
    }

    static void clearLog(HttpExchange ex, int uid) throws Exception {
        String password = form(ex).getOrDefault("password", "");
        Db.write(c -> {
            // Re-enter your own login password. Checked here, never on the client.
            String hash = Db.scalarText(c, "SELECT Hash FROM Users WHERE Id = ?", uid);
            if (hash == null || !Db.verifyPassword(password, hash)) throw new Reject("Wrong password.");
            Db.exec(c, """
                    INSERT INTO LogArchive
                    SELECT Id, At, Action, PersonId, ItemId, OrderId, AmountP, BalanceAfterP, UserId, Detail, ?
                    FROM LogEntries""", LocalDateTime.now().format(STORE));
            Db.exec(c, "DELETE FROM LogEntries");
            return null;
        });
        json(ex, 200, "{}");
    }

    static String basket(Connection c, Integer orderId) throws SQLException {
        if (orderId == null) return "";
        StringBuilder sb = new StringBuilder();
        try (PreparedStatement s = Db.st(c, """
                SELECT ol.Units, i.Name FROM OrderLines ol
                JOIN Items i ON i.Id = ol.ItemId
                WHERE ol.OrderId = ? ORDER BY ol.Id""", orderId);
             ResultSet r = s.executeQuery()) {
            while (r.next()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(r.getInt(1)).append(" x ").append(r.getString(2));
            }
        }
        return sb.toString();
    }

    static void log(Connection c, String at, String action, int userId,
                    Integer personId, Integer itemId, Integer orderId,
                    Integer amountP, Integer balanceAfterP, String detail) throws SQLException {
        Db.exec(c, """
                INSERT INTO LogEntries (At, Action, PersonId, ItemId, OrderId, AmountP, BalanceAfterP, UserId, Detail)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                at, action, personId, itemId, orderId, amountP, balanceAfterP, userId, detail);
    }

    static String pad(String tag) {
        return tag.length() >= 6 ? tag : tag + " ".repeat(6 - tag.length());
    }

    // ---------------------------------------------------------------- plumbing

    static Map<String, String> form(HttpExchange ex) throws IOException {
        String raw = new String(ex.getRequestBody().readAllBytes(), UTF_8);
        Map<String, String> m = new HashMap<>();
        for (String kv : raw.split("&")) {
            if (kv.isEmpty()) continue;
            int i = kv.indexOf('=');
            String k = i < 0 ? kv : kv.substring(0, i);
            String v = i < 0 ? "" : kv.substring(i + 1);
            m.put(URLDecoder.decode(k, UTF_8), URLDecoder.decode(v, UTF_8));
        }
        return m;
    }

    static void json(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    static String jq(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
                }
            }
        }
        return sb.append('"').toString();
    }

    /** Only used when running without nginx. In the deployed setup nginx serves these directly. */
    static void serveStatic(HttpExchange ex) throws IOException {
        String p = ex.getRequestURI().getPath();
        if (p.equals("/")) p = "/index.html";
        Path file = web.resolve(p.substring(1)).normalize();
        if (!file.startsWith(web) || !Files.isRegularFile(file)) {
            ex.sendResponseHeaders(404, -1);
            ex.close();
            return;
        }
        String type = p.endsWith(".html") ? "text/html; charset=utf-8"
                : p.endsWith(".js") ? "text/javascript; charset=utf-8"
                : p.endsWith(".css") ? "text/css; charset=utf-8"
                : "application/octet-stream";
        byte[] b = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
}
