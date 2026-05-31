# HomeBase — Product Requirements Document

## Vision
Privater Familien-Hub für zwei Personen (Max + Partner) mit
Echtzeit-Sync zwischen Android-App und Web-Browser.
Erreichbar über DynDNS + HTTPS ohne VPN.

## Nutzer
- 2 feste Nutzer, kein Self-Registration
- Authentifizierung per JWT

---

## MVP-Features

### 1. Todos (Inbox-Prinzip)
- Schnell erfassen: nur Titel nötig → landet in Inbox
- Inbox leeren (Web oder Mobile): Datum, Person, Priorität setzen
- Status-Flow: INBOX → PLANNED → DONE
- Täglicher Digest zeigt: heute erledigt, neu in Inbox, fällig morgen
- Echtzeit-Sync: Änderungen sofort bei beiden sichtbar

Todo-Felder:
- title (Pflicht)
- description (optional)
- status: INBOX | PLANNED | DONE
- assignee (optional)
- due_date (optional)
- priority: LOW | MEDIUM | HIGH (optional)
- created_by, created_at, done_at

### 2. Einkaufsliste
- Items hinzufügen, abhaken, löschen
- Kategorien (Obst, Kühlware, Haushalt, …)
- Echtzeit-Sync

### 3. Notizen
- Erstellen, bearbeiten, löschen
- Markdown-Rendering
- Tags + Volltextsuche
- Sichtbarkeit: privat oder geteilt

### 4. Täglicher Digest (Telegram)
- Jeden Abend zur konfigurierbaren Uhrzeit
- Inhalt:
    - Heute erledigte Todos
    - Neue Items in Inbox
    - Todos mit Fälligkeit morgen

---

## Post-MVP (Backlog)
- Angebote zu Einkaufsitems (Rewe/Kaufland)
- Wiederkehrende Todos
- Bilder in Notizen
- iOS-App

---

## Tech-Stack
- Backend:  Kotlin + Ktor, PostgreSQL 16, WebSockets, Flyway
- Web:      React + Vite + TypeScript + Tailwind CSS
- Android:  Jetpack Compose
- Proxy:    Nginx (Reverse Proxy, HTTPS)
- Digest:   Telegram Bot API
- Hosting:  Synology NAS, erreichbar via DynDNS + Port 443

## Nicht im MVP
- Push Notifications (Android)
- Offline-Fähigkeit
- Mehrere Haushalte / Teams
- VPN-Pflicht