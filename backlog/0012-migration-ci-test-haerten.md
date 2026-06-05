---
id: 0012
title: Migrations-Integrationstest + CI-Skip-Guard härten (Folge aus #35)
status: backlog
category: tech-debt
priority: low
source: PR #35 (Review session 2026-06-05)
created: 2026-06-05
---

# 0012 — Migrations-Integrationstest + CI-Skip-Guard härten

## Kontext
PR #35 hat einen CI-Job `migrations` (`.github/workflows/ci.yml`) plus
`MigrationIntegrationTest` (`backend/src/test/kotlin/com/homebase/MigrationIntegrationTest.kt`)
ergänzt, der die echten Flyway-Migrationen gegen ein Wegwerf-Postgres laufen lässt und die
Writes ausführt, die die H2-Unit-Suite nicht abdeckt.

Das Review (session 2026-06-05) hat zwei kleine Robustheits-/Hygiene-Punkte gefunden —
**kein aktueller Defekt**: der Skip-Guard kann heute nachweislich nicht false-green werden
(im Review empirisch über die skip-JUnit-XML reproduziert). Beides ist reine Härtung gegen
künftige Fußangeln.

1. **Skip-Guard prüft per `grep` die gesamte JUnit-XML-Datei.** Der Step prüft
   `grep -q 'tests="1"'` und `! grep -q 'skipped="0"'` über die **ganze** Datei. Heute sicher
   (genau ein Test; die assume-Message enthält keinen der Strings). Es würde aber irreführen,
   sobald (a) ein **zweiter** Test in die Klasse kommt → `tests="1"` wird false-RED, oder
   (b) eine Assertion-/Fehlermeldung den Literal-String `skipped="0"` enthält → könnte einen
   Skip maskieren.
2. **Test räumt seine Zeilen nicht auf.** `MigrationIntegrationTest` legt `mig_it_*`-User/
   Todo/Subtask an, löscht sie aber nicht. Für das Wegwerf-CI-Postgres egal; auf einer
   **persistenten** lokalen DB sammeln sich (harmlos eindeutig benannte) Zeilen an.

## Aufgabe
- [ ] Skip-Guard in `ci.yml` härten: gezielt die `<testsuite>`-Attribute parsen
  (z. B. `xmllint --xpath`) statt Volltext-`grep`; den Anzahl-Check von der hartkodierten
  `tests="1"` entkoppeln, damit ein künftiger zweiter Migrationstest nicht false-RED wird.
- [ ] Teardown in `MigrationIntegrationTest` ergänzen, der die angelegten `mig_it_*`-Zeilen
  wieder entfernt (Hygiene für persistente lokale DBs).

## Offene Fragen / Notizen
- Beides niedrige Prio: reine Härtung/Hygiene, kein bestehender Bug. Der aktuelle Guard ist
  korrekt — ein Skip lässt den Job rot werden (`! grep skipped="0"` → `exit 1`), ein
  echter Testfehler färbt bereits den `./gradlew test`-Step rot.
- Zusammenhängende Quelle: beide Punkte stammen aus dem Review von #35 und würden in einem
  einzigen kleinen Cleanup-PR erledigt.
