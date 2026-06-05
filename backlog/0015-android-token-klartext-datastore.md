---
id: 0015
title: Android — JWT-Token im Klartext im DataStore (allowBackup=true, security-crypto ungenutzt)
status: backlog
category: security
priority: medium
source: PR #3 (Review session 2026-06-05)
created: 2026-06-05
---

# 0015 — Android: JWT-Token im Klartext gespeichert

## Kontext
Das JWT, das vollen Zugriff auf den Familien-Hub gewährt, liegt **unverschlüsselt** im
DataStore-File (`android/app/src/main/kotlin/com/homebase/android/data/repository/AuthRepository.kt:14-37`,
`preferencesDataStore(name="auth")`). Gleichzeitig steht im Manifest
`android:allowBackup="true"` ohne `fullBackupContent`/`dataExtractionRules`
(`AndroidManifest.xml:8`), sodass das `auth`-DataStore-File in ADB- und Cloud-Backups landet
und das Klartext-Token das Gerät verlassen kann. Die Verschlüsselungs-Dependency
`androidx.security.crypto` ist in `build.gradle.kts:57` deklariert, wird aber nirgends benutzt
— Hinweis auf eine geplante, dann vergessene Verschlüsselung.

## Aufgabe
- Token über EncryptedSharedPreferences (oder verschlüsseltes DataStore mit
  `androidx.security.crypto` MasterKey) ablegen statt im Klartext-DataStore — die bereits
  vorhandene, ungenutzte `security-crypto`-Dependency tatsächlich verwenden (oder entfernen,
  falls bewusst Klartext gewollt).
- Zusätzlich/alternativ das `auth`-File per `android:dataExtractionRules` +
  `fullBackupContent` vom Backup ausschließen oder `allowBackup="false"` setzen.

## Offene Fragen / Notizen
- Privates 2-Nutzer-App hinter DynDNS — Risiko real, aber begrenzt (App-privater Storage;
  Exploit nur bei Root/USB-Debugging/Backup-Extraktion). Daher medium, kein BLOCKER.
