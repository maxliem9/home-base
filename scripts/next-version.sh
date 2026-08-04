#!/usr/bin/env bash
# Bestimmt die NÄCHSTE Produktversion für einen Merge auf main (Issue #630).
#
# WOZU:
#   Seit #626 ist `VERSION` im Repo-Root die einzige Quelle der Produktversion — aber nichts
#   erzwang, dass sie gepflegt wird. Man konnte beliebig oft nach main mergen, ohne zu bumpen;
#   Backend/Web/Android zeigten dann eine Version an, die nichts mehr aussagte. Statt einen
#   Guard zu bauen, der ans Bumpen *erinnert*, bumpt die Pipeline jetzt selbst: dieses Skript
#   liefert die Zahl, der `release`-Job in ci.yml schreibt/committet/taggt sie.
#
# EINGABE:
#   COMMIT_MESSAGE (env) — die Message des gemergten Commits. Bewusst per env und NICHT als
#   `${{ github.event.head_commit.message }}` direkt in einen run-Block interpoliert: eine
#   Commit-Message ist beliebiger, von außen wählbarer Text und würde dort als Shell-Code
#   landen (Script-Injection).
#
# REGELN (Conventional Commits — bei Squash-Merge ist das der PR-Titel):
#   `feat!:` / `feat(x)!:` / "BREAKING CHANGE" im Body  → major   (1.2.3 → 2.0.0)
#   `feat:` / `feat(scope):`                            → minor   (1.2.3 → 1.3.0)
#   alles andere (fix, chore, docs, refactor, test, …)  → patch   (1.2.3 → 1.2.4)
#
# BASIS + MANUELLE ÜBERSTEUERUNG:
#   Basis ist der höchste vorhandene `v*`-Tag. Steht in `VERSION` bereits eine HÖHERE Version
#   als der letzte Tag, wurde von Hand gebumpt — dann gilt diese Zahl unverändert (kein
#   zusätzlicher Bump obendrauf). Das ist die Notausstiegs-Luke für „diese Änderung ist mir
#   ein 2.0.0 wert" und zugleich der Bootstrap: ohne jeden Tag wird das aktuelle `VERSION`
#   (1.1.0) zur ersten getaggten Release, statt sie zu 1.1.1 zu überspringen.
#
# AUSGABE: die nächste Version auf stdout (z. B. `1.1.1`), Diagnose auf stderr.
#
# LOKAL: `COMMIT_MESSAGE="feat: neues Ding" bash scripts/next-version.sh`
set -euo pipefail

cd "$(dirname "$0")/.."

msg="${COMMIT_MESSAGE:-}"
[ -n "$msg" ] || { echo "next-version: COMMIT_MESSAGE ist leer oder nicht gesetzt." >&2; exit 1; }

# --- 1) Bump-Level aus der Commit-Message ------------------------------------------------
# Nur die ERSTE Zeile ist der Conventional-Commit-Header; "feat:" mitten im Body darf nicht
# zählen. "BREAKING CHANGE" hingegen steht laut Konvention im Footer, wird also im ganzen
# Text gesucht.
header="$(printf '%s' "$msg" | head -1)"

level="patch"
if printf '%s' "$header" | grep -qE '^[a-zA-Z]+(\([^)]*\))?!:' \
   || printf '%s' "$msg" | grep -qE 'BREAKING[ -]CHANGE'; then
  level=major
elif printf '%s' "$header" | grep -qE '^feat(\([^)]*\))?:'; then
  level=minor
fi

# --- 2) Basis: höchster v*-Tag -----------------------------------------------------------
# `sort -V` statt lexikografisch, sonst gilt v1.9.0 > v1.10.0. Ohne Tags: 0.0.0.
last_tag="$(git tag -l 'v[0-9]*.[0-9]*.[0-9]*' | sed 's/^v//' \
            | grep -E '^[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -1 || true)"
last_tag="${last_tag:-0.0.0}"

file_version="$(tr -d '[:space:]' < VERSION)"
if ! printf '%s' "$file_version" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'; then
  echo "next-version: VERSION enthält kein X.Y.Z (gelesen: '$file_version')." >&2
  exit 1
fi

# --- 3) Manueller Bump schlägt Automatik -------------------------------------------------
# Höchster der beiden Werte gewinnt; ist das die Datei, wurde von Hand vorgebumpt.
highest="$(printf '%s\n%s\n' "$last_tag" "$file_version" | sort -V | tail -1)"
if [ "$highest" = "$file_version" ] && [ "$file_version" != "$last_tag" ]; then
  echo "next-version: VERSION ($file_version) liegt über dem letzten Tag ($last_tag) — manueller Bump, übernehme sie unverändert." >&2
  printf '%s\n' "$file_version"
  exit 0
fi

# --- 4) Automatischer Bump ---------------------------------------------------------------
IFS=. read -r major minor patch <<< "$last_tag"
case "$level" in
  major) major=$((major + 1)); minor=0; patch=0 ;;
  minor) minor=$((minor + 1)); patch=0 ;;
  patch) patch=$((patch + 1)) ;;
esac

echo "next-version: '$header' ⇒ $level-Bump, $last_tag → $major.$minor.$patch" >&2
printf '%s.%s.%s\n' "$major" "$minor" "$patch"
