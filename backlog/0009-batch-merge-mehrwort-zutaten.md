---
id: 0009
title: Mengen-Merge greift nicht bei mehrwortigen Zutatennamen (parseQty-Heuristik)
status: backlog
category: tech-debt
priority: low
source: "PR #37 review"
created: 2026-06-05
---

# 0009 — Mengen-Merge greift nicht bei mehrwortigen Zutatennamen

## Kontext
`POST /api/v1/shopping/batch` (`backend/.../routes/ShoppingRoutes.kt`) führt Mengen
in ein vorhandenes Listen-Item zusammen, indem es dessen Label `"200 g Mehl"` mit
`parseQty()` (ebd., ~Z. 360) wieder in Menge/Einheit/Name zerlegt und über
`name + unit` matcht.

Die Einheiten-Heuristik in `parseQty` (Z. 369–371) erkennt als Einheit nicht nur
bekannte Einheiten (`KNOWN_UNITS`), sondern auch **jeden kurzen Token** nach der
Zahl: `candidate.length <= 4 && hat Buchstaben && keine Ziffern`. Bei einem
mehrwortigen Namen, dessen **erstes Wort ≤ 4 Zeichen** ist und keine Ziffer
enthält, wird dieses Wort fälschlich als Einheit verschluckt:

- `"2 rote Paprika"` → `amount=2, unit="rote", name="Paprika"` (statt `unit=null,
  name="rote Paprika"`).
- ebenso `"3 Bio Eier"`, `"1 alte Kartoffel"`, `"5 reife Tomaten"` …

Folge: Beim erneuten Hinzufügen derselben Zutat schlägt das Matching fehl
(`parsed.name="Paprika"` ≠ eingehender Name `"rote Paprika"`), und es entsteht eine
**zweite, separate Zeile** statt einer Zusammenführung. Keine Datenverfälschung —
die PR nennt „getrennte Zeile“ ausdrücklich als zulässigen Fallback —, aber das
beworbene Merge-Verhalten greift für solche Namen still nicht.

Single-Word-Namen mit/ohne Einheit (der Normalfall) funktionieren korrekt; durch
die 21 grünen Backend-Tests abgedeckt.

## Aufgabe
- Heuristik robuster machen, z. B. eine von:
  - Einheit nur akzeptieren, wenn `candidate.lowercase() in KNOWN_UNITS`
    (Fallback-Zweig `length <= 4 …` streichen) — einfachste, sicherste Variante;
    deckt unsere selbst formatierten Labels ab, da der Client strukturierte
    `unit`-Werte liefert.
  - oder Merge nicht über das Re-Parsen des Labels, sondern über die strukturiert
    eingehenden `name`/`unit`-Felder gegen ein gespeichertes Strukturfeld matchen
    (größerer Umbau; siehe „Menge in eigener Spalte“-Diskussion in 0002/PR #37).
- Testfälle ergänzen: `"2 rote Paprika"` + `"3 rote Paprika"` → `"5 rote Paprika"`
  (1 Zeile), analog `"Bio Eier"`.

## Offene Fragen / Notizen
- Bewusst kleiner Scope der PR #37 („Menge lebt im Namen, keine neue Spalte,
  keine Einheiten-Umrechnung“). Die Fallback-Heuristik war eine akzeptierte
  Vereinfachung — daher Prio low.
- Web- und Android-Picker zeigen Mengen über eigene `fmtAmount`/`Format.amount`
  (2 Nachkommastellen), das Backend speichert mit 3 — rein kosmetische
  Abweichung in der Vorschau, nicht Teil dieses Items.
