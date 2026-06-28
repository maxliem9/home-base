#!/usr/bin/env node
// Drift-Guard für die Einkaufs-Icon-Maps (Web ↔ Android), Issue #452.
//
// Die Name→Icon-Datei-Map (`ITEM_ICON_KEY`) und die Kategorie→Icon-Map
// (`CATEGORY_ICON_KEY`) sind in drei Dateien dupliziert und müssen byte-genau
// identisch bleiben (Hintergrund/Zielbild: Issues #443/#451):
//   - Web:     web/src/components/shoppingIconMap.ts      (ITEM_ICON_KEY)
//   - Web:     web/src/components/shoppingCategories.tsx  (CATEGORY_ICON_KEY)
//   - Android: android/.../ui/shopping/ShoppingIcons.kt   (beide Maps)
//
// Dieses Skript extrahiert die Paare aus beiden Sprachen per Regex (es sind flache
// String-Literale: TS `'k': 'v',`, Kotlin `"k" to "v",`), normalisiert sie und
// diff't Web gegen Android. Bei Drift Exit-Code 1 mit einer klaren Auflistung der
// abweichenden Schlüssel. Reine Node-Standardbibliothek, keine Dependencies.
//
// Bewusst NICHT im Scope: Existenz der SVG-Asset-Dateien (das prüft bereits ein
// Web-Unit-Test) — hier geht es nur um Web↔Android-Map-Parität.

import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..')

const WEB_ITEM = 'web/src/components/shoppingIconMap.ts'
const WEB_CAT = 'web/src/components/shoppingCategories.tsx'
const ANDROID = 'android/app/src/main/kotlin/com/homebase/android/ui/shopping/ShoppingIcons.kt'

/** Liest eine Datei relativ zum Repo-Root. */
function read(rel) {
  try {
    return readFileSync(join(repoRoot, rel), 'utf8')
  } catch (e) {
    console.error(`FEHLER: Datei nicht lesbar: ${rel}\n  ${e.message}`)
    process.exit(2)
  }
}

/**
 * Schneidet den Map-Body zwischen der Deklaration `name` und der ersten danach
 * folgenden schließenden Klammer heraus (`}` für TS-Objekte, `)` für Kotlin
 * mapOf(...)). Verhindert, dass Literale aus anderen Maps mitgelesen werden.
 */
function sliceMapBody(source, name, file) {
  // An die echte Deklaration binden (`val`/`const` o. ä. + Name), nicht an die erste
  // Erwähnung im Doc-Kommentar (z. B. "[CATEGORY_ICON_KEY] maps …").
  const declRe = new RegExp(`(?:val|const|let|var)\\s+${name}\\b`)
  const declMatch = declRe.exec(source)
  const declIdx = declMatch ? declMatch.index : -1
  if (declIdx === -1) {
    console.error(`FEHLER: Deklaration von ${name} nicht in ${file} gefunden.`)
    process.exit(2)
  }
  // Ab dem ersten '{' (TS) bzw. 'mapOf(' (Kotlin) nach der Deklaration.
  const openObj = source.indexOf('{', declIdx)
  const openMapOf = source.indexOf('mapOf(', declIdx)
  let start
  let close
  if (openMapOf !== -1 && (openObj === -1 || openMapOf < openObj)) {
    start = openMapOf + 'mapOf('.length
    close = source.indexOf(')', start)
  } else {
    start = openObj + 1
    close = source.indexOf('}', start)
  }
  if (close === -1) {
    console.error(`FEHLER: Map-Ende für ${name} in ${file} nicht gefunden.`)
    process.exit(2)
  }
  return source.slice(start, close)
}

// Flache Key/Value-Paare. Key wahlweise gequotet ('k'/"k") oder bare TS-Object-
// Shorthand (PRODUCE), Bindewort ':' (TS) oder 'to' (Kotlin), Value stets gequotet.
//   'aepfel': 'apples'  |  "aepfel" to "apples"  |  PRODUCE: 'produce'
// Erfasst beide Maps in beiden Sprachen.
const PAIR_RE = /(?:(['"])([^'"]+)\1|([A-Za-z_][A-Za-z0-9_-]*))\s*(?::|\bto\b)\s*(['"])([^'"]+)\4/g

/** Extrahiert eine Map als sortiertes Array von "key=value"-Zeilen. */
function extractMap(source, name, file) {
  const body = sliceMapBody(source, name, file)
  const map = new Map()
  let m
  while ((m = PAIR_RE.exec(body)) !== null) {
    const key = m[2] ?? m[3]
    const value = m[5]
    if (map.has(key)) {
      console.error(`FEHLER: doppelter Schlüssel "${key}" in ${name} (${file}).`)
      process.exit(2)
    }
    map.set(key, value)
  }
  if (map.size === 0) {
    console.error(`FEHLER: ${name} in ${file} ergab keine Einträge — Parser-Annahme verletzt?`)
    process.exit(2)
  }
  return map
}

/** Vergleicht zwei Maps und sammelt menschenlesbare Diff-Zeilen. */
function diffMaps(label, web, android) {
  const problems = []
  const keys = new Set([...web.keys(), ...android.keys()])
  for (const key of [...keys].sort()) {
    const w = web.get(key)
    const a = android.get(key)
    if (w === undefined) problems.push(`  - "${key}": fehlt im Web, Android="${a}"`)
    else if (a === undefined) problems.push(`  - "${key}": fehlt in Android, Web="${w}"`)
    else if (w !== a) problems.push(`  - "${key}": Web="${w}" ≠ Android="${a}"`)
  }
  return { label, problems, webSize: web.size, androidSize: android.size }
}

const webSrc = read(WEB_ITEM)
const catSrc = read(WEB_CAT)
const androidSrc = read(ANDROID)

const results = [
  diffMaps(
    'ITEM_ICON_KEY',
    extractMap(webSrc, 'ITEM_ICON_KEY', WEB_ITEM),
    extractMap(androidSrc, 'ITEM_ICON_KEY', ANDROID),
  ),
  diffMaps(
    'CATEGORY_ICON_KEY',
    extractMap(catSrc, 'CATEGORY_ICON_KEY', WEB_CAT),
    extractMap(androidSrc, 'CATEGORY_ICON_KEY', ANDROID),
  ),
]

let failed = false
for (const { label, problems, webSize, androidSize } of results) {
  if (problems.length === 0) {
    console.log(`✓ ${label}: Web und Android identisch (${webSize} Einträge).`)
  } else {
    failed = true
    console.error(
      `✗ ${label}: Drift zwischen Web (${webSize}) und Android (${androidSize}):`,
    )
    for (const p of problems) console.error(p)
  }
}

if (failed) {
  console.error(
    '\nDie Einkaufs-Icon-Maps sind aus dem Tritt geraten. Web und Android müssen\n' +
      'identisch sein — passe die abweichende Seite an (siehe Issue #452).',
  )
  process.exit(1)
}

console.log('\nAlle Einkaufs-Icon-Maps sind Web↔Android synchron. ✓')
