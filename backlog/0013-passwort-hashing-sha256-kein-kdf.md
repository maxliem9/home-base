---
id: 0013
title: Passwörter als ungesalzenes SHA-256 ohne Work-Factor gespeichert (kein KDF)
status: backlog
category: security
priority: high
source: PR #4 (Review session 2026-06-05)
created: 2026-06-05
---

# 0013 — Passwörter als ungesalzenes SHA-256 ohne KDF

## Kontext
Passwörter werden mit einfachem, einmaligem **SHA-256 ohne Salt** gehasht
(`backend/src/main/kotlin/com/homebase/security/Passwords.kt`). SHA-256 ist ein schneller
Allzweck-Hash: ohne Salt ergeben gleiche Passwörter identische Hashes (Rainbow-Table-/
Precomputation-Angriffe), ohne Work-Factor sind bei einem DB-Leak Milliarden Rateversuche/s
auf GPU möglich. Genutzt sowohl beim Login (`routes/AuthRoutes.kt:32-33`) als auch beim
Seeding (`db/UserSeeder.kt:65`). Es ist **keine** KDF-Bibliothek auf dem Backend-Classpath
(`backend/build.gradle.kts` — kein bcrypt/argon2/scrypt/pbkdf), `password_hash` ist seit
`V1__create_users.sql` durchgehend `TEXT`.

Der Autor von PR #1 hatte SHA-256 ausdrücklich als Platzhalter markiert („should move to a
proper KDF (bcrypt/argon2) before real use"); PR #4 zog die Funktion nach `security/Passwords.kt`,
behielt aber SHA-256 bei. Die App läuft inzwischen produktiv auf der NAS mit echten, über
`SEED_USERS` angelegten Nutzern — aus dem Platzhalter ist eine reale Schwachstelle geworden.

## Aufgabe
- Echte Passwort-KDF einführen (bcrypt, scrypt oder Argon2id; z. B. `at.favre.lib:bcrypt`
  oder `password4j`) mit Per-Nutzer-Salt und sinnvollem Work-Factor.
- `sha256()` in `Passwords.kt` durch `hash(password): String` und
  `verify(password, stored): Boolean` ersetzen; Aufrufstellen anpassen:
  `AuthRoutes.kt` (Vergleich auf `verify` umstellen — beseitigt zugleich den nicht
  konstant-zeitigen `!=`-Vergleich in `:33`) und `UserSeeder.kt` (Seed-/Update-Hash).
- Tests in `TestHelpers.kt` und `UserSeederTest.kt` mitziehen.
- Da bisher nur SHA-256-Hashes existieren und Passwörter ohnehin bei jedem Boot aus
  `SEED_USERS` neu geschrieben werden, genügt ein einmaliger Re-Seed nach Deploy
  (kein Migrationspfad für Alt-Hashes nötig). `password_hash TEXT` ist breit genug für
  moderne KDF-Strings.

## Offene Fragen / Notizen
- Höchste Sicherheits-Prio dieses Reviews; im Bericht als BLOCKER eingestuft. Mit dem
  Nutzer abstimmen (eigener kleiner PR).
- Für einen privaten 2-Nutzer-Hub ist die *praktische* Ausnutzbarkeit an einen DB-Leak
  gebunden — die Härtung ist aber Standard und mit einer KDF-Bibliothek trivial.
