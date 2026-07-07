# Dashboard / „Heute"-View

> Lies dies, bevor du am Web-Dashboard / Heute-Screen arbeitest.

Web-Startseite: Dashboard-/„Heute"-View (`components/DashboardView.tsx`, erster Nav-Eintrag,
Default-Tab) — zeitabhängige Begrüßung, Quick-Add → Inbox-Todo, 4 Stat-Kacheln
(heute fällig / Inbox / morgen fällig / heute erledigt), „Heute dran", laufender
Timer, Einkaufs-Peek und Digest-Vorschau; aggregiert die bestehenden Reads
(Todos/Shopping/Time) live über WebSocket. Vorbild: Android `HeuteScreen`.
