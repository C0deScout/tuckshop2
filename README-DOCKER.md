# Tuck Shop: Docker deployment on one VPS

This package runs the Java application behind Caddy on a single VPS. Caddy
handles HTTPS; the application is reachable only on the private Compose
network. SQLite, Caddy state, and backups survive image replacement.

## Run locally with Docker Desktop on macOS

This folder now includes the complete frontend and a separate local Compose
file. Start Docker Desktop, then run these commands from this directory:

```sh
docker compose --env-file .env.local -f compose.local.yaml config
docker compose --env-file .env.local -f compose.local.yaml up --build -d
docker compose --env-file .env.local -f compose.local.yaml ps
curl http://localhost:5000/api/health
open http://localhost:5000
```

View logs or stop the local application with:

```sh
docker compose --env-file .env.local -f compose.local.yaml logs -f app
docker compose --env-file .env.local -f compose.local.yaml down
```

The database remains in the `tuckshop_local_data` volume after `down`. Use
`down --volumes` only when you intentionally want to erase the local database.
The `/tmp` mount intentionally permits execution because sqlite-jdbc extracts
its platform-specific native library there during startup.

The expected project layout is:

```text
.
├── Caddyfile
├── Dockerfile
├── compose.local.yaml  # Docker Desktop on this Mac
├── compose.yaml
├── src/
│   ├── Db.java
│   └── TuckShop.java
└── wwwroot/
    ├── index.html
    ├── app.js
    └── style.css
```

## First deployment

Use a current Debian or Ubuntu VPS with at least 1 GB RAM. Point the domain's
DNS A/AAAA records at it and allow inbound TCP 22, 80, and 443 plus UDP 443.
Do not expose port 5000.

Install Docker Engine and the Compose plugin from Docker's official repository,
then copy this directory to the server (for example `/opt/tuckshop`). Run:

```sh
cd /opt/tuckshop
cp .env.example .env
chmod 600 .env
openssl rand -base64 32
# Edit DOMAIN, ACME_EMAIL, and TUCK_PASSWORD in .env.
docker compose config --quiet
docker compose build --pull
docker compose up -d
docker compose ps
docker compose logs --tail=100 app caddy
```

Caddy requests a certificate automatically after DNS and ports are correct.
Sign in as `leader` using `TUCK_PASSWORD`. That variable only seeds a brand-new
database; changing it later does not change the stored password.

## Operations

Add another leader (avoid shell history when supplying real credentials):

```sh
docker compose exec app java -cp /app/classes:/app/lib/sqlite-jdbc.jar \
  TuckShop adduser NEW_USERNAME NEW_PASSWORD
```

Create a transactionally consistent SQLite backup while the app is running:

```sh
mkdir -p backups
docker compose --profile tools run --rm backup
ls -lh backups/
```

Schedule that command daily with root's systemd timer or cron, retain several
generations, and copy them off the VPS. A volume is persistence, not a backup.
Test restoration on a separate Compose project before relying on it.

Ready-made systemd units are included. They assume `/opt/tuckshop` and run at
about 02:17 UTC daily:

```sh
sudo cp systemd/tuckshop-backup.* /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now tuckshop-backup.timer
systemctl list-timers tuckshop-backup.timer
```

Before an upgrade, take a backup. Then deploy a reviewed source revision with:

```sh
docker compose build --pull
docker compose up -d --remove-orphans
docker compose ps
```

The named `tuckshop_data` volume is preserved. Sessions are in memory, so an
app restart signs users out. Run exactly one app replica: SQLite plus in-memory
sessions makes horizontal scaling inappropriate without application changes.

## Security and reliability notes

- The app runs as UID/GID 10001, with a read-only root filesystem, no Linux
  capabilities, and a writable `/data` volume and `/tmp` tmpfs only.
- Production cookies are marked `Secure`; Caddy adds HSTS and basic browser
  hardening headers.
- Protect `.env`, restrict SSH to keys, enable unattended host security updates,
  and use a firewall. Docker image tags are intentionally readable here; for a
  stricter supply chain, pin the JDK and Caddy base images by digest in a tested
  dependency-update process.
- Monitor `docker compose ps`, disk usage, certificate logs, and backup age.
  SQLite WAL files live beside the database and must never be copied as an
  ad-hoc substitute for the `.backup` command.

## Restore outline

Stop the app, keep the current volume as a rollback point, and restore only a
verified backup. A conservative approach is to create a new named volume,
copy the selected database into it as `/data/tuck.db` owned by UID 10001, point
Compose at that volume, and start the stack. Never overwrite the sole live
database without retaining the original volume.
