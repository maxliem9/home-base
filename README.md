# HomeBase

Privater Familien-Hub für 2 Nutzer — Echtzeit-Sync für Todos, Einkaufsliste, Notizen, Zeiterfassung und Rezepte zwischen Web und Android.

## Stack

| Schicht   | Technologie                          |
|-----------|--------------------------------------|
| Backend   | Kotlin · Ktor · Exposed · PostgreSQL |
| Web       | React 18 · Vite · TypeScript · Tailwind |
| Android   | Jetpack Compose · Kotlin Coroutines  |
| Proxy     | Synology DSM Reverse Proxy (TLS) · web-Container (nginx) für SPA + /api |
| Hosting   | Synology NAS · Docker · Images via GHCR |

---

## Lokale Entwicklung

### 1. Voraussetzungen

- Docker & Docker Compose
- JDK 21 (`sdk install java 21-tem`)
- Node 20 (`nvm install 20`)

### 2. Umgebungsvariablen

```bash
cp .env.example .env
# .env anpassen (DB_PASSWORD, JWT_SECRET)
```

### 3. Datenbank starten

```bash
docker compose -f docker-compose.dev.yml up -d
```

### 4. Backend starten

```bash
cd backend
./gradlew run
# → http://localhost:8080/api/v1/health
```

### 5. Web starten

```bash
cd web
npm install
npm run dev
# → http://localhost:5173
```

---

## Deployment auf Synology NAS

> 📘 **Ausführliche Schritt-für-Schritt-Anleitung** (NAS, FRITZ!Box-Routing/DynDNS,
> HTTPS-Zertifikat, Android-App): siehe [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md).

### Voraussetzungen

- DSM 7.x mit Container Manager
- DynDNS-Domain (z. B. `home.example.com`)
- Let's Encrypt Zertifikat via **DSM → Systemsteuerung → Sicherheit → Zertifikat**

### HTTPS via DSM Reverse Proxy

TLS terminiert Synology DSM — kein Zertifikat-Mount, kein Container-Neustart bei
der Erneuerung:

1. **Systemsteuerung → Sicherheit → Zertifikat:** Let's-Encrypt-Zertifikat für die
   Domain anlegen (Port 80 muss von außen erreichbar sein).
2. **Systemsteuerung → Anmeldeportal → Erweitert → Reverse Proxy → Erstellen:**
   - Quelle: **HTTPS**, deine Domain, Port **443**
   - Ziel: **HTTP**, **localhost**, Port **3000** (der `web`-Container)
   - **WebSocket** aktivieren (Custom Header) — nötig für den Echtzeit-Sync
3. Dem Dienst unter **Zertifikat → Konfigurieren** das Zertifikat aus Schritt 1
   zuweisen.

DSM bedient dann `https://<domain>` und erneuert das Zertifikat automatisch.

### Deploy

Backend- und Web-Images werden von der CI gebaut und nach GHCR gepusht — die NAS
**zieht** sie nur (kein Build aus dem Quellcode). Auf der NAS genügen
`docker-compose.yml` und `.env`.

```bash
# Einmalig: an GHCR anmelden (privates Repo ⇒ private Images)
echo <GITHUB_PAT> | docker login ghcr.io -u <github-user> --password-stdin

# .env mit Produktionswerten befüllen
cp .env.example .env && nano .env

# Images ziehen und starten
docker compose pull && docker compose up -d

# Logs prüfen
docker compose logs -f
```

Aktualisieren später: `docker compose pull && docker compose up -d`.

### Health-Check

```bash
curl https://home.example.com/api/v1/health
# → {"status":"ok"}
```

---

## Umgebungsvariablen

| Variable            | Beschreibung                          | Beispiel                              |
|---------------------|---------------------------------------|---------------------------------------|
| `DB_URL`            | JDBC-URL der Datenbank                | `jdbc:postgresql://db:5432/homebase`  |
| `DB_USER`           | Datenbanknutzer                       | `homebase`                            |
| `DB_PASSWORD`       | Datenbankpasswort                     | *(sicheres Passwort)*                 |
| `JWT_SECRET`        | HMAC-Secret für JWT-Signing           | *(32+ zufällige Bytes, hex)*          |
| `TELEGRAM_BOT_TOKEN`| Telegram Bot Token (optional)         | `123456:ABC-...`                      |
| `TELEGRAM_CHAT_ID`  | Empfänger-Chat-ID (optional)          | `-1001234567890`                      |
| `UPLOAD_DIR`        | Speicherort der Notizbilder (prod: per Volume gesetzt) | `/data/uploads`      |
| `MAX_UPLOAD_MB`     | Max. Größe pro Notizbild in MB (optional) | `10`                              |

> Haushaltsname und Digest-Uhrzeit sind **keine** Umgebungsvariablen mehr — sie
> werden in-app unter *Einstellungen → Haushalt / Benachrichtigungen* gesetzt und
> in der Datenbank gespeichert (Defaults `Mäxchen` / `20:00`). Siehe Issue #100.

JWT-Secret generieren:

```bash
openssl rand -hex 32
```

## Funktionen

- Inbox-Todos mit Status-Flow `INBOX` → `PLANNED` → `DONE`
- Gemeinsame Einkaufsliste mit Kategorien und Abhaken in Echtzeit
- Notizen mit Suche, Tags und `PRIVATE`/`SHARED` Sichtbarkeit
- Projektbasierte Zeiterfassung mit Start/Stopp-Timer (höchstens ein laufender Timer pro Nutzer); tagesgruppierte Eintragsliste und Projekt-Detailansicht mit Kennzahlen, Aufschlüsselung pro Nutzer und Wochenübersicht
- Rezeptsammlung mit Zutaten, Zubereitungsschritten und Portionsskalierung
- Web-Login und Android-Login über dieselben fest konfigurierten Nutzer
- Täglicher Telegram-Digest zur in-app konfigurierten Uhrzeit (Einstellungen → Benachrichtigungen): heute erledigte Todos, neue Inbox-Items, morgen fällige Todos
