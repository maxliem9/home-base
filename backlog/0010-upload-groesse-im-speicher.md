---
id: 0010
title: Bild-Upload puffert die ganze Datei in den Speicher, bevor das Größenlimit greift
status: backlog
category: tech-debt
priority: low
source: "PR #38 review"
created: 2026-06-05
---

# 0010 — Bild-Upload: Größenlimit erst nach vollständigem Einlesen in den Speicher

## Kontext
Beim Bild-Upload (`backend/.../routes/NoteRoutes.kt`, `post("/{id}/images")`)
wird der Datei-Part erst vollständig in den Heap gelesen und **danach** gegen
`MAX_UPLOAD_MB` geprüft:

```kotlin
val bytes = part.provider().readRemaining().readByteArray()
when {
    bytes.isEmpty() -> rejected = ImageRejection.Empty
    bytes.size > imageConfig.maxBytes -> rejected = ImageRejection.TooLarge
    ...
}
```

Ein sehr großer Upload wird also komplett gepuffert, bevor er mit 413 abgelehnt
wird. Auch das **Ausliefern** liest die Datei komplett in den Speicher
(`Files.readAllBytes(file)`, `get("/{id}/images/{imageId}")`).

In Produktion steht nginx mit `client_max_body_size 12m` davor
(`web/nginx-spa.conf`), und es gibt nur zwei vertrauenswürdige Nutzer — der
praktische DoS-Spielraum ist daher gering. Ohne den vorgelagerten Proxy (z. B.
Backend direkt erreichbar, lokale Dev-Umgebung) fehlt die Schranke aber, und
mehrere parallele große Uploads könnten den Heap belasten.

## Aufgabe
- Größe **während** des Streamens begrenzen, statt erst nach `readByteArray()`:
  z. B. Bytes blockweise lesen und bei Überschreiten von `maxBytes` abbrechen
  (Stream verwerfen, 413), oder Ktors Request-Limits / einen Größen-begrenzten
  Reader nutzen.
- Optional Upload direkt streamend auf Platte schreiben (Temp-Datei), statt den
  gesamten Inhalt im Heap zu halten; analog beim Ausliefern `respondFile` /
  einen Stream statt `readAllBytes` erwägen.
- Verhalten testen (knapp über Limit → 413, ohne den ganzen Body zu puffern).

## Offene Fragen / Notizen
- Niedrige Priorität wegen nginx-Limit + 2-Nutzer-Setup; eher Härtung/Tech-Debt
  als akute Lücke.
- Inhaltliche Validierung der Bytes (Magic-Bytes statt nur Content-Type) wäre
  ein verwandtes, separates Hardening; beim Review wurde stattdessen
  `X-Content-Type-Options: nosniff` auf dem Ausliefer-Endpunkt gesetzt, damit ein
  als `image/*` getarnter Nicht-Bild-Inhalt vom Browser nicht als Markup
  interpretiert wird.
