package com.homebase

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Struktur-Drift-Guard für die API-DTOs (#593).
 *
 * Full-OpenAPI-Codegen wurde bewusst vertagt (ADR 0001 / #547). Stattdessen — im Stil der
 * mechanischen Guards #134/#348/#505 — vergleicht dieser Test die DTO-Deklarationen der **drei**
 * Quellen und fängt den „Feld in 2 von 3 Quellen ergänzt / Optionalität weicht ab"-Fall, ohne
 * Generator oder Kontrakt-Versionierung:
 *  - Backend  `backend/src/main/kotlin/com/homebase/model/Models.kt`  (`@Serializable`, Wire-Wahrheit)
 *  - Android  `android/.../data/model/Models.kt`                      (Moshi `@JsonClass`)
 *  - Web      `web/src/types.ts`                                       (TS-Interfaces)
 *
 * **Optionalität** = „Feld darf in der Payload fehlen":
 *  - Kotlin (Backend/Android): der Parameter hat einen Default (`= …`). Wegen `encodeDefaults = false`
 *    (#46) fehlt ein Nicht-null-Feld mit Kotlin-Default (`Boolean = false`, `= emptyList()`) am Draht,
 *    wenn es auf dem Default steht → gilt als optional. Deshalb ist „hat Default" das Signal, nicht
 *    „ist nullable" (ADR §3 / #593-Umsetzungsnotiz).
 *  - TS (Web): das Feld ist mit `?` deklariert.
 *
 * **Bewusst NICHT geprüft** (siehe #593): Tri-State-Update-Semantik (#265) und konkrete
 * Default-Werte (`?? false`) — in keinem Typ ausdrückbar, bleiben Prosa + Review.
 *
 * Namens-Parität wird strikt geprüft (kein Allowlist). Optionalitäts-Abweichungen sind gegen eine
 * dokumentierte [OPTIONALITY_BASELINE] eingerastet: eine **neue** Abweichung lässt den Test rot
 * werden, eine **aufgelöste** ebenfalls (dann ist der Baseline-Eintrag zu entfernen).
 */
class DtoStructureDriftTest {

    // Ein logisches DTO: Feldname -> „darf fehlen" (optional).
    private data class Dto(val fields: Map<String, Boolean>)

    // ── Quellen-Parser ──────────────────────────────────────────────────────

    // Annahme: kein String-Default und kein Feldwert enthält `//` oder `/* */` (heute in allen drei
    // Quellen erfüllt). Ein solcher Literal-Inhalt würde hier fälschlich als Kommentar entfernt.
    private fun stripComments(text: String): String =
        text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\n]*"), "")

    /**
     * Splittet an Top-Level-Kommas (Klammern `()` und Generics `<>` als Tiefe zählen).
     * Annahme: keine Funktionstyp-Parameter (`(X) -> Y`) — deren `>` würde die Tiefe verfälschen;
     * heute hat kein DTO einen solchen Parameter.
     */
    private fun splitTopLevel(s: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        val cur = StringBuilder()
        for (ch in s) {
            when (ch) {
                '(', '<' -> depth++
                ')', '>' -> depth--
            }
            if (ch == ',' && depth == 0) {
                parts += cur.toString(); cur.setLength(0)
            } else cur.append(ch)
        }
        if (cur.isNotBlank()) parts += cur.toString()
        return parts
    }

    /** Findet den Index der zu [open] passenden schließenden Klammer ab `openIdx`. */
    private fun matchClose(text: String, openIdx: Int, open: Char, close: Char): Int {
        var depth = 0
        for (j in openIdx until text.length) {
            if (text[j] == open) depth++
            else if (text[j] == close) {
                depth--
                if (depth == 0) return j
            }
        }
        error("Unbalanced $open at $openIdx")
    }

    /** Kotlin `data class Name( val f: T = default, … )` → für Backend und Android. */
    private fun parseKotlin(file: File): Map<String, Dto> {
        val t = stripComments(file.readText())
        val out = LinkedHashMap<String, Dto>()
        for (m in Regex("data class (\\w+)\\s*\\(").findAll(t)) {
            val name = m.groupValues[1]
            val openIdx = t.indexOf('(', m.range.first)
            val closeIdx = matchClose(t, openIdx, '(', ')')
            val inner = t.substring(openIdx + 1, closeIdx)
            val fields = LinkedHashMap<String, Boolean>()
            for (raw in splitTopLevel(inner)) {
                val p = raw.trim()
                val fm = Regex("(?:val|var)\\s+(\\w+)\\s*:\\s*(.+)", RegexOption.DOT_MATCHES_ALL).find(p) ?: continue
                fields[fm.groupValues[1]] = fm.groupValues[2].contains("=")
            }
            out[name] = Dto(fields)
        }
        return out
    }

    /** TS `export interface Name { f?: T; … }` → für Web. */
    private fun parseTs(file: File): Map<String, Dto> {
        val t = stripComments(file.readText())
        val out = LinkedHashMap<String, Dto>()
        for (m in Regex("export interface (\\w+)\\s*\\{").findAll(t)) {
            val name = m.groupValues[1]
            val openIdx = t.indexOf('{', m.range.first)
            val closeIdx = matchClose(t, openIdx, '{', '}')
            val inner = t.substring(openIdx + 1, closeIdx)
            val fields = LinkedHashMap<String, Boolean>()
            for (line in inner.split("\n")) {
                val l = line.trim().trimEnd(',', ';')
                val fm = Regex("(\\w+)(\\??)\\s*:\\s*(.+)").find(l) ?: continue
                fields[fm.groupValues[1]] = fm.groupValues[2] == "?"
            }
            out[name] = Dto(fields)
        }
        return out
    }

    // Backend/Android führen teils `…Dto`-Suffix, Web nicht → auf gemeinsamen logischen Namen mappen.
    private fun logical(name: String) = name.removeSuffix("Dto")

    private fun byLogical(m: Map<String, Dto>): Map<String, Dto> {
        val out = LinkedHashMap<String, Dto>()
        for ((name, dto) in m) {
            val key = logical(name)
            // Kollision (z. B. `Foo` und `FooDto` in derselben Quelle) würde eines der DTOs still
            // überschreiben und echte Drift maskieren → laut scheitern statt schlucken.
            if (out.containsKey(key)) fail("Logischer DTO-Name '$key' kollidiert (mehrere Quell-DTOs mappen darauf)")
            out[key] = dto
        }
        return out
    }

    // ── Repo-Root robust finden (Test-CWD = backend/) ───────────────────────

    private fun repoRoot(): File {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            if (File(d, "backend").isDirectory && File(d, "android").isDirectory && File(d, "web").isDirectory) return d
            d = d.parentFile
        }
        fail("Repo-Root (mit backend/ android/ web/) nicht gefunden ab ${System.getProperty("user.dir")}")
    }

    private fun sources(): Triple<Map<String, Dto>, Map<String, Dto>, Map<String, Dto>> {
        val root = repoRoot()
        val be = byLogical(parseKotlin(File(root, "backend/src/main/kotlin/com/homebase/model/Models.kt")))
        val an = byLogical(parseKotlin(File(root, "android/app/src/main/kotlin/com/homebase/android/data/model/Models.kt")))
        val we = byLogical(parseTs(File(root, "web/src/types.ts")))
        return Triple(be, an, we)
    }

    // ── Tests ───────────────────────────────────────────────────────────────

    /** Anti-Trivial-Guard: ein kaputter Parser (0 DTOs) darf den Test nicht still grün machen. */
    @Test
    fun `parser findet plausibel viele DTOs pro Quelle`() {
        val (be, an, we) = sources()
        assertTrue(be.size >= 60, "Backend-DTOs zu wenige: ${be.size}")
        assertTrue(an.size >= 60, "Android-DTOs zu wenige: ${an.size}")
        assertTrue(we.size >= 30, "Web-Interfaces zu wenige: ${we.size}")
    }

    /**
     * Namens-Parität: für jedes DTO, das in ≥2 Quellen existiert, muss die Feld-Namen-Menge
     * quellenübergreifend identisch sein. Fängt „Feld in 2 von 3 Quellen ergänzt". Kein Allowlist.
     */
    @Test
    fun `keine Feld-Namen-Drift zwischen den Quellen`() {
        val (be, an, we) = sources()
        val srcs = mapOf("BE" to be, "AN" to an, "WE" to we)
        val drift = mutableListOf<String>()
        for (name in (be.keys + an.keys + we.keys).sorted()) {
            val present = srcs.filterValues { name in it }.mapValues { it.value.getValue(name).fields }
            if (present.size < 2) continue
            val allFields = present.values.flatMap { it.keys }.toSortedSet()
            for (f in allFields) {
                val has = present.filterValues { f in it }.keys.sorted()
                val missing = present.keys - has.toSet()
                if (missing.isNotEmpty()) drift += "$name.$f: vorhanden in $has, fehlt in ${missing.sorted()}"
            }
        }
        assertTrue(drift.isEmpty(), "Feld-Namen-Drift gefunden:\n" + drift.joinToString("\n"))
    }

    /**
     * Optionalitäts-Parität, eingerastet gegen [OPTIONALITY_BASELINE]. Neue Abweichung ⇒ rot;
     * aufgelöste Abweichung ⇒ rot (Baseline-Eintrag entfernen).
     */
    @Test
    fun `Optionalitaets-Abweichungen entsprechen der Baseline`() {
        val (be, an, we) = sources()
        val srcs = mapOf("BE" to be, "AN" to an, "WE" to we)
        val found = mutableMapOf<String, String>() // key -> menschenlesbares Detail
        for (name in (be.keys + an.keys + we.keys).sorted()) {
            val present = srcs.filterValues { name in it }.mapValues { it.value.getValue(name).fields }
            if (present.size < 2) continue
            val shared = present.values.map { it.keys }.reduce { a, b -> a intersect b }.sorted()
            for (f in shared) {
                val opt = present.mapValues { it.value.getValue(f) }
                if (opt.values.toSet().size > 1) {
                    val optIn = opt.filterValues { it }.keys.sorted()
                    val reqIn = opt.filterValues { !it }.keys.sorted()
                    found["$name.$f"] = "$name.$f: optional in $optIn, required in $reqIn"
                }
            }
        }
        val unexpected = (found.keys - OPTIONALITY_BASELINE).sorted().map { found.getValue(it) }
        val stale = (OPTIONALITY_BASELINE - found.keys).sorted()
        val msgs = buildList {
            if (unexpected.isNotEmpty())
                add("NEUE Optionalitäts-Drift (Feld angleichen ODER — falls bewusst — in OPTIONALITY_BASELINE aufnehmen):\n  " +
                    unexpected.joinToString("\n  "))
            if (stale.isNotEmpty())
                add("VERALTETE Baseline-Einträge (Abweichung ist weg → aus OPTIONALITY_BASELINE entfernen):\n  " +
                    stale.joinToString("\n  "))
        }
        assertTrue(msgs.isEmpty(), msgs.joinToString("\n\n"))
    }

    companion object {
        /**
         * Aktuell akzeptierte Optionalitäts-Abweichungen (Stand #593). Key = `LogischesDto.feld`.
         *
         * Bucket 1 — Client defensiver als ein wire-**pflicht**-Feld: der Backend-Serializer sendet
         * das Feld immer (kein Default), Web/Android tolerieren zusätzlich sein Fehlen. Harmlos, da
         * „lenienter als der Draht" nie zum #96-Crash führt. Bleibt dauerhaft.
         *
         * Bucket 2 — Web typisiert eine wire-**optionale** Liste als `required`: der Backend lässt die
         * leere Liste weg (`encodeDefaults=false`), die Web-Lesestellen kompensieren mit `?? []`, aber
         * der TS-Typ behauptet `required`. Latente Typ-vs-Draht-Lücke, getrackt in #597 (dort ggf.
         * Web-Typen auf `?` ziehen und diese Einträge entfernen).
         */
        private val OPTIONALITY_BASELINE = setOf(
            // Bucket 1 — defensive client (dauerhaft)
            "CalendarEvent.allDay",
            "CalendarEvent.type",
            "CalendarFeedConfigResponse.availableSections",
            "CalendarFeedConfigResponse.sections",
            "CustomHoliday.half",
            "DigestConfigResponse.availableSections",
            "DigestConfigResponse.sections",
            "DigestConfigResponse.telegramConfigured",
            "Note.attachments",
            "Note.images",
            "Note.tags",
            "SetAvatarColorRequest.hue",
            "ShoppingSuggestion.count",
            "ShoppingTemplateItem.sortOrder",
            // Bucket 2 — Web required vs. wire-optionale Liste, per `?? []` kompensiert (#597)
            "AbsenceState.absences",
            "AbsenceState.customHolidays",
            "AbsenceState.kitaClosures",
            "AbsenceState.partTime",
            "AbsenceState.settings",
            "AbsenceState.users",
            "Recipe.ingredients",
            "Recipe.steps",
            "ShoppingTemplate.items",
        )
    }
}
