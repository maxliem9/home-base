---
id: 0002
title: Rezept-Zutaten auf Einkaufsliste übernehmen
status: backlog
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
