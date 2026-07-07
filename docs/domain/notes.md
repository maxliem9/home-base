# Notizen-Domänenmodell

> Lies dies, bevor du an Notizen (Modell oder Editor-UX) arbeitest.

Markdown-Notizen mit Tags, Volltextsuche und Sichtbarkeit (PRIVATE|SHARED).
- Note: id, title, content (Markdown), tags (CSV), visibility, created_by,
  created_at, updated_at. Geteilte Notizen sind für beide Nutzer sicht- und
  editierbar; die Sichtbarkeit darf nur der Ersteller ändern.
- NoteImage (1:n Anhang-Galerie): id, note_id (FK ON DELETE CASCADE), filename
  (auf Platte), original_name, content_type, size_bytes, sort_order, created_by,
  created_at — immer als images-Array in NoteDto eingebettet.
- Bilder liegen als Datei unter UPLOAD_DIR (nicht in der DB); das Original wird
  ausgeliefert, Thumbnails skaliert der Client. Erlaubt: JPEG/PNG/WebP/GIF bis
  MAX_UPLOAD_MB (default 10).
- Endpunkte unter /api/v1/notes: CRUD + POST/GET/DELETE
  /notes/{id}/images[/{imageId}] (Upload als multipart, Auslieferung via ?token=
  wie bei den WS-Endpunkten; Upload und Delete geben die aktualisierte Note
  zurück). WebSocket /api/v1/ws/notes (Channel "notes"):
  NOTE_CREATED|UPDATED|DELETED; Bildänderungen senden NOTE_UPDATED. Private
  Notizen werden nie über den geteilten Kanal gesendet.

## Notizen-View / Editor-UX (Web, #309–#313/HB-13)
Notizen-View (`components/NotesView.tsx`): Eine ausgewählte Notiz **ruht in der
gerenderten Vorschau** (Lesen); ein Klick auf **Titel oder Textkörper** verwandelt genau diesen
Bereich **in place** in den Editor (kein Dialog, kein Seitenwechsel; Fokus ins geklickte Feld).
**Esc oder ein Klick außerhalb** des Dokuments speichert und kehrt zur Vorschau zurück; eine neue
Notiz öffnet direkt im Edit (Fokus Titel), eine leere Notiz zeigt einen klickbaren Platzhalter.
Der frühere Edit/Vorschau-Umschalter entfällt (Klicken = Bearbeiten; kehrt #310s Edit-first-Default
bewusst um). Im Editmodus trägt das Dokument einen Akzent-Rahmen, die auto-wachsende Markdown-
Textarea scrollt nicht intern; die Bild-Galerie/-Verwaltung lebt im Editmodus (Vorschau rendert
eingebettete Bilder inline). Es gibt **keinen Speichern-Button** mehr: Änderungen **auto-speichern**
debounced (~900 ms nach der letzten Eingabe, sofort bei Blur/Notizwechsel/Schließen/Unmount)
per bestehendem PUT bzw. POST, mit Status-Indikator (Speichert…/Gespeichert/Fehler). Hazards:
neue Notiz wird erst bei nicht-leerem Titel angelegt, die zurückgegebene id wandert in den
Draft (Folge-Saves sind PUT), ein `creatingRef`/`savingRef`-Guard verhindert Doppel-POST,
ein Session-Token verhindert id-Stamping auf einen inzwischen gewechselten Draft, ein
Snapshot-Vergleich unterdrückt redundante Saves, und der WS-`NOTE_UPDATED`-Echo wird nur in
die `notes`-Liste gemerged — **nie** zurück in den `draft` (kein Text/Caret-Clobber). Die
Liste ist **ordner-gruppiert** (Header + eingerückte Notizen, benannte Ordner alphabetisch,
„Ohne Ordner" zuletzt; Filter-Chips bleiben). Auf Mobile (≤860px) wird bei offenem Editor die
Liste eingeklappt (Voll-Breite-Editor + „← Notizen"-Back-Control), Notizwechsel ohne Zurück
über ein linkes `<Sheet>`-Slide-over.
