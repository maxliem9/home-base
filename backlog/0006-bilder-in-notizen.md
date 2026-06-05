---
id: 0006
title: Bilder in Notizen
status: done
category: feature
priority: low
source: prd.md (Post-MVP)
created: 2026-06-05
---

# 0006 — Bilder in Notizen

## Kontext
Das Notizen-Feature existiert (Backend + Web + Android), unterstützt aber nur Text.

## Aufgabe
- Bild-Upload an einer Notiz (Web + Android).
- Serverseitige Speicherung (NAS-Volume oder Objektspeicher) + Auslieferung.
- Anzeige inkl. Thumbnails; Größenlimit erzwingen.

## Offene Fragen / Notizen
Umgesetzt (session 2026-06-05) — getroffene Entscheidungen:
- **Datenmodell:** Anhang-Galerie über eigene Tabelle `note_images` (1:n,
  FK ON DELETE CASCADE), eingebettet als `images`-Array in der NoteDto.
- **Speicherort:** Dateisystem unter `UPLOAD_DIR` (prod: Docker-Volume
  `uploads` → `/data/uploads`), nicht in der DB. Backup = Volume sichern
  (zusammen mit `pgdata`). Lokal default `uploads/` (gitignored).
- **Größe/Formate:** JPEG/PNG/WebP/GIF, max. `MAX_UPLOAD_MB` (default 10);
  Backend erzwingt Typ + Größe, nginx `client_max_body_size 12m`.
- **Thumbnails:** clientseitig skaliert (Web `<img>` + CSS, Android Coil) —
  keine serverseitige Bildverarbeitung; das Original wird ausgeliefert.
  Mögliche spätere Optimierung: serverseitige Thumbnails zur Bandbreitenersparnis.
- **Auth der Auslieferung:** GET `/notes/{id}/images/{imageId}` akzeptiert das
  JWT via `?token=` (wie die WS-Endpunkte), da `<img>`/Coil keinen
  Authorization-Header setzen können.
