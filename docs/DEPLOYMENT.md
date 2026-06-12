# HomeBase — Setup & Deployment Guide

End-to-end walkthrough: run it locally, deploy it on a Synology NAS, expose it
through a FRITZ!Box with HTTPS, and install the Android app on your phone.

Architecture (production):

```
 Phone / Browser ──HTTPS:443──> FRITZ!Box ──forward 443──> Synology NAS
                                                             │
                                          DSM reverse proxy  (terminates TLS,
                                                             │   DSM-managed cert)
                                                             ▼  http://localhost:3000
                                                  ┌────────────────────┐
                                                  │  web (nginx :3000)  │  serves the SPA,
                                                  └─────────┬──────────┘  proxies /api + WS
                                                  /api, /ws →│
                                                       backend:8080
                                                             │
                                                       db (postgres:16)
```

The web app and the Android app talk to the **same** backend at
`https://<your-domain>/api/v1/`. Real-time sync uses WebSockets on the same host.

---

## 0. Prerequisites

- A **Synology NAS**, DSM 7.x, with the **Container Manager** package installed
  (Package Center → Container Manager).
- A **domain name** that will point at your home IP. Easiest options:
  - **Synology DDNS** (e.g. `yourname.synology.me`) — DSM can manage the cert for it, or
  - a **DynDNS** hostname configured in the FRITZ!Box (covered in Part F).
- Admin access to your **FRITZ!Box**.
- A computer with **Android Studio** (or the Android SDK) to build the phone app.
- The backend/web images are pulled from **GHCR** (`ghcr.io/maxliem9/homebase-*`).
  This repo is private, so the packages are private too — you'll need a **GitHub
  Personal Access Token** with the `read:packages` scope for the NAS login in
  Part 2. (Or make the two packages public on GitHub, then no login is needed.)
- Roughly 15–30 minutes.

> **Prebuilt images:** the backend and web images are built by CI and published
> to GitHub Container Registry, so the NAS **pulls** them and never compiles
> anything. You don't need Java, Node, or the app source on the NAS — only
> `docker-compose.yml` and your `.env`.

> **TLS:** Synology DSM's built-in **reverse proxy** terminates HTTPS using a
> DSM-managed certificate (auto-renewed, no container restart needed) and forwards
> to the `web` container, which serves the SPA and proxies `/api` + WebSocket to
> the backend. There is no nginx container in this stack — DSM is the front door.

---

## 1. (Optional) Run it locally first

Good for a smoke test before touching the NAS.

```bash
# 1. Start just the database
docker compose -f docker-compose.dev.yml up -d

# 2. Backend (needs a JDK; uses the Gradle wrapper)
cd backend
DB_URL=jdbc:postgresql://localhost:5432/homebase \
DB_USER=homebase DB_PASSWORD=devpass \
JWT_SECRET=$(openssl rand -hex 32) \
SEED_USERS=max:test1234,partner:test1234 \
./gradlew run
# → http://localhost:8080

# 3. Web (in another terminal)
cd web
npm install
npm run dev
# → http://localhost:5173  (proxies /api to :8080)
```

Log in with one of the `SEED_USERS` you set. When happy, move on.

---

## 2. Put the deployment files on the NAS & log in to GHCR

Because the images are prebuilt and pulled from GHCR, the NAS only needs a few
files — not the whole source tree.

1. In **File Station**, create a folder, e.g. `/docker/homebase`
   (full path `/volume1/docker/homebase`).
2. Copy just **`docker-compose.yml`** and **`.env.example`** into it (plus the
   **`scripts/`** folder if you want the helpers). Copying the whole repo also
   works — the extra files are simply unused.
3. Log Docker in to GHCR so it can pull the private images. SSH into the NAS:
   ```bash
   echo <YOUR_GITHUB_PAT> | sudo docker login ghcr.io -u <your-github-username> --password-stdin
   ```
   Use a token with the `read:packages` scope. (Skip this step if you made the
   packages public.) The login persists, so this is a one-time setup.

---

## 3. Create your `.env`

Copy `.env.example` to `.env` in the project root on the NAS and fill it in:

```ini
# Database
DB_URL=jdbc:postgresql://db:5432/homebase
DB_USER=homebase
DB_PASSWORD=<a-strong-password>

# JWT — generate with:  openssl rand -hex 32
JWT_SECRET=<64-hex-char-random-string>

# The two fixed users (no self-registration).
# Format: username:password,username2:password2
# Re-seeded on every backend start, so you can change a password here later.
SEED_USERS=max:<password1>,partner:<password2>

# Container timezone — interprets the (in-app) digest time / RECURRING_TIME and the
# CSV-export timestamps. Defaults to Europe/Berlin; change it if your household is elsewhere.
TZ=Europe/Berlin

# Telegram daily digest (optional — leave blank to disable). Bot credentials only;
# the send time is set in-app (Einstellungen → Benachrichtigungen), not here.
TELEGRAM_BOT_TOKEN=
TELEGRAM_CHAT_ID=
```

- `DB_URL` keeps the hostname `db` — that's the compose service name, don't change it.
- Generate the secret on any machine: `openssl rand -hex 32`.
- **Telegram (optional):** create a bot via [@BotFather](https://t.me/BotFather)
  to get `TELEGRAM_BOT_TOKEN`; get your `TELEGRAM_CHAT_ID` by messaging the bot
  and reading `https://api.telegram.org/bot<token>/getUpdates`. The digest fires
  daily at the time set in-app (Einstellungen → Benachrichtigungen, default `20:00`,
  in `TZ`) and is skipped on empty days.
- **In-app settings (no env var):** the household name (sidebar) and the digest time
  are edited in *Einstellungen → Haushalt / Benachrichtigungen* and stored in the
  database (defaults `Mäxchen` / `20:00`). See issue #100.
- **Other optional vars** (all carry working defaults in `.env.example`, so you can
  leave them out): `IMAGE_TAG` (GHCR tag, default `latest`),
  `UPLOAD_DIR` / `MAX_UPLOAD_MB` (note images, see below), and
  `RECURRING_TIME` (daily time the recurring-todo safety-net runs, default `00:30`).

> **Timezone:** the digest's firing time and "today/tomorrow" boundaries — and the
> time-tracking CSV timestamps — follow the container's `TZ`, which is **preset to
> `Europe/Berlin`** in `docker-compose.yml`. Override it via `TZ` in `.env` only if
> your household is in another zone.

> **Note images:** uploads are stored in the `uploads` Docker volume, wired up
> automatically by `docker-compose.yml` (mounted at `/data/uploads`), so there's
> nothing to configure for a default setup. To change the 10 MB per-image cap,
> set `MAX_UPLOAD_MB` in `.env`. Remember to back up the `uploads` volume — see
> Part 10.

> **Non-root containers:** both images run unprivileged (backend uid 10001, web
> nginx uid 101), so the `uploads` volume has to be writable by uid 10001.
> `scripts/deploy.sh` chowns it on every run — start the stack that way and it just
> works. Any other start path (Container Manager **GUI** or raw `docker compose`)
> skips that step; see the caveat in Part 6.

---

## 4. HTTPS via DSM's reverse proxy

DSM terminates HTTPS for you: it obtains/renews the certificate and forwards the
decrypted traffic to the `web` container. No certificate is mounted into any
container, and **renewals apply automatically — no restarts**. You can set this
up after the stack is running (Part 6), since the proxy targets `localhost:3000`.

**4a. Get a certificate**

1. Make sure your domain reaches the NAS on port 80 — that needs the FRITZ!Box
   port-forward from **Part 7** (do that first, or issue the cert once it's in place).
2. **DSM → Control Panel → Security → Certificate → Add → Add a new certificate
   → Get a certificate from Let's Encrypt.** Enter your domain (e.g.
   `homebase.example.com` or `yourname.synology.me`) and finish the wizard.
   (Issuance needs inbound port 80.)

**4b. Add the reverse-proxy rule**

**DSM → Control Panel → Login Portal → Advanced → Reverse Proxy → Create**
(older DSM: **Application Portal → Reverse Proxy**):

- **Source:** protocol **HTTPS**, hostname **your domain**, port **443**.
- **Destination:** protocol **HTTP**, hostname **localhost**, port **3000**
  (the `web` container's published port).
- Open **Custom Header → Create → WebSocket** (or tick *Enable WebSocket*) — this
  is required for real-time sync.
- Save. Then in **Control Panel → Security → Certificate → Settings**, make sure
  the service for your domain uses the certificate from 4a.

DSM now serves `https://<your-domain>` and renews the cert on its own.

---

## 5. Ports

The app stack binds only **`127.0.0.1:3000`** (the `web` container, reachable
just by the NAS itself) — so there's no container fighting DSM over ports 80/443.
DSM's reverse proxy listens on **443**, which it owns.

If DSM's own Login Portal already uses 443, either move the portal
(**Control Panel → Login Portal**) or pick a different source port for the
reverse-proxy rule and forward that port in Part 7.

> Synology firewall on? Allow the reverse-proxy port (Control Panel → Security →
> Firewall).

---

## 6. Start the stack (Container Manager)

**GUI way:**

1. **Container Manager → Project → Create.**
2. **Project name:** `homebase`. **Path:** the folder from Part 2
   (`/docker/homebase`). It will detect `docker-compose.yml`.
3. Create and start. Container Manager **pulls** the prebuilt images from GHCR
   (under a minute) and starts the three services — nothing is compiled on the NAS.

**CLI way (often more reliable for the first run):** SSH into the NAS:

```bash
cd /volume1/docker/homebase
sudo docker compose pull        # fetch the backend/web images from GHCR
sudo docker compose up -d
sudo docker compose ps          # all services "running"/"healthy"
sudo docker compose logs -f backend
```

Healthy state: `db` healthy, `backend` and `web` up. The backend runs Flyway
migrations automatically on first boot (creates the tables) and seeds the users
from `SEED_USERS`. HTTPS is then served by DSM's reverse proxy (Part 4) — the
stack itself only listens on `localhost:3000`.

> **Non-root & the uploads volume:** both containers run unprivileged (backend uid
> 10001, web nginx uid 101), so the `uploads` volume must be writable by uid 10001.
> `scripts/deploy.sh` (Part 10b) chowns it automatically — but the raw `docker
> compose` commands above and the GUI start do **not**. So if you reuse an older,
> root-owned `uploads` volume without going through the script, the first note-image
> upload fails with `AccessDenied`. Fix it once (the backend also logs a warning at
> startup when `UPLOAD_DIR` isn't writable):
> ```bash
> docker compose run --rm --no-deps --user root --entrypoint chown \
>   backend -R 10001:10001 /data/uploads
> ```

---

## 7. FRITZ!Box: DynDNS + port forwarding

Goal: a stable hostname that points at your home, with ports 80/443 forwarded to
the NAS. (Menu labels below are the German FRITZ!OS names with English in
parentheses.)

### 7a. A hostname for your changing IP

Pick **one**:

- **Synology DDNS (simplest):** DSM → Control Panel → External Access → DDNS →
  Add → provider *Synology*, hostname `yourname.synology.me`. DSM updates it and
  can issue the Let's Encrypt cert for it (Part 4).
- **FRITZ!Box DynDNS:** **Internet → Freigaben → DynDNS (Internet → Permit Access
  → DynDNS)**. Enable it, pick a provider (e.g. dynv6, No-IP, deSEC), and enter
  the domain, username, password, and update URL from that provider. The
  FRITZ!Box then keeps the record pointed at your current WAN IP.
- **MyFRITZ! (zero-config):** **Internet → MyFRITZ!-Konto** gives you a
  `xxxxx.myfritz.net` hostname. Works, but a custom/Synology domain is tidier for
  certificates.

### 7b. Forward the ports to the NAS

1. **Internet → Freigaben → Portfreigaben (Internet → Permit Access → Port
   Sharing) → Gerät für Freigaben hinzufügen (Add device for sharing).**
2. Select your **NAS** from the device list.
3. Add **two** "new sharing" entries (*Neue Freigabe → Portfreigabe*):
   - **HTTPS:** protocol TCP, external port **443** → NAS port **443**
     (DSM's reverse proxy; use your chosen port if you changed it in Part 5).
   - **HTTP:** protocol TCP, external port **80** → NAS port **80**
     (DSM uses it for Let's Encrypt issuance and renewal).
4. Apply. Keep the NAS on a **fixed local IP** (FRITZ!Box → Heimnetz → Netzwerk →
   the NAS → "Diesem Gerät immer die gleiche IP-Adresse zuweisen").

> Most German ISPs now give a real IPv4. If you're on **DS-Lite / CGNAT**
> (IPv4-only forwarding won't work), enable **IPv6** forwarding too, or ask your
> ISP for a public IPv4 ("Public-IP" option).

---

## 8. Verify

From a device **outside** your home network (e.g. phone on mobile data):

```
https://<your-domain>/api/v1/health      → {"status":"ok"}   (or similar)
https://<your-domain>/                    → the HomeBase login page
```

Log in with a `SEED_USERS` account. Open the web app on two devices and add a
todo — it should appear on both in real time (WebSocket sync).

If the browser warns about the certificate, the reverse-proxy rule or its
assigned certificate (Part 4) doesn't match the domain you're visiting.

---

## 9. Android app

The app ships pointing at a placeholder domain, so you set your domain and build
an installable APK. Two paths — pick one.

### First: point the app at your domain

Edit `android/app/build.gradle.kts` and replace
`your-dyndns-domain.example.com` with your real domain in **both** the
`defaultConfig` and `release` `BASE_URL` lines (keep the `/api/v1/` suffix and
the trailing slash):

```kotlin
buildConfigField("String", "BASE_URL", "\"https://<your-domain>/api/v1/\"")
```

### Path A — Quick (debug APK, auto-signed)

Simplest for personal use; no keystore needed. Point the **debug** `BASE_URL`
(in `buildTypes { debug { ... } }`) at your domain too, then:

```bash
cd android
./gradlew assembleDebug
# APK: android/app/build/outputs/apk/debug/app-debug.apk
```

### Path B — Proper signed release

Smaller, optimized, and what you'd keep installed long-term.

1. Generate a keystore once (keep it safe — you need it for every update):
   ```bash
   keytool -genkey -v -keystore homebase.jks -keyalg RSA -keysize 2048 \
     -validity 10000 -alias homebase
   ```
2. Add a signing config to `android/app/build.gradle.kts` inside `android { }`:
   ```kotlin
   signingConfigs {
       create("release") {
           storeFile = file("/path/to/homebase.jks")
           storePassword = "…"
           keyAlias = "homebase"
           keyPassword = "…"
       }
   }
   buildTypes {
       release {
           signingConfig = signingConfigs.getByName("release")
           // …existing isMinifyEnabled / proguardFiles stay as-is…
       }
   }
   ```
   (R8/ProGuard keep-rules for Retrofit/Moshi are already in
   `app/proguard-rules.pro`.)
3. Build:
   ```bash
   ./gradlew assembleRelease
   # APK: android/app/build/outputs/apk/release/app-release.apk
   ```

> Or skip Gradle entirely: open the project in **Android Studio → Build →
> Generate Signed Bundle / APK → APK**, and let the wizard create the keystore.

### Install on the phone

1. Transfer the `.apk` to the phone (USB, email, cloud, or
   `adb install app-…​.apk`).
2. On the phone, allow **Install unknown apps** for the app you're opening the
   APK from (Settings → Apps → Special access).
3. Tap the APK, install, open, and log in with a `SEED_USERS` account.

To update later, bump `versionCode`/`versionName` in `build.gradle.kts`, rebuild,
and reinstall (same signing key for Path B).

---

## 10. Day-2 operations

```bash
cd /volume1/docker/homebase

# Update to the latest published images (CI republishes on every merge to main)
sudo docker compose pull && sudo docker compose up -d

# Logs
sudo docker compose logs -f backend

# (Cert renewals need no action — DSM's reverse proxy applies them automatically.)

# Back up the database (todos, notes, recipes, … live in the pgdata volume)
sudo docker compose exec db pg_dump -U "$DB_USER" homebase > homebase-$(date +%F).sql

# Back up uploaded note images — these live in the `uploads` volume, NOT in the
# SQL dump above. (Volume name is <project>_uploads; check with `docker volume ls`.)
sudo docker run --rm -v homebase_uploads:/data -v "$PWD":/backup alpine \
  tar czf /backup/homebase-uploads-$(date +%F).tar.gz -C /data .
```

Two Docker volumes hold all persistent state and survive restarts/rebuilds:
- **`pgdata`** — the Postgres database (todos, notes, recipes, time entries, …).
- **`uploads`** — the original note image files (added with the "Bilder in
  Notizen" feature). These are **not** part of the `pg_dump`, so back this volume
  up separately (command above) or you'll lose all note images on a rebuild.

Don't delete either volume unless you intend to wipe that data.

---

## 10b. Helper scripts

The scripts in [`scripts/`](../scripts) take the repetitive bits off your hands.
Run them from the project folder; on the NAS prefix with `sudo` if Docker needs it.

| Script | What it does |
|---|---|
| `scripts/setup-env.sh` | Creates `.env` — random `JWT_SECRET`/`DB_PASSWORD`, prompts for the two login passwords (blank ⇒ generated & printed). Won't overwrite an existing `.env` without `--force`. |
| `scripts/deploy.sh` | `docker compose pull && up -d` + status, and chowns the `uploads` volume to the non-root backend (uid 10001) — first start and every later update. |
| `scripts/backup.sh [dir]` | Dumps the database **and** tars the `uploads` volume (note images) into `./backups/` (or `[dir]`). |
| `scripts/restore.sh <db.sql> <uploads.tar.gz>` | Restores a backup — **destructive**, asks for confirmation. |

Typical first run on the NAS:

```bash
cd /volume1/docker/homebase
./scripts/setup-env.sh                  # create .env (Part 3)
sudo docker login ghcr.io -u <user>     # one-time, for the private images (Part 2)
sudo ./scripts/deploy.sh                # pull + start (Part 6)
```

Point a weekly **DSM → Control Panel → Task Scheduler** job at `scripts/backup.sh`
to keep snapshots of both the database and the note images.

---

## 11. Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| `docker compose pull` fails with `unauthorized` / `denied` | NAS not logged in to GHCR (Part 2), PAT missing the `read:packages` scope, or the package is private and not yours. Re-run `docker login ghcr.io`, or make the packages public. |
| Pull fails with `manifest unknown` / `not found` | CI hasn't published the images yet — merge to `main` once so the `docker` job runs — or `IMAGE_TAG` in `.env` points at a tag that doesn't exist. |
| `docker login ghcr.io` keeps rejecting the password (`sudo: N incorrect password attempts`) | That `Password:` prompt is **`sudo`** asking for your **NAS account password**, not Docker asking for the token. Type the account password there; the GHCR token is fed in separately through the pipe (`--password-stdin`) and is never typed at a prompt. The account must be in DSM's **administrators** group for `sudo` to work at all. |
| `deploy.sh` finishes but `docker compose ps` shows nothing running | The script is `set -euo pipefail`, so it aborts on the first error: a failed `docker compose pull` (not logged in to GHCR — Part 2) or a missing `.env` stops it **before** `up -d` ever runs. Fix that cause, then re-run `sudo ./scripts/deploy.sh`. |
| DSM shows **502 / 503 Bad Gateway** | The reverse-proxy destination is wrong or `web` isn't up. Confirm the rule points at `localhost:3000` and `docker compose ps` shows `web` running. |
| Browser cert warning / `ERR_CERT` | The reverse-proxy rule's domain ≠ the domain you visit, or it isn't using the Let's Encrypt cert (Part 4). |
| Cert is still `CN=synology` (self-signed) after issuing Let's Encrypt | Issuing isn't enough — **assign** it in **Control Panel → Security → Certificate → Settings** for your domain / reverse-proxy service. DSM serves whatever cert is mapped to the SNI hostname; unmapped ⇒ the default self-signed one. Check with `openssl s_client -connect <domain>:443 -servername <domain> 2>/dev/null \| openssl x509 -noout -issuer`. |
| Visiting the domain redirects to DSM itself (`:5000` over HTTP, `:5001` over HTTPS) | No reverse-proxy rule matches that hostname, so DSM's default vhost bounces you to its own UI. Create the rule with **Source hostname = your exact domain** (Part 4b) — a typo or a leftover old hostname won't match. Also confirm DSM's own Login Portal isn't sitting on 443 (Part 5). |
| Site loads but login fails | `SEED_USERS` not set, or password mismatch. Check `docker compose logs backend` for the seeding line; fix `.env` and `up -d`. |
| Works at home, not outside | Port forwarding missing/wrong, or **DS-Lite/CGNAT** (Part 7b note). Test the health URL on mobile data. |
| Real-time sync not updating | WebSocket not enabled on the DSM reverse-proxy rule — add the **WebSocket** custom header (Part 4b) — and confirm you're on HTTPS. |
| Android app can't connect | `BASE_URL` still the placeholder, missing `/api/v1/` suffix, or you built `assembleDebug` without repointing the debug `BASE_URL` (Path A). |
| Telegram digest never arrives | `TELEGRAM_BOT_TOKEN`/`TELEGRAM_CHAT_ID` empty, wrong chat id, or nothing to report that day (empty digests are skipped by design). |
| Note image upload fails / HTTP 413 | Image exceeds `MAX_UPLOAD_MB` (default 10 MB); or the DSM reverse proxy caps the request body — raise it in the rule's advanced settings (the `web` container itself already allows 12 MB). |
| Note image upload fails with `AccessDenied` / permission denied | The `uploads` volume is root-owned but the backend runs as uid 10001. Use `scripts/deploy.sh` (it chowns automatically) or run the one-time `chown -R 10001:10001` from Part 6. Backend startup logs warn when `UPLOAD_DIR` isn't writable. |
| Note images vanish after a rebuild | The `uploads` volume wasn't backed up/restored — images live there, not in the SQL dump (Part 10). |
