# 7th Purley Tuck Shop — Java

Plain Java 17+, one dependency (`sqlite-jdbc`), no Maven, no Gradle, no Spring.
The web server is `com.sun.net.httpserver`, which ships inside the JDK. nginx
sits in front and reverse-proxies to it.

This version was compiled and run end to end before you got it. The flows
tested: login, wrong password, add person, empty name rejected, add stock,
sell, double-submitted order, oversell, insufficient balance, top up, undo,
undo twice, edit with a stale version, delete, log rendering, clear log with
the wrong and right password, path traversal, and requests with no cookie.

## Deploy

On the VPS:

```
apt install openjdk-17-jdk-headless nginx
```

Copy this folder to `/opt/tuckshop`, then:

```
cd /opt/tuckshop
sh build.sh
```

Make a user for it and hand over the folder:

```
adduser --system --group --no-create-home tuckshop
chown -R tuckshop:tuckshop /opt/tuckshop
```

Service:

```
cp deploy/tuckshop.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now tuckshop
journalctl -u tuckshop -n 20      # first leader's login is printed here
```

nginx:

```
cp deploy/nginx-tuckshop.conf /etc/nginx/sites-available/tuckshop
ln -s /etc/nginx/sites-available/tuckshop /etc/nginx/sites-enabled/
nginx -t && systemctl reload nginx
```

Edit `server_name` in that file to your domain or the VPS IP first.

## Logging in

There is no signup page. The first time the app starts against an empty
database it creates one account and prints it to the log:

```
*** Created first leader: username 'leader', password 'changeme' ***
```

Set a real one instead by putting `Environment=TUCK_PASSWORD=whatever` in the
service file **before** the first start. After that, add leaders by hand:

```
cd /opt/tuckshop
sudo -u tuckshop java -cp app:lib/sqlite-jdbc.jar TuckShop adduser russell theirpassword
```

## Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `TUCK_PORT` | 5000 | Port the Java app listens on |
| `TUCK_BIND` | 127.0.0.1 | Loopback only, so nothing reaches it except nginx |
| `TUCK_DB` | tuck.db | SQLite file |
| `TUCK_WEB` | wwwroot | Static files, only used if you run without nginx |
| `TUCK_PASSWORD` | changeme | First leader's password, first run only |

## Layout

| File | What's in it |
|---|---|
| `src/Db.java` | Schema, transactions, the write lock, money, password hashing |
| `src/TuckShop.java` | Routing, sessions, every endpoint, log rendering |
| `wwwroot/` | The phone UI — three files, no build step |
| `lib/sqlite-jdbc.jar` | The only dependency |
| `deploy/` | systemd unit and nginx site |

## Things you should know

**Set up HTTPS before camp.** Right now leaders' passwords cross the public
internet in plain text, because this is a VPS on port 80 rather than a laptop
in a field. `certbot --nginx` fixes it and takes two minutes. Do it.

**Sessions are held in memory.** Restarting the service signs everyone out.
That's a deliberate trade to avoid a session table; it costs one re-login.

**The driver is pinned to sqlite-jdbc 3.42.0.0 on purpose.** Versions from
3.43 onwards need `slf4j-api` on the classpath as well or they refuse to load.
3.42 has no dependencies at all, which is why this is one jar rather than
three.

**Requests are form-encoded, not JSON.** That removes the need for a JSON
parsing library, so the dependency list stays at one. Responses are still
JSON, written by hand in `jq()`.

**Renaming an item rewrites how old sales read in the log.** Because log lines
are rendered from the current data at read time rather than frozen as text,
a sale of "Coke" shows as "Coke 330ml" after you rename it. The money is
untouched and correct — `OrderLines.PriceP` still holds the price at the time
of sale. This is the cost of the structured log that section 6.8 asked for.

**§8 says ASCII only, then uses `£` in every example.** `£` is not ASCII. I
followed the examples. `Db.fmt` is the one place to change it.

**A VPS means no signal, no till.** Section 11 again. Keep a paper fallback
for the shop and enter the backlog when the phone gets bars.

## Backups

The whole camp is one file. On the VPS:

```
sqlite3 /opt/tuckshop/tuck.db ".backup /root/tuck-$(date +%F-%H%M).db"
```

Run it each evening. `apt install sqlite3` if it's not there.
