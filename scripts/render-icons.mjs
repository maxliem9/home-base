#!/usr/bin/env node
/*
 * Rendert alle PNG-Icon-Varianten aus den SVG-Quellen in `web/public/`.
 *
 * Quellen (Single Source of Truth für die Marke):
 *   web/public/app-icon.svg           — Kachel mit eigener Rundung (Web-/Launcher-PNGs)
 *   web/public/app-icon-maskable.svg  — randlos, die Maske macht das OS (PWA maskable, iOS)
 *   web/public/favicon.svg            — kleinteilig optimierte Variante (dickerer Strich)
 *
 * Rasterizer ist das bereits vorhandene Playwright-Chromium (keine zusätzliche
 * Dependency wie sharp/librsvg — die gibt es hier nicht, und Chromium rendert die
 * SVGs exakt so, wie der Browser sie später auch anzeigt).
 *
 * Aufruf (aus dem Repo-Root):  node scripts/render-icons.mjs
 * Nach jeder Änderung an einer der drei SVG-Quellen erneut laufen lassen und die
 * erzeugten PNGs mit committen.
 */
import { chromium } from '../web/node_modules/playwright/index.mjs'
import { readFileSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const WEB = join(ROOT, 'web/public')

/** [SVG-Quelle, Zielpfad, Kantenlänge in px] */
const TARGETS = [
  // — Web / PWA —
  ['favicon.svg', 'web/public/favicon-16.png', 16],
  ['favicon.svg', 'web/public/favicon-32.png', 32],
  ['app-icon.svg', 'web/public/icon-192.png', 192],
  ['app-icon.svg', 'web/public/icon-512.png', 512],
  ['app-icon-maskable.svg', 'web/public/maskable-192.png', 192],
  ['app-icon-maskable.svg', 'web/public/maskable-512.png', 512],
  // iOS maskiert das Apple-Touch-Icon selbst und füllt Transparenz schwarz auf —
  // deshalb bewusst die randlose Variante, nicht die schon gerundete Kachel.
  ['app-icon-maskable.svg', 'web/public/apple-touch-icon.png', 180],
  // — Android Legacy-Launcher-PNGs (API < 26; ab 26 zieht mipmap-anydpi-v26/ic_launcher.xml).
  //   ic_launcher_round ist absichtlich byte-identisch zu ic_launcher (Bestand seit #241).
  ...['mdpi:48', 'hdpi:72', 'xhdpi:96', 'xxhdpi:144', 'xxxhdpi:192'].flatMap((d) => {
    const [dpi, size] = d.split(':')
    return [
      ['app-icon.svg', `android/app/src/main/res/mipmap-${dpi}/ic_launcher.png`, Number(size)],
      ['app-icon.svg', `android/app/src/main/res/mipmap-${dpi}/ic_launcher_round.png`, Number(size)],
    ]
  }),
]

const browser = await chromium.launch()
const page = await browser.newPage({ deviceScaleFactor: 1 })

for (const [src, out, size] of TARGETS) {
  const svg = readFileSync(join(WEB, src), 'utf8')
  await page.setViewportSize({ width: size, height: size })
  await page.setContent(
    `<style>html,body{margin:0;padding:0;background:transparent}
     svg{display:block;width:${size}px;height:${size}px}</style>${svg}`,
  )
  const png = await page.screenshot({ omitBackground: true })
  writeFileSync(join(ROOT, out), png)
  console.log(`${out.padEnd(56)} ${size}x${size}  ${png.length}B  <- ${src}`)
}

await browser.close()
