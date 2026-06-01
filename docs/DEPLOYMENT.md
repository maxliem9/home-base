# HomeBase — Setup & Deployment Guide

End-to-end walkthrough: run it locally, deploy it on a Synology NAS, expose it
through a FRITZ!Box with HTTPS, and install the Android app on your phone.

Architecture (production):

```
 Phone / Browser ──HTTPS:443──> FRITZ!Box ──port-forward──> Synology NAS
                                                              │
                                                   ┌──────────┴───────────┐
                                                   │   nginx (container)   │  :80 → 301 → :443
                                                   │   reverse proxy + TLS │
                                                   └─────┬───────────┬─────┘
                                                /api/ →  │           │  / →
                                              backend:8080        web:3000
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
- Roughly 15–30 minutes.

> **Note on Java version:** the backend compiles and runs on **Java 24**. The
> Docker images in this repo are already aligned to that (`backend/Dockerfile`
> uses JDK/JRE 24). You don't need Java installed on the NAS — Docker handles it.

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

## 2. Get the code onto the NAS

Container Manager builds the images from source, so the whole repo must live on
the NAS.

1. In **File Station**, create a folder, e.g. `/docker/homebase`
   (full path `/volume1/docker/homebase`).
2. Copy the entire project into it (drag-and-drop via File Station, or over
   SMB/`scp`, or `git clone` if you have Git Server installed). You should end
   up with `docker-compose.yml`, `backend/`, `web/`, `nginx/`, `.env.example`,
   etc. inside that folder.

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

# Telegram daily digest (optional — leave blank to disable)
TELEGRAM_BOT_TOKEN=
TELEGRAM_CHAT_ID=
DIGEST_TIME=20:00
```

- `DB_URL` keeps the hostname `db` — that's the compose service name, don't change it.
- Generate the secret on any machine: `openssl rand -hex 32`.
- **Telegram (optional):** create a bot via [@BotFather](https://t.me/BotFather)
  to get `TELEGRAM_BOT_TOKEN`; get your `TELEGRAM_CHAT_ID` by messaging the bot
  and reading `https://api.telegram.org/bot<token>/getUpdates`. The digest fires
  daily at `DIGEST_TIME` (24h, NAS local time) and is skipped on empty days.

> **Timezone:** the digest's "today/tomorrow" boundaries follow the **container's
> timezone**. If your digest fires at the wrong moment, add `TZ=Europe/Berlin`
> to the `backend` service environment (in `docker-compose.yml` or `.env`).

---

## 4. TLS certificate (DSM Let's Encrypt)

The nginx container terminates HTTPS using a certificate that DSM obtains and
renews for you.

1. First make sure your domain reaches the NAS on port 80 — that requires the
   FRITZ!Box port-forward from **Part F**. (Do Part F, then come back here, or
   issue the cert once forwarding is in place.)
2. **DSM → Control Panel → Security → Certificate → Add → Add a new certificate
   → Get a certificate from Let's Encrypt.**
   - **Domain name:** your domain (e.g. `homebase.example.com` or `yourname.synology.me`).
   - Finish the wizard. Issuance needs inbound port 80 to reach the NAS.
3. Select the new certificate → **Settings/Configure** → set it as the
   certificate used by the system (this makes DSM store it under the **`DEFAULT`**
   archive folder, which is what `docker-compose.yml` mounts).

The compose file mounts the cert read-only:

```yaml
- /usr/syno/etc/certificate/_archive/DEFAULT:/etc/nginx/certs:ro
```

That folder must contain `fullchain.pem` and `privkey.pem` (it does, by default).

> **If your cert is *not* the default:** SSH into the NAS and list the archive:
> ```bash
> sudo ls -l /usr/syno/etc/certificate/_archive/
> ```
> Each sub-folder is one certificate. Find the one holding your domain's
> `fullchain.pem`/`privkey.pem` and change the mount path in
> `docker-compose.yml` to that folder instead of `DEFAULT`.

> **Renewal:** DSM auto-renews every ~90 days, but the running nginx container
> won't notice new files on its own. After a renewal, restart the proxy:
> `docker restart homebase-nginx-1` (or restart the project in Container
> Manager). A monthly DSM **Scheduled Task** running that command keeps it hands-off.

---

## 5. Heads-up: ports 80 / 443 on Synology

The nginx container wants host ports **80** and **443**. On many Synology setups
DSM's own services (Web Station / Login Portal) already use them, which would
make the container fail to start with *"address already in use"*.

Check over SSH:

```bash
sudo netstat -tlnp | grep -E ':80 |:443 '
```

- **If 80/443 are free:** great, no change needed.
- **If they're taken:** either free them in **Control Panel → Login Portal**
  (move DSM to other ports / disable Web Station), **or** remap the container to
  high host ports. To remap, edit the `nginx` service in `docker-compose.yml`:

  ```yaml
  ports:
    - "8080:80"
    - "8443:443"
  ```

  …and in **Part F** forward external **80 → NAS:8080** and **443 → NAS:8443**.

> If you run the Synology firewall, allow the chosen ports (Control Panel →
> Security → Firewall).

---

## 6. Start the stack (Container Manager)

**GUI way:**

1. **Container Manager → Project → Create.**
2. **Project name:** `homebase`. **Path:** the folder from Part 2
   (`/docker/homebase`). It will detect `docker-compose.yml`.
3. Create and let it **build** (first build pulls images and compiles the
   backend — a few minutes). It then starts all four services.

**CLI way (often more reliable for the first build):** SSH into the NAS:

```bash
cd /volume1/docker/homebase
sudo docker compose up -d --build
sudo docker compose ps          # all services "running"/"healthy"
sudo docker compose logs -f backend
```

Healthy state: `db` healthy, `backend`, `web`, `nginx` all up. The backend runs
Flyway migrations automatically on first boot (creates the tables) and seeds the
users from `SEED_USERS`.

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
   - **HTTPS:** protocol TCP, external port **443** → to NAS port **443**
     (or **8443** if you remapped in Part 5).
   - **HTTP:** protocol TCP, external port **80** → to NAS port **80**
     (or **8080**). Needed for the HTTP→HTTPS redirect and Let's Encrypt.
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

If the browser warns about the certificate, the cert mount/domain in Parts 4–5
doesn't match the domain you're visiting.

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

# Update after pulling new code
sudo docker compose up -d --build

# Logs
sudo docker compose logs -f backend

# Restart proxy after a cert renewal
sudo docker restart homebase-nginx-1

# Back up the database (data lives in the pgdata volume)
sudo docker compose exec db pg_dump -U "$DB_USER" homebase > homebase-$(date +%F).sql
```

The Postgres data persists in the `pgdata` Docker volume across restarts and
rebuilds. Don't delete that volume unless you intend to wipe all data.

---

## 11. Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| `backend` build fails on Java version | Ensure you're on this repo's `backend/Dockerfile` (JDK/JRE **24**). Older versions used JDK 21 and can't compile/run Java 24. |
| nginx container won't start, "address already in use" | DSM owns 80/443 → remap to 8080/8443 (Part 5) and adjust the FRITZ!Box forward (Part 7b). |
| Browser cert warning / `ERR_CERT` | Cert domain ≠ visited domain, or the mount points at the wrong `_archive` folder (Part 4). |
| Site loads but login fails | `SEED_USERS` not set, or password mismatch. Check `docker compose logs backend` for the seeding line; fix `.env` and `up -d`. |
| Works at home, not outside | Port forwarding missing/wrong, or **DS-Lite/CGNAT** (Part 7b note). Test the health URL on mobile data. |
| Real-time sync not updating | WebSocket blocked — confirm the `/api/` proxy in `nginx/nginx.conf` keeps the `Upgrade`/`Connection` headers (it does by default) and that you reach the site over HTTPS. |
| Android app can't connect | `BASE_URL` still the placeholder, missing `/api/v1/` suffix, or you built `assembleDebug` without repointing the debug `BASE_URL` (Path A). |
| Telegram digest never arrives | `TELEGRAM_BOT_TOKEN`/`TELEGRAM_CHAT_ID` empty, wrong chat id, or nothing to report that day (empty digests are skipped by design). |
```
