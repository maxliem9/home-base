// Einstellungen → Über (#626). Zeigt, welcher Build hier gerade läuft: die Version dieser
// Web-App (zur Build-Zeit von Vite eingesetzt, Quelle ist die VERSION-Datei im Repo-Root) und
// die Version des Backends (GET /version). Rein informativ — nichts zum Einstellen; die Seite
// beantwortet „läuft mein Handy/Browser auf demselben Stand wie der Server?".
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { API_BASE, safeFetch } from '../../api'
import type { VersionResponse } from '../../types'
import { APP_COMMIT, APP_VERSION, formatVersion } from '../../version'
import { Card } from '../../ui/primitives'

type BackendState =
  | { status: 'loading' }
  | { status: 'ok'; version: string; commit: string }
  | { status: 'error' }

export function AboutSettings({ token, onLogout }: { token: string; onLogout: () => void }) {
  const { t } = useTranslation()
  const [backend, setBackend] = useState<BackendState>({ status: 'loading' })

  useEffect(() => {
    let alive = true
    safeFetch(token, `${API_BASE}/version`).then(async (result) => {
      if (!alive) return
      if (result.ok && result.res.status === 401) return onLogout()
      if (!result.ok || !result.res.ok) return setBackend({ status: 'error' })
      // encodeDefaults=false (CLAUDE.md): `commit` fehlt, wenn der Build ohne Git-Kontext lief.
      // `.json()` in try/catch: ein 200 mit Nicht-JSON-Body (Proxy-Interstitial) würde sonst
      // unbehandelt werfen und die Zeile für immer auf „Lädt…" stehen lassen.
      const data = await result.res.json().catch(() => null) as Partial<VersionResponse> | null
      if (!data?.version) return setBackend({ status: 'error' })
      setBackend({ status: 'ok', version: data.version, commit: data.commit ?? '' })
    })
    return () => { alive = false }
  }, [token, onLogout])

  // Hinweis nur bei einem echten Versions-Unterschied (der Commit darf abweichen: das Web-Image
  // wird nur neu gebaut, wenn web/ sich geändert hat).
  const mismatch = backend.status === 'ok' && backend.version !== APP_VERSION

  return (
    <div className="hb-stack" style={{ gap: 18 }}>
      <Card className="hb-card--pad">
        <div className="hb-cardhead">
          <div>
            <h3>{t('settings.aboutVersionTitle')}</h3>
            <p className="hb-muted" style={{ margin: '2px 0 0' }}>{t('settings.aboutVersionHint')}</p>
          </div>
        </div>
        <dl className="hb-about-list">
          <dt>{t('settings.aboutWeb')}</dt>
          <dd data-testid="about-web-version">{formatVersion(APP_VERSION, APP_COMMIT)}</dd>
          <dt>{t('settings.aboutBackend')}</dt>
          <dd data-testid="about-backend-version">
            {backend.status === 'loading' && t('common.loading')}
            {backend.status === 'error' && t('settings.aboutBackendUnavailable')}
            {backend.status === 'ok' && formatVersion(backend.version, backend.commit)}
          </dd>
        </dl>
        {mismatch && (
          <p className="hb-muted" style={{ margin: '12px 0 0' }}>{t('settings.aboutVersionMismatch')}</p>
        )}
      </Card>
    </div>
  )
}
