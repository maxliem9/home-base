---
id: 0011
title: flyway.repair() läuft unbedingt bei jedem Start (hebelt Checksum-Validierung aus)
status: backlog
category: tech-debt
priority: medium
source: PR #33 (Review session 2026-06-05)
created: 2026-06-05
---

# 0011 — flyway.repair() läuft unbedingt bei jedem Start

## Kontext
PR #33 hat in `DatabaseFactory.init` (`backend/src/main/kotlin/com/homebase/db/DatabaseFactory.kt`)
vor `flyway.migrate()` ein **unbedingtes `flyway.repair()`** eingebaut. Der Anlass war
einmalig: #33 hat die bereits ausgelieferte Migration `V7` editiert (idempotent gemacht),
wodurch sich deren Checksum änderte. Ohne `repair()` würde `flyway.validate()` auf einem
**bestehenden** Deployment mit Checksum-Mismatch abbrechen.

Das Problem: `repair()` läuft jetzt bei **jedem** Start, nicht nur für dieses eine
Realignment. `repair()` gleicht nicht nur Checksums an, sondern entfernt auch
fehlgeschlagene (non-success) Einträge aus `flyway_schema_history`.

Folge: Künftige, **unbeabsichtigte** Checksum-Drift (z. B. jemand editiert versehentlich
eine bereits angewandte Migration) wird beim nächsten Boot stillschweigend „repariert",
statt von `validate()` laut zu scheitern — genau die Drift-Erkennung, für die `validate()`
existiert, wird dauerhaft ausgehebelt.

Praktisches Risiko für die aktuelle 2-Nutzer-Postgres-Installation ist gering
(transaktionales DDL ⇒ eine fehlgeschlagene Migration rollt zurück und wird beim Neustart
sauber erneut versucht), aber es ist ein langfristiger Footgun und ein stummer Verlust einer
Sicherheitsprüfung.

## Aufgabe
- `repair()` gezielter machen statt bei jedem Boot — eine von:
  - **Bevorzugt (sobald gefahrlos):** `repair()` wieder entfernen, sobald sicher ist, dass
    die einzige (NAS-)Installation die neue V7-Checksum übernommen hat. Auf einer fresh DB
    war es ohnehin ein No-op.
  - nur ausführen, wenn `validate()` tatsächlich einen Checksum-Mismatch meldet
    (try `validate()` → bei Mismatch `repair()` → erneut `migrate()`), **oder**
  - einmalig per Flag/State ausführen.
- Verifizieren: bestehende DB (V7 mit alter Checksum) migriert weiterhin sauber; fresh DB
  unverändert.

## Offene Fragen / Notizen
- Sobald die produktive NAS-DB die neue V7-Checksum gespeichert hat, ist „`repair()` wieder
  entfernen" die einfachste und sicherste Variante.
- Der Migrations-CI-Test aus #35 (`MigrationIntegrationTest`) deckt den
  `repair()`+`migrate()`-Pfad gegen echtes Postgres ab — Änderungen hier landen also nicht
  blind, sondern werden vom CI-Job `migrations` mitgetestet.
- Im Review als „kein Blocker, Design-Hinweis" eingestuft — bewusst **nicht** unilateral
  geändert, weil es eine Abwägung ist (Verfügbarkeit bei Bestands-Upgrade vs. laute
  Drift-Erkennung).
