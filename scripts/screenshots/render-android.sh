#!/usr/bin/env bash
#
# Render the Android README screenshots from the real app (issue #300).
#
# Drives a running emulator: logs into the backend, seeds realistic German
# content over the REST API (mirroring the web shots), then opens each screen
# and takes a RAW `screencap` — frameless, no device bezel composited on top
# (deliberate, consistent with the web shots).
#
# This script does NOT start the emulator / backend / DB — see README.md in this
# folder for the full prerequisite setup (Postgres, backend with SEED_USERS,
# emulator at 976×1936 + German locale, `installDebug`). Run those first, then:
#
#   BASE_URL=http://localhost:8080 HB_USER=max HB_PASS=test1234 \
#     scripts/screenshots/render-android.sh
#
# Env:
#   BASE_URL   backend base URL as reached from THIS host (default http://localhost:8080)
#   HB_USER    seed username to log in as (default max)
#   HB_PASS    that user's password   (default test1234)
#   APP_ID     Android applicationId  (default com.homebase.android)
#   ADB_SERIAL target device          (default: the only attached device)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUT_DIR="$REPO_ROOT/docs/screenshots"

BASE_URL="${BASE_URL:-http://localhost:8080}"
API="$BASE_URL/api/v1"
HB_USER="${HB_USER:-max}"
HB_PASS="${HB_PASS:-test1234}"
APP_ID="${APP_ID:-com.homebase.android}"

ADB=(adb)
if [ -n "${ADB_SERIAL:-}" ]; then ADB=(adb -s "$ADB_SERIAL"); fi

PY="${PYTHON:-python3}"

require() { command -v "$1" >/dev/null 2>&1 || { echo "Missing required tool: $1" >&2; exit 1; }; }
require adb
require curl
require "$PY"

"${ADB[@]}" get-state >/dev/null 2>&1 || { echo "No adb device. Start the emulator first (see README.md)." >&2; exit 1; }

# ---------------------------------------------------------------------------
# 1) Log in
# ---------------------------------------------------------------------------
echo "Logging in as $HB_USER → $API …"
TOKEN="$(curl -fsS -X POST "$API/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$HB_USER\",\"password\":\"$HB_PASS\"}" \
  | "$PY" -c 'import sys,json; print(json.load(sys.stdin)["token"])')"
[ -n "$TOKEN" ] || { echo "Login failed — check the backend + SEED_USERS." >&2; exit 1; }

# Small REST helper: api METHOD PATH [JSON]
api() {
  local method="$1" path="$2" body="${3:-}"
  if [ -n "$body" ]; then
    curl -fsS -X "$method" "$API$path" \
      -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$body"
  else
    curl -fsS -X "$method" "$API$path" -H "Authorization: Bearer $TOKEN"
  fi
}
# Extract the "id" of a JSON object response.
json_id() { "$PY" -c 'import sys,json; print(json.load(sys.stdin).get("id",""))'; }

# ---------------------------------------------------------------------------
# 2) Seed content (idempotency: only seeds when the household is still empty)
# ---------------------------------------------------------------------------
EXISTING_LISTS="$(api GET /todos/lists | "$PY" -c 'import sys,json; print(len(json.load(sys.stdin)))')"
if [ "$EXISTING_LISTS" -gt 0 ]; then
  echo "Backend already has $EXISTING_LISTS todo list(s) — skipping seed."
else
  echo "Seeding realistic German content …"

  iso_day() { "$PY" -c "import sys,datetime; print((datetime.date.today()+datetime.timedelta(days=int(sys.argv[1]))).isoformat())" "$1"; }
  YEAR="$("$PY" -c 'import datetime; print(datetime.date.today().year)')"
  PARTNER="lea"; [ "$HB_USER" = "lea" ] && PARTNER="max"

  # -- Todo lists --
  L_HAUS="$(api POST /todos/lists '{"name":"Haushalt","visibility":"SHARED"}' | json_id)"
  L_FAM="$(api POST /todos/lists '{"name":"Familie & Termine","visibility":"SHARED"}' | json_id)"
  api POST /todos/lists '{"name":"Persönlich","visibility":"PRIVATE"}' >/dev/null

  # -- Todos (inbox / planned today+soon / a couple done) --
  mk_todo() { api POST /todos "$1" >/dev/null; }
  mk_todo "{\"title\":\"Geschenk für Mama besorgen\",\"listId\":\"$L_FAM\"}"
  mk_todo "{\"title\":\"Steuerunterlagen sortieren\",\"listId\":\"$L_HAUS\"}"
  mk_todo "{\"title\":\"Müll rausbringen\",\"description\":\"Gelber Sack + Restmüll.\",\"listId\":\"$L_HAUS\",\"assignee\":\"$HB_USER\",\"dueDate\":\"$(iso_day 0)\",\"priority\":\"MEDIUM\"}"
  mk_todo "{\"title\":\"Blumen auf dem Balkon gießen\",\"listId\":\"$L_HAUS\",\"assignee\":\"$PARTNER\",\"dueDate\":\"$(iso_day 0)\",\"priority\":\"LOW\"}"
  mk_todo "{\"title\":\"Stromzähler ablesen\",\"description\":\"Stand fotografieren und senden.\",\"listId\":\"$L_HAUS\",\"assignee\":\"$HB_USER\",\"dueDate\":\"$(iso_day 1)\",\"priority\":\"MEDIUM\"}"
  mk_todo "{\"title\":\"Auto zur Inspektion bringen\",\"listId\":\"$L_FAM\",\"assignee\":\"$PARTNER\",\"dueDate\":\"$(iso_day 3)\",\"priority\":\"HIGH\"}"
  # mark one done so "Heute erledigt" is non-zero
  DONE_ID="$(api POST /todos "{\"title\":\"Wocheneinkauf erledigt\",\"listId\":\"$L_HAUS\",\"assignee\":\"$HB_USER\",\"dueDate\":\"$(iso_day 0)\"}" | json_id)"
  api PUT "/todos/$DONE_ID" '{"status":"DONE"}' >/dev/null

  # -- Shopping --
  S_WOCHE="$(api POST /shopping/lists '{"name":"Wocheneinkauf"}' | json_id)"
  api POST /shopping/lists '{"name":"Drogerie"}' >/dev/null
  for item in "Äpfel" "Bananen" "Tomaten" "Milch (1,5%)" "Naturjoghurt" "Gouda am Stück" "Filterkaffee"; do
    api POST /shopping "{\"name\":\"$item\",\"listId\":\"$S_WOCHE\"}" >/dev/null
  done
  # two checked items so the "Im Wagen" section is populated
  for item in "Babyspinat" "Butter"; do
    SID="$(api POST /shopping "{\"name\":\"$item\",\"listId\":\"$S_WOCHE\"}" | json_id)"
    api PUT "/shopping/$SID" '{"checked":true}' >/dev/null
  done

  # -- Recipes --
  api POST /recipes '{"title":"Fluffige Buttermilch-Pancakes","description":"Sonntagsklassiker — innen weich, außen goldbraun.","servings":4,"prepTimeMinutes":10,"cookTimeMinutes":15,"category":"BREAKFAST","ingredients":[{"name":"Mehl","amount":250,"unit":"g"},{"name":"Buttermilch","amount":300,"unit":"ml"},{"name":"Eier","amount":2,"unit":"Stk"},{"name":"Backpulver","amount":1,"unit":"TL"}],"steps":[{"description":"Trockene Zutaten vermengen."},{"description":"Buttermilch und Eier verquirlen, kurz verrühren."},{"description":"Portionsweise goldbraun backen."}]}' >/dev/null
  api POST /recipes '{"title":"Spaghetti Carbonara","description":"Original ohne Sahne — nur Ei, Pecorino und Pfeffer.","servings":2,"prepTimeMinutes":10,"cookTimeMinutes":15,"category":"DINNER","ingredients":[{"name":"Spaghetti","amount":250,"unit":"g"},{"name":"Guanciale","amount":120,"unit":"g"},{"name":"Eigelb","amount":3,"unit":"Stk"},{"name":"Pecorino","amount":60,"unit":"g"}],"steps":[{"description":"Spaghetti al dente kochen."},{"description":"Guanciale knusprig auslassen."},{"description":"Mit Ei-Pecorino-Mischung zügig zu einer Creme verrühren."}]}' >/dev/null
  api POST /recipes '{"title":"Herzhafte Linsensuppe","description":"Wärmt an kalten Tagen.","servings":4,"prepTimeMinutes":15,"cookTimeMinutes":40,"category":"DINNER","ingredients":[{"name":"Tellerlinsen","amount":250,"unit":"g"},{"name":"Suppengrün","amount":1,"unit":"Bund"},{"name":"Kartoffeln","amount":2,"unit":"Stk"}],"steps":[{"description":"Gemüse anschwitzen."},{"description":"Linsen und Brühe zugeben, köcheln."}]}' >/dev/null
  api POST /recipes '{"title":"Saftiger Schokoladenkuchen","description":"Einfach, schokoladig, gelingt immer.","servings":12,"prepTimeMinutes":20,"cookTimeMinutes":35,"category":"DESSERT","ingredients":[{"name":"Mehl","amount":200,"unit":"g"},{"name":"Zucker","amount":180,"unit":"g"},{"name":"Kakao","amount":40,"unit":"g"}],"steps":[{"description":"Ofen auf 175 °C vorheizen."},{"description":"Zutaten verrühren und backen."}]}' >/dev/null
  api POST /recipes '{"title":"Dattel-Energy-Balls","description":"Schneller Snack ohne Backen.","servings":10,"prepTimeMinutes":15,"cookTimeMinutes":0,"category":"SNACK","ingredients":[{"name":"Datteln","amount":150,"unit":"g"},{"name":"Haferflocken","amount":100,"unit":"g"}],"steps":[{"description":"Alles pürieren."},{"description":"Zu Kugeln rollen und kühlen."}]}' >/dev/null
  api POST /recipes '{"title":"Pfirsich-Eistee","description":"Erfrischend für warme Nachmittage.","servings":4,"prepTimeMinutes":5,"cookTimeMinutes":0,"category":"DRINK","ingredients":[{"name":"Schwarztee","amount":3,"unit":"Beutel"},{"name":"Pfirsich","amount":2,"unit":"Stk"}],"steps":[{"description":"Tee aufgießen und abkühlen lassen."},{"description":"Mit Pfirsichpüree mischen, über Eis servieren."}]}' >/dev/null

  # -- A note (for the notiz-detail shot) --
  api POST /notes '{"title":"Urlaubsplanung Sommer","visibility":"SHARED","tags":["urlaub","reise"],"content":"## Toskana, Ende Juli\n\nGrobe Idee für 10 Tage:\n\n- **Anreise** über Nacht, Stopp in Verona\n- 4 Nächte Florenz, dann 4 Nächte am Meer\n- Agriturismo statt Hotel — mehr Ruhe\n\n> Budget grob: **1.800 €** ohne Sprit\n\n### Noch klären\n1. Hund bei Oma oder Tierhotel?\n2. Mietwagen vor Ort vs. eigenes Auto"}' >/dev/null
  api POST /notes '{"title":"Ideen fürs Wohnzimmer","visibility":"SHARED","tags":["zuhause","deko"],"content":"### Umgestaltung\n\n- Großer Teppich in warmem Sandton\n- Mehr Pflanzen am Fenster"}' >/dev/null

  # -- Time tracking: projects, weekly targets, a few finished entries this week,
  #    plus a running timer so the Zeit screen shows the hero + Wochensoll + list.
  iso_ago() { "$PY" -c "import sys,datetime; print((datetime.datetime.now()-datetime.timedelta(minutes=int(sys.argv[1]))).astimezone().isoformat())" "$1"; }
  P_APP="$(api POST /time/projects '{"name":"Nebenprojekt: App","color":"#5b9e7a"}' | json_id)"
  P_STEUER="$(api POST /time/projects '{"name":"Steuererklärung","color":"#c9805a"}' | json_id)"
  P_LERNEN="$(api POST /time/projects '{"name":"Spanisch lernen","color":"#c2a14d"}' | json_id)"
  # Weekly targets (default project per person → forecast/Wochensoll renders).
  api PUT "/time/targets/$HB_USER/$P_APP"     '{"weeklyHours":8,"isDefault":true}'  >/dev/null
  api PUT "/time/targets/$HB_USER/$P_LERNEN"  '{"weeklyHours":2}'                    >/dev/null
  api PUT "/time/targets/$PARTNER/$P_STEUER"  '{"weeklyHours":4,"isDefault":true}'   >/dev/null
  # Finished entries (descending-recent). userId targets the household member.
  mk_entry() { api POST /time/entries "{\"projectId\":\"$1\",\"userId\":\"$2\",\"startedAt\":\"$(iso_ago "$3")\",\"stoppedAt\":\"$(iso_ago "$4")\",\"description\":\"$5\"}" >/dev/null; }
  mk_entry "$P_APP"    "$HB_USER" 2400 2280 "Notizen-Editor"
  mk_entry "$P_LERNEN" "$HB_USER" 2900 2855 "Vokabeln Einheit 4"
  mk_entry "$P_STEUER" "$PARTNER" 1500 1410 "Belege scannen"
  mk_entry "$P_APP"    "$HB_USER" 12120 12000 "Kalender-Ansicht"
  # A running timer for the current user (no stoppedAt) → the hero shows it live.
  api POST /time/entries/start "{\"projectId\":\"$P_APP\",\"description\":\"Sync-Bug nachstellen\"}" >/dev/null

  # -- Absence: settings + a few entries so the year grid shows colour runs --
  api PUT "/absence/settings/$HB_USER/$YEAR"  "{\"state\":\"BE\",\"allowance\":30,\"carryover\":5,\"kindKrankCap\":15}" >/dev/null
  api PUT "/absence/settings/$PARTNER/$YEAR" "{\"state\":\"BY\",\"allowance\":24,\"carryover\":0,\"kindKrankCap\":15}" >/dev/null
  api POST /absence/entries/batch "{\"userId\":\"$HB_USER\",\"type\":\"URLAUB\",\"dates\":[\"$YEAR-03-16\",\"$YEAR-03-17\",\"$YEAR-03-18\",\"$YEAR-03-19\",\"$YEAR-03-20\",\"$YEAR-07-27\",\"$YEAR-07-28\",\"$YEAR-07-29\"]}" >/dev/null
  api POST /absence/entries "{\"userId\":\"$HB_USER\",\"date\":\"$YEAR-05-11\",\"type\":\"KRANK\"}" >/dev/null
  api POST /absence/entries/batch "{\"userId\":\"$PARTNER\",\"type\":\"URLAUB\",\"dates\":[\"$YEAR-07-27\",\"$YEAR-07-28\",\"$YEAR-07-29\"]}" >/dev/null
  api POST /absence/kita/range "{\"from\":\"$YEAR-07-27\",\"to\":\"$YEAR-07-29\",\"label\":\"Sommerschließung\"}" >/dev/null

  echo "Seed complete."
fi

# ---------------------------------------------------------------------------
# 3) Launch the app and capture each screen
# ---------------------------------------------------------------------------
mkdir -p "$OUT_DIR"

# Tap the on-screen element whose visible text equals $1 (resolution-independent:
# dumps the view hierarchy and taps the matched node's centre). Returns non-zero
# if not found.
tap_text() {
  local want="$1"
  "${ADB[@]}" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 1
  "${ADB[@]}" pull /sdcard/ui.xml /tmp/hb_ui.xml >/dev/null 2>&1 || return 1
  local xy
  xy="$("$PY" - "$want" <<'PYEOF'
import re, sys, xml.etree.ElementTree as ET
want = sys.argv[1]
try:
    root = ET.parse('/tmp/hb_ui.xml').getroot()
except Exception:
    sys.exit(1)
for n in root.iter('node'):
    if n.get('text') == want or n.get('content-desc') == want:
        m = re.findall(r'\d+', n.get('bounds', ''))
        if len(m) == 4:
            x1, y1, x2, y2 = map(int, m)
            print(f'{(x1+x2)//2} {(y1+y2)//2}')
            sys.exit(0)
sys.exit(1)
PYEOF
)" || return 1
  [ -n "$xy" ] || return 1
  # shellcheck disable=SC2086
  "${ADB[@]}" shell input tap $xy
}

wait_text() {  # wait_text TEXT [tries]
  local want="$1" tries="${2:-30}"
  for _ in $(seq 1 "$tries"); do
    "${ADB[@]}" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
    "${ADB[@]}" pull /sdcard/ui.xml /tmp/hb_ui.xml >/dev/null 2>&1 || true
    if grep -q "text=\"$want\"" /tmp/hb_ui.xml 2>/dev/null \
       || grep -q "content-desc=\"$want\"" /tmp/hb_ui.xml 2>/dev/null; then
      return 0
    fi
    sleep 1
  done
  return 1
}

snap() { "${ADB[@]}" exec-out screencap -p > "$OUT_DIR/$1.png"; echo "✓ $1.png"; }

# Tap the centre of the Nth (1-based) EditText on screen — the login fields carry
# no resource-id, so we address them by document order (1=username, 2=password).
tap_edittext() {
  local idx="$1"
  "${ADB[@]}" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 1
  "${ADB[@]}" pull /sdcard/ui.xml /tmp/hb_ui.xml >/dev/null 2>&1 || return 1
  local xy
  xy="$("$PY" - "$idx" <<'PYEOF'
import re, sys, xml.etree.ElementTree as ET
idx = int(sys.argv[1])
root = ET.parse('/tmp/hb_ui.xml').getroot()
edits = [n for n in root.iter('node') if n.get('class','').endswith('EditText')]
if len(edits) < idx:
    sys.exit(1)
m = re.findall(r'\d+', edits[idx-1].get('bounds',''))
x1,y1,x2,y2 = map(int, m)
print(f'{(x1+x2)//2} {(y1+y2)//2}')
PYEOF
)" || return 1
  # shellcheck disable=SC2086
  "${ADB[@]}" shell input tap $xy
}

# Type a string into the currently focused field. `adb input text` doesn't handle
# spaces/special chars, so escape spaces as %s and avoid them where possible.
type_text() { "${ADB[@]}" shell input text "${1// /%s}"; }

echo "Launching the app …"
"${ADB[@]}" shell am force-stop "$APP_ID" || true
# Clean slate so the app shows its login screen (no stale/expired token from a
# previous session, which would silently 401 and render empty views).
"${ADB[@]}" shell pm clear "$APP_ID" >/dev/null 2>&1 || true
"${ADB[@]}" shell am start -n "$APP_ID/.MainActivity" >/dev/null 2>&1
sleep 4

# In-app login (the app has its own auth screen; seeding via REST above only
# fills the DB — the app still needs to authenticate against the backend).
if wait_text "Anmelden" 30; then
  echo "Logging the app in as $HB_USER …"
  tap_edittext 1 && type_text "$HB_USER"
  "${ADB[@]}" shell input keyevent 111   # ESC to dismiss the soft keyboard/IME
  tap_edittext 2 && type_text "$HB_PASS"
  "${ADB[@]}" shell input keyevent 111
  tap_text "Anmelden" || true
  sleep 1
fi
# Wait for the dashboard greeting to confirm we're in (German "Hallo,").
wait_text "Heute" 30 >/dev/null 2>&1 || wait_text "Start" 30 >/dev/null 2>&1 || \
  echo "  (app didn't reach the dashboard in time — continuing anyway)"
sleep 2

# Capture is best-effort per shot — a single missed tap must not abort the run.
set +e

go_dash()   { tap_text "Start" >/dev/null 2>&1; sleep 1; }
open_more() { tap_text "Mehr"  >/dev/null 2>&1; sleep 1; }

# -- Dashboard --
snap "android-dashboard"

# Bottom-nav primaries (short German labels).
tap_text "Aufgaben" >/dev/null 2>&1; sleep 2; snap "android-aufgaben"; go_dash
tap_text "Einkauf"  >/dev/null 2>&1; sleep 2; snap "android-einkauf";  go_dash
tap_text "Zeit"     >/dev/null 2>&1; sleep 2; snap "android-zeit";     go_dash

# Overflow areas live behind the "Mehr" sheet (full labels there).
open_more; tap_text "Rezepte"  >/dev/null 2>&1; sleep 2; snap "android-rezepte";     go_dash
open_more; tap_text "Kalender" >/dev/null 2>&1; sleep 2
tap_text "Jahr" >/dev/null 2>&1 && sleep 2   # year grid (matches the README "Jahresraster" caption)
snap "android-abwesenheit"; go_dash
open_more; tap_text "Notizen"  >/dev/null 2>&1; sleep 2
tap_text "Urlaubsplanung Sommer" >/dev/null 2>&1 && sleep 2
snap "android-notiz-detail"; go_dash

echo "Done. Android shots written to $OUT_DIR/ (frameless screencaps)."
