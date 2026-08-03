// Version dieses Web-Builds (#626). Die Werte werden von Vite per `define` eingesetzt
// (Quelle: VERSION im Repo-Root bzw. die Docker-Build-Args) — hier nur eingesammelt, damit
// der Rest der App eine normale Konstante importiert.
//
// Der `typeof`-Guard ist für Umgebungen ohne die Vite-defines nötig (Vitest nutzt eine eigene
// vitest.config.ts) — dort ist die Version schlicht unbekannt statt ein ReferenceError.
export const APP_VERSION: string = typeof __APP_VERSION__ === 'string' ? __APP_VERSION__ : '0.0.0-dev'
export const APP_COMMIT: string = typeof __APP_COMMIT__ === 'string' ? __APP_COMMIT__ : ''

/** Für die Anzeige: `1.1.0 (a1b2c3d)` bzw. nur `1.1.0`, wenn kein Commit bekannt ist. */
export function formatVersion(version: string, commit: string): string {
  return commit ? `${version} (${commit})` : version
}
