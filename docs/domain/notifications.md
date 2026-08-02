# Benachrichtigungen: Telegram-Digest, Todo-Erinnerungen, Web Push

> Lies dies, bevor du an Digests, Erinnerungen oder Web-Push arbeitest.
> Zugehörige env-Variablen (Secrets/Gating: `TELEGRAM_*`, `VAPID_*`, `RECURRING_TIME`) siehe [`.env.example`](../../.env.example).

## Telegram Digest
- Kotlin-Coroutine-basierter Scheduler im Backend; ein `DigestScheduler` pro Digest, beide
  über denselben Bot/Chat. Fehlen TELEGRAM_BOT_TOKEN/TELEGRAM_CHAT_ID (env-Secrets), ruhen beide.
- **Zwei tägliche Digests:** Abend-Recap (`DigestService`, Default 20:00 — heute erledigt /
  neu in Inbox / morgen fällig) und Morgen-Briefing (`MorningDigestService`, Default 07:00 —
  heute fällig / überfällig / Inbox / heute abwesend / Kita heute zu).
- **Pro Digest in-app konfigurierbar** (Einstellungen → Benachrichtigungen, `app_settings`,
  pro Zyklus frisch gelesen — kein Neustart): Uhrzeit (`DIGEST_TIME` / `MORNING_DIGEST_TIME`),
  An/Aus (`DIGEST_*_ENABLED`, unset = an) und Inhalts-Auswahl per Checkbox (`DIGEST_*_SECTIONS`,
  CSV von `DigestSection`-IDs; unset = alle Sektionen, leer **gespeichert** = keine). Gesendet
  wird nur, wenn `enabled && telegramConfigured` und mindestens eine **gewählte** Sektion Inhalt
  hat. Lange Sektionen werden bei 20 Einträgen + „… und X weitere" gekappt (#167).
- **Abend-Digest** enthält zusätzlich eine Vorschau auf **morgen** (wer ist morgen abwesend,
  Kita morgen zu) via geteiltem `familyCalendarFor(date)` — deshalb kann er auch an einem sonst
  stillen Tag feuern, wenn nur diese Vorschau Inhalt hat (#182). Web-UI fertig; Android-Spiegelung
  der Digest-Einstellungen offen (#189).

## Todo-Erinnerungen (#429 Phase 2a + 2b)
Sofortige, per-Todo-Erinnerung über **denselben Telegram-Bot** wie die Digests **und/oder
Browser-Web-Push** (Phase 2b) — die erste *unmittelbare* Benachrichtigung (Digest = nur 2× täglich).
Der Scheduler läuft, sobald **mindestens ein** Kanal konfiguriert ist; ohne Telegram **und** ohne
VAPID ruht er komplett.
- **Scheduler** (`reminder/ReminderScheduler` → `ReminderService`): enger Tick (60 s, nicht
  täglich wie der Digest), damit eine Erinnerung nahe der Fälligkeitszeit feuert. Liest seine
  Settings pro Tick neu (kein Neustart).
- **Opt-in über die Uhrzeit:** ein Todo erinnert nur, wenn es eine **Fälligkeits-Uhrzeit**
  (`due_time`, #429 Phase 1) trägt — rein datierte Todos pingen nicht. Feuerzeitpunkt =
  `due_time` − optionaler `reminder_lead_minutes`. Reine Logik in `ReminderLogic` (unit-getestet).
- **Fire-once:** `todos.reminder_sent_at` (V37) wird beim Senden **oder** Verwerfen gestempelt;
  vor dem Senden gesetzt (Fire-once schlägt Best-effort-Zustellung — lieber eine verlorene als eine
  doppelte). Ein zu altes (> 12 h `CATCHUP`) Reminder wird **still verworfen** (kein Spam nach
  Deploy/Downtime). Wird das Fälligkeits-Moment editiert, **re-armt** der PUT (`reminder_sent_at`
  zurück auf NULL). Wiederkehrende Nachfolger erben NULL → eigene Erinnerung.
- **In-app konfigurierbar** (Einstellungen → Benachrichtigungen, `app_settings`, pro Tick gelesen):
  An/Aus (`REMINDERS_ENABLED`, unset = an) + optionale **Ruhezeiten** (`REMINDER_QUIET_START/END`,
  „HH:mm", paarweise; in der Ruhezeit wird der ganze Pass übersprungen, Erinnerungen kommen danach
  nach). Endpunkt `/config/reminders` (GET/PUT). **Privacy:** gesendet wird an den einen gemeinsamen
  Chat (+ alle Push-Geräte), ohne Pro-*Nutzer*-Zustellung. Todos in einer **PRIVATE-Liste werden
  jedoch ausgeblendet** — sonst leakte ihr Titel an die*den Partner*in (Helper
  `notifications/privateTodoListIds` + `ResultRow.todoIsShareable`, geteilt mit beiden Digests). Da
  die Kanäle geteilt sind, ist Weglassen die einzige datenschutzwahrende Option (auch der Eigentümer
  bekommt für private Todos keine Erinnerung). Listenlose und SHARED-Listen-Todos sind unberührt.
- **Zustell-Seam (Phase 2b):** `ReminderService` fragt nur *ob* gefeuert wird (`ReminderLogic`,
  rein) und übergibt den Text an einen `ReminderNotifier` — Kanal-agnostisch. In Prod ein
  `CompositeReminderNotifier` über `TelegramReminderNotifier` (wie Phase 2a) **+** `WebPushNotifier`;
  jeder Kanal ist best-effort + isoliert (ein Telegram-Ausfall unterdrückt kein Push und umgekehrt).
  Das Feuermodell (Fire-once, Ruhezeiten, Stale-Retire) bleibt unangetastet.
- **Web Push (Phase 2b, #429):** VAPID-Keypair per env (`VAPID_PUBLIC_KEY`/`VAPID_PRIVATE_KEY`/
  `VAPID_SUBJECT`; Generierung in `.env.example`). Alle drei gesetzt ⇒ aktiv, sonst dormant (wie
  Telegram gegatet). Lib `nl.martijndwars:web-push` + explizites BouncyCastle (im web-push-POM nur
  *optional*, muss daher selbst auf den Klassenpfad). `WebPushNotifier` sendet an **jede** gespeicherte
  Subscription und **pruned** 404/410 („gone"). DB-Tabelle `push_subscriptions` (V38: endpoint UNIQUE,
  p256dh, auth, username, created_at). Endpunkte unter `/api/v1/push` (JWT): `GET /vapid-public-key`
  (404 wenn nicht konfiguriert), `POST /subscribe` (Upsert auf endpoint), `DELETE /subscribe` (idempotent).
  Zustellung haushaltsweit (an alle Subscriptions, wie der geteilte Digest-Chat); `username` ist nur
  informativ. **Web/PWA:** manueller Service-Worker `web/public/sw.js` (`push`→`showNotification`,
  `notificationclick`→fokussieren/öffnen; **kein** Asset-Caching — HomeBase ist nicht offline-first),
  in `main.tsx` registriert; Opt-in **pro Gerät** in `NotificationsSettings` („Browser-Benachrichtigungen").
  Helper in `web/src/lib/webpush.ts`. **Hinweis:** der eigentliche Subscribe-Flow (ServiceWorker + Push
  API) braucht einen echten Browser über HTTPS/localhost — Unit-Tests decken nur die Capability-Gate ab.

## Android-Erinnerungen (#429 Phase 2c) — gerätelokal
Kein FCM/Push: die App plant die Erinnerung **selbst auf dem Gerät**, aus der ohnehin geladenen
Todo-Liste. Kein Google-Dienst, kein Server-Kanal, kein Extra-Endpunkt.
- **Planung:** `notifications/ReminderPlan` (rein, unit-getestet) spiegelt die Backend-Regel — nur
  nicht-DONE Todos **mit Fälligkeits-Uhrzeit**, Feuerzeitpunkt = `dueTime` − `reminderLeadMinutes`,
  Verwerfen ab > 12 h `CATCHUP`. `ReminderScheduler` macht die Android-I/O: pro Todo ein verzögerter
  One-Shot-`ReminderWorker` (WorkManager, Unique-Name = Todo-Id, `REPLACE`), Cancel für Todos, die
  aus dem Plan fallen. Die geplanten Ids liegen in SharedPreferences, damit ein Prozess-Tod keine
  Waisen-Jobs hinterlässt; `cancelAll()` beim Logout.
- **Trigger:** `MainActivity` ruft `reminderScheduler.sync(todos)` bei jeder Änderung der Liste
  (Cold-Load, WS-Reload, Edit) — idempotent.
- **Permission:** `POST_NOTIFICATIONS` wird nach dem Login einmal angefragt; eine Ablehnung degradiert
  still (der Worker läuft, `notify()` entfällt).
- **Tap → Deep-Link auf das Todo:** die Notification trägt einen Content-Intent auf `MainActivity`
  (`ReminderWorker.openTodoIntent`, Action `…action.OPEN_TODO` + Extra `…extra.TODO_ID`). Die Activity
  läuft als `launchMode="singleTop"`, der Intent kommt also bei laufender App über `onNewIntent` an
  statt eine zweite Instanz zu stapeln. `MainActivity` hält die Id in Compose-State, navigiert nach
  Aufgaben (schließt Drawer/„Mehr"/Einstellungen) und reicht sie an `AufgabenScreen` weiter; das
  öffnet das Edit-Sheet, sobald das Todo in der Liste ist (Cold-Start: der Tap gewinnt gegen den
  ersten `/todos`-Fetch — bounded Wait, `DEEP_LINK_WAIT_MS`) und schaltet auf den „Alle"-Tab, damit
  das Todo hinter dem Sheet sichtbar ist. Bei Logout wartet der Deep-Link bis nach dem Login; wird
  das Todo nicht gefunden (gelöscht, veraltete Notification), verfällt er nach dem Timeout.
  Verbraucht wird er inkl. `removeExtra`, damit eine Activity-Recreation (Rotation) ihn nicht
  erneut auslöst.
