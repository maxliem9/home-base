# HomeBase — Product Requirements Document

> Stand: 2026-06-03. Status-Legende: ✅ umgesetzt · 🔜 geplant.
> Sofern nicht anders vermerkt, sind umgesetzte Features auf allen Flächen
> verfügbar (Backend, Web, Android) und werden per WebSocket in Echtzeit synchronisiert.

## Vision
Privater Familien-Hub für zwei Personen (Max + Partner) mit
Echtzeit-Sync zwischen Android-App und Web-Browser.
Erreichbar über DynDNS + HTTPS ohne VPN.

## Nutzer
- 2 feste Nutzer, kein Self-Registration
- Authentifizierung per JWT

## Sprache & Lokalisierung
- UI aktuell ausschließlich Deutsch.
- Alle benutzersichtbaren Texte sind zentralisiert (Web: i18n-Katalog unter
  `web/src/i18n/`, Android: `res/values/strings.xml`), sodass eine spätere
  Mehrsprachigkeit ohne Umbau der Komponenten möglich ist.
- Ein Sprachumschalter ist bewusst (noch) nicht implementiert → siehe Backlog.

---

## Features

### 1. Todos (Inbox-Prinzip) ✅
- Schnell erfassen: nur Titel nötig → landet in Inbox
- Inbox leeren (Web oder Mobile): Datum, Person, Priorität setzen
- Status-Flow: INBOX → PLANNED → DONE
- Web: gemeinsamer „Aufgaben"-Tab mit Segmenten Inbox / Geplant / Erledigt
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

### 2. Einkaufsliste ✅
- Items hinzufügen, abhaken, löschen
- Kategorien (Obst, Kühlware, Haushalt, …); unkategorisiert = „Sonstiges"
- Echtzeit-Sync

### 3. Notizen ✅
- Erstellen, bearbeiten, löschen
- Markdown-Inhalt
- Tags + Volltextsuche
- Sichtbarkeit: privat oder geteilt

### 4. Täglicher Digest (Telegram) ✅
- Jeden Abend zur konfigurierbaren Uhrzeit (`DIGEST_TIME`)
- Inhalt:
    - Heute erledigte Todos
    - Neue Items in Inbox
    - Todos mit Fälligkeit morgen

### 5. Zeiterfassung ✅
Projektbezogene Zeiterfassung mit Start/Stopp-Timer.

- **Projekte:** Name, Farbe (Hex), Archivierungs-Flag. Anlegen, bearbeiten, archivieren.
- **Zeiteinträge:** Timer pro Projekt starten und stoppen, optionale Beschreibung,
  Start-/Stoppzeit, berechnete Dauer. Einträge nachträglich bearbeiten und löschen.
- **Invariante:** Pro Nutzer darf höchstens **ein** Timer gleichzeitig laufen
  (auf Datenbankebene per partiellem Unique-Index abgesichert; beim Start eines
  neuen Timers wird ein noch laufender automatisch gestoppt).
- Endpunkt für den aktuell laufenden Timer (`GET /time/running`).
- Echtzeit-Sync über `/ws/time` (PROJECT_*/ENTRY_*-Events).
- **Auswertung im Web** (clientseitig aus den vorhandenen Einträgen berechnet,
  kein zusätzlicher Endpunkt):
    - „Letzte Einträge" sind nach Tag gruppiert; jede Gruppe zeigt eine
      Trennzeile mit Tageslabel (Heute / Gestern / Vorgestern / Wochentag /
      Datum) und der Tagessumme.
    - Ein Klick auf Name oder Gesamtzeit einer Projektkarte öffnet eine
      **Projekt-Detailansicht** mit vier Kennzahlen (Gesamt, diese Woche,
      Anzahl Einträge, ⌀ pro Eintrag), einer Aufschlüsselung **pro Nutzer**
      (sobald mehrere Nutzer Einträge haben) und der vollständigen,
      tagesgruppierten Eintragshistorie (Löschen weiterhin nur für eigene
      Einträge).
    - **Wochenübersicht** („Pro Woche") in der Detailansicht: eine Zeile je
      ISO-Woche (Montag-basiert), neueste zuerst, mit Wochensumme,
      Eintragsanzahl und einem nutzerweise segmentierten Balken (Breite je
      Segment ∝ Zeit des Nutzers, skaliert auf die aktivste Woche).

Felder Projekt: id, name, color, archived, created_by, created_at
Felder Zeiteintrag: id, project_id, user_id, started_at, stopped_at?,
description?, created_at, updated_at

### 6. Rezepte ✅
Rezeptsammlung mit Zutaten und Zubereitungsschritten.

- **Rezept:** Titel, Beschreibung, Portionen (≥ 1), Vorbereitungszeit,
  Kochzeit, Kategorie. CRUD; Liste optional nach Kategorie filterbar.
- **Kategorien:** BREAKFAST | LUNCH | DINNER | SNACK | DESSERT | DRINK
- **Zutaten:** Name, Menge, Einheit, Sortierreihenfolge.
- **Schritte:** nummerierte Zubereitungsschritte.
- Echtzeit-Sync über `/ws/recipes` (RECIPE_*-Events).

Felder Rezept: id, title, description?, servings, prep_time_minutes?,
cook_time_minutes?, category, created_by, created_at, updated_at
Felder Zutat: id, recipe_id, name, amount?, unit?, sort_order
Felder Schritt: id, recipe_id, step_number, description

---

## Post-MVP (Backlog)
- Mehrsprachigkeit / Sprachumschalter (Texte sind bereits externalisiert) 🔜
- Rezept-Zutaten direkt auf die Einkaufsliste übernehmen 🔜
- Zeiterfassung: server-seitige Reports / CSV-Export 🔜
  *(In-App-Auswertung pro Projekt/Woche ist umgesetzt ✅)*
- Angebote zu Einkaufsitems (Rewe/Kaufland) 🔜
- Wiederkehrende Todos 🔜
- Bilder in Notizen 🔜
- iOS-App 🔜

---

## Tech-Stack
- Backend:  Kotlin + Ktor, PostgreSQL 16, WebSockets, Flyway
- Web:      React + Vite + TypeScript + Tailwind CSS
- Android:  Jetpack Compose
- Proxy:    Nginx (Reverse Proxy, HTTPS)
- Digest:   Telegram Bot API
- Hosting:  Synology NAS, erreichbar via DynDNS + Port 443
- CI/CD:    GitHub Actions (Backend-, Web-, Android-Build + Docker-Image-Build)

## Nicht im MVP
- Push Notifications (Android)
- Offline-Fähigkeit
- Mehrere Haushalte / Teams
- VPN-Pflicht
