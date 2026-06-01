# HomeBase

Privater Familien-Hub für 2 Nutzer — Echtzeit-Sync für Todos, Einkaufsliste und Notizen zwischen Web und Android.

## Stack

| Schicht   | Technologie                          |
|-----------|--------------------------------------|
| Backend   | Kotlin · Ktor · Exposed · PostgreSQL |
| Web       | React 18 · Vite · TypeScript · Tailwind |
| Android   | Jetpack Compose · Kotlin Coroutines  |
| Proxy     | Nginx (TLS-Termination)              |
| Hosting   | Synology NAS · Docker                |

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

### Voraussetzungen

- DSM 7.x mit Container Manager
- DynDNS-Domain (z. B. `home.example.com`)
- Let's Encrypt Zertifikat via **DSM → Systemsteuerung → Sicherheit → Zertifikat**

### Zertifikatspfad anpassen

Synology speichert Let's Encrypt Zertifikate unter:

```
/usr/syno/etc/certificate/_archive/<CERT_ID>/
```

Den aktuellen Pfad findest du in der DSM-Oberfläche oder mit:

```bash
# SSH auf NAS
ls /usr/syno/etc/certificate/_archive/
```

Passe in `docker-compose.yml` den Volume-Mount des nginx-Services an:

```yaml
volumes:
  - /usr/syno/etc/certificate/_archive/<CERT_ID>:/etc/nginx/certs:ro
```

### Deploy

```bash
# .env mit Produktionswerten befüllen
cp .env.example .env && nano .env

# Images bauen und starten
docker compose up -d --build

# Logs prüfen
docker compose logs -f
```

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
| `DIGEST_TIME`       | Uhrzeit des täglichen Digests         | `20:00`                               |

JWT-Secret generieren:

```bash
openssl rand -hex 32
```

## Funktionen

- Inbox-Todos mit Status-Flow `INBOX` → `PLANNED` → `DONE`
- Gemeinsame Einkaufsliste mit Kategorien und Abhaken in Echtzeit
- Notizen mit Suche, Tags und `PRIVATE`/`SHARED` Sichtbarkeit
- Web-Login und Android-Login über dieselben fest konfigurierten Nutzer
- Täglicher Telegram-Digest zur konfigurierten Uhrzeit (`DIGEST_TIME`): heute erledigte Todos, neue Inbox-Items, morgen fällige Todos
