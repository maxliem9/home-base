# Security-Invarianten (Backend & nginx)

> Zwei Code-Invarianten, die sonst nirgends dokumentiert sind. Nicht ungeprüft entfernen.
>
> **Andere Quellen (nicht hier duplizieren):**
> - Alle env-Variablen (vollständig, kommentiert, mit Defaults): **`.env.example`** (Single Source of Truth).
> - Deployment, Docker-Services, DSM-Reverse-Proxy, FRITZ!Box, Android-Build, Troubleshooting: **[DEPLOYMENT.md](DEPLOYMENT.md)**.
> - Config-Grundsatz (env = nur Defaults, editierbare Optionen in der DB): `CLAUDE.md`.

## nginx-Access-Log maskiert `?token=` — nicht entfernen
`web/nginx-spa.conf` maskiert `?token=`-Query-Params im Access-Log (`token=***`) und loggt für
`/api` nur ab Severity `crit` ins Error-Log. Grund: die JWT-Bild-URLs (Android Coil ruft Notizbilder
mit `?token=<JWT>` ab) dürfen **nie im Klartext in Logfiles** landen. **Diese Regeln nicht entfernen.**
Das DSM-Reverse-Proxy-Log liegt außerhalb des Repos und loggt URLs ggf. weiterhin — bei Bedarf in DSM
konfigurieren.

## Login-Throttling (Issue #8)
`security/LoginThrottler`: `POST /auth/login` wird **pro Client-IP** gedrosselt. Die ersten 5
Fehlversuche sind frei, danach exponentielles Backoff (1→2→4→…→15 min Sperre) mit
`429 TOO_MANY_ATTEMPTS` + `Retry-After`; ein erfolgreicher Login setzt den Zähler zurück. State nur
im Speicher (Neustart vergibt jedem).

Bewusst **IP**- statt benutzerbasiert: Benutzernamen sind bekannt → Username-Keying ermöglichte einen
Account-Lockout-DoS. Die echte Client-IP wird spoofing-resistent aus `X-Forwarded-For` gelesen: die
rechtesten `TRUSTED_PROXY_COUNT` Einträge stammen von eigenen Proxies, alles weiter links ist
client-gefälscht und wird ignoriert. Konfiguration + Fallstricke von `TRUSTED_PROXY_COUNT` (prod = 2:
DSM + nginx) stehen kommentiert in `.env.example`.
