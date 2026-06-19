# Screenshot-Pipeline (README-Showcase)

Reproduzierbare Screenshots der **echten Apps** für den README-Showcase
(`docs/screenshots/*.png`). Ersetzt die früheren Standalone-Mockups unter
`docs/web` / `docs/android`, die in PR #287 entfernt wurden (Issue #300).

Alle Shots sind **rahmenlos** (kein Gerät-Bezel): Web als 1440×920 @2×
(2880×1840 px) gerendert, Android als roher `screencap` (~976×1920 px, je nach
Emulator-Display). Dateinamen sind fix — der README bettet sie genau so ein.
Bitte beim Neu-Rendern die Namen **nicht** ändern.

| Datei | Bereich |
|-------|---------|
| `web-dashboard.png` / `web-dashboard-dark.png` | Dashboard / „Heute" (hell + dunkel) |
| `web-aufgaben.png` | Aufgaben (Listen-Tabs, Fälligkeitsgruppen) |
| `web-einkauf.png` | Einkaufslisten |
| `web-notizen.png` | Notizen (Liste + gerenderte Markdown-Vorschau) |
| `web-zeit.png` | Zeiterfassung (laufender Timer, Wochensoll, Einträge) |
| `web-rezepte.png` | Rezepte (Karten-Grid) |
| `web-abwesenheit.png` / `web-abwesenheit-dark.png` | Familienkalender / Jahresraster (hell + dunkel) |
| `android-dashboard.png` | Android Dashboard |
| `android-aufgaben.png` | Android Aufgaben |
| `android-einkauf.png` | Android Einkaufsliste |
| `android-abwesenheit.png` | Android Jahresraster |
| `android-notiz-detail.png` | Android Notiz-Detail |
| `android-rezepte.png` | Android Rezepte |
| `android-zeit.png` | Android Zeiterfassung |

---

## Web — vollautomatisch, ohne Backend

Der Web-Renderer fährt die echte App hoch und steuert sie mit Playwright. Das
Backend wird mit demselben In-Memory-`MockApi` gefälscht, das auch die
E2E-Suite nutzt (`web/e2e/helpers/mockApi.ts`), befüllt mit einem realistischen
deutschen Haushalt (`seed.ts`). Es braucht **kein** Backend, keine DB und keinen
Emulator.

```bash
# Alle Web-Shots (baut web/, startet vite preview, rendert, schreibt PNGs):
scripts/screenshots/render-web.sh

# Nur einen Shot (Argument = Test-Titel):
scripts/screenshots/render-web.sh web-rezepte

# Gegen den Dev-Server statt des Prod-Builds (schneller beim Iterieren):
SCREENSHOT_SERVER=dev scripts/screenshots/render-web.sh
```

Beim ersten Lauf installiert das Skript `web/`-Abhängigkeiten und den
Chromium-Browser von Playwright.

**Wie es funktioniert**
- `render-web.spec.ts` läuft auf dem **Playwright-Test-Runner** (TS „funktioniert
  einfach", der Dev-/Preview-Server wird über `webServer` der Config verwaltet) —
  ist aber ein *Renderer*, keine Assertion-Suite: jeder `test` steuert eine
  Ansicht an und schreibt ein PNG. Damit ist der Renderer auch sein eigener
  Smoke-Test: schlägt eine Ansicht fehl (z. B. weil ein Selektor nach einem
  UI-Umbau nicht mehr passt), wird der Lauf rot.
- `playwright.screenshots.config.ts` setzt Viewport (1440×920), `deviceScaleFactor`
  2, Locale `de-DE`, Zeitzone `Europe/Berlin` und den Server.
- Auth: ein JWT mit `{username:"max"}` landet in `localStorage`; die App
  loggt sich daraus automatisch ein (kein Login-Formular) und behandelt „max" als
  aktuellen Nutzer (Begrüßung „Hallo, Max", „Lea" als Partner).
- Dunkel-Varianten kommen über `prefers-color-scheme: dark` (Default-Theme ist
  `system`, das auf das dunkle Token-Set auflöst).
- Die Uhr ist auf einen festen Zeitpunkt gepinnt, damit „heute/morgen"-Buckets
  und der laufende Timer zwischen Läufen deterministisch sind.

**Inhalt anpassen:** Seed-Daten in `seed.ts` ändern (nutzt die Factory-Helfer
aus `web/e2e/helpers/mockApi.ts`, ist also gegen die echten App-Typen geprüft).
Eine neue Ansicht: einen `test`-Block in `render-web.spec.ts` ergänzen.

---

## Android — Emulator + lokales Backend

Android braucht einen laufenden Emulator und ein lokales Backend (der
Debug-Build zeigt fest auf `http://10.0.2.2:8080`, also Emulator → Host).
`render-android.sh` automatisiert Login + Seed über die REST-API und
`adb exec-out screencap` für jede Ansicht. **Rahmenlos** — es wird **kein**
Gerät-Rahmen einkomponiert (bewusste Entscheidung, konsistent zu den Web-Shots;
die früheren Android-Shots hatten noch einen gezeichneten Bezel).

Voraussetzungen (verifizierte Prozedur, 2026-06-16):

1. **Postgres**
   ```bash
   docker compose -f docker-compose.dev.yml up -d
   ```
2. **Backend mit Seed-Nutzern** — Build braucht JDK 21 (Default-JDK 26 stirbt mit
   `IllegalArgumentException: 26`), also `JAVA_HOME` auf ein sdkman-JDK-21 pinnen.
   Seed-Nutzer via Env `SEED_USERS` (Format `user:passwort`, mehrere mit `,`
   getrennt — siehe `backend/src/main/kotlin/com/homebase/db/UserSeeder.kt`):
   ```bash
   export JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.5-tem"   # Beispiel
   (cd backend && SEED_USERS="max:test1234,lea:test1234" ./gradlew run)
   ```
3. **Emulator** — AVD `hb_test` starten, dann Auflösung/Dichte und Sprache setzen
   (der Emulator kann auf eine etwas kleinere Höhe clampen, z. B. 976×1920 —
   das ist ok, der `screencap` nimmt die echte Framebuffer-Größe):
   ```bash
   adb shell wm size 976x1936
   adb shell wm density 480
   # System-Locale auf Deutsch (die README-Shots sind deutsch):
   adb shell "setprop persist.sys.locale de-DE; setprop persist.sys.language de; setprop persist.sys.country DE"
   adb shell stop && adb shell start    # Locale-Wechsel braucht einen Framework-Neustart
   ```
4. **App** (Debug, zeigt auf `10.0.2.2:8080`) bauen + installieren — JDK 21 +
   `ANDROID_HOME` nötig:
   ```bash
   (cd android && ./gradlew installDebug)
   ```

Dann rendern:

```bash
# Login + Seed + alle Android-Shots:
BASE_URL=http://localhost:8080 HB_USER=max HB_PASS=test1234 \
  scripts/screenshots/render-android.sh
```

Das Skript:
- meldet sich per `POST /api/v1/auth/login` an und seedet über die REST-API
  einen Inhalt, der die Web-Shots spiegelt (Listen, Todos, Einkauf, Rezepte,
  eine Notiz, ein paar Abwesenheiten),
- öffnet je Ansicht den passenden Screen (Deep-Navigation über die Bottom-Nav
  bzw. den Drawer per `adb`-Input) und macht einen rohen `screencap`,
- schreibt `docs/screenshots/android-<view>.png` (rahmenlos, ~976×1920).

> Lässt sich der Emulator in deiner Umgebung nicht starten, ist das Skript +
> diese Prozedur trotzdem eingecheckt; auf einer Maschine mit Emulator einmal
> laufen lassen, um die Android-Shots zu aktualisieren.

---

## Konventionen / Stolpersteine

- **Dateinamen fix lassen** — der README bettet sie mit festen `width=`-Werten
  ein. Auflösung möglichst gleich halten (Web 2880×1840, Android ~976×1920),
  sonst verschiebt sich das Layout im README.
- **Web rahmenlos @2×** wie schon `web-dashboard.png` aus PR #287.
- **Config/Spec liegen außerhalb `web/`**, darum verweist `render-web.sh` per
  `NODE_PATH` auf `web/node_modules` (sonst findet Node `@playwright/test` /
  die E2E-Helfer nicht).
- Bricht ein Web-Shot mit Timeout ab, hat sich vermutlich ein CSS-Selektor durch
  einen UI-Umbau verschoben — die Selektoren in `render-web.spec.ts` an die
  Ansicht anpassen (analog zu den Locators in `web/e2e/*.spec.ts`).
