---
id: 0002
title: Rezept-Zutaten auf Einkaufsliste übernehmen
status: done
category: feature
priority: medium
source: prd.md (Post-MVP)
created: 2026-06-05
---

# 0002 — Rezept-Zutaten auf Einkaufsliste übernehmen

## Kontext
Rezepte tragen eingebettete `Ingredient`s (`name`, `amount?`, `unit?`, `sort_order`).
Eine listenbasierte Einkaufsliste existiert bereits. Es fehlt die Brücke zwischen beidem.

## Aufgabe
- Aktion „Zutaten auf Einkaufsliste" im Rezept-Detail (Web + Android).
- Mengen anhand der aktuell gewählten Portionierung skalieren
  (vgl. `GET /api/v1/recipes/{id}?servings=N`).
- Mapping `Ingredient` → Einkaufs-Item; bereits vorhandene Items zusammenführen
  statt zu duplizieren.

## Offene Fragen / Notizen
- Auf welche Liste übernehmen, wenn es mehrere gibt? (Auswahl-Dialog?)
- Einheiten zusammenfassen (z. B. 2× „200 g Mehl")? Erstmal simpel halten.
- Reiner Client-Flow über bestehende Endpunkte, oder eigener Backend-Endpunkt?

## Umgesetzt (session 2026-06-05)
Neuer Endpunkt `POST /api/v1/shopping/batch` (`{ listId?, items:[{name, amount?, unit?}] }`)
→ `{ added, merged, skipped, items }`. Beide Clients nutzen ihn.
- **Auswahl-Modal** (Web + Android): Checkliste der Zutaten (alle vorausgewählt, Staples wie
  Salz einfach abwählen) + Portionen-Stepper zum Skalieren. Liste wählbar per Dropdown,
  sobald es mehr als eine gibt (sonst implizit die einzige).
- **Skalierung** passiert im Client (Faktor = gewählte/Rezept-Portionen); der Endpunkt bekommt
  fertige Mengen.
- **Zusammenführen ohne Schema-Änderung:** Menge steht im Item-Namen („200 g Mehl"); der
  Endpunkt parst bestehende Namen und summiert bei gleichem Name+Einheit
  (500 g + 200 g → „700 g Mehl"). Andere Einheit / nicht parsbar → eigene Zeile;
  exakt gleicher Name → übersprungen. Keine Einheiten-Umrechnung (bewusst simpel).
- Verifiziert: Backend-Tests (`ShoppingRouteTest`), Live-E2E gegen Postgres und
  Browser-Durchlauf (Skalieren, Salz abwählen, Merge 400+200 g → 600 g).
