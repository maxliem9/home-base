package com.homebase.routes

import com.homebase.model.IngredientInput
import com.homebase.model.RecipeDraftDto
import com.homebase.model.RecipeStepInput
import com.homebase.plugins.appJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Duration
import kotlin.math.roundToInt

/**
 * Pure JSON-LD → recipe-draft extraction/mapping for the URL import (Issue #430).
 *
 * Everything in this file is side-effect-free and HTTP-free so it can be unit-tested directly
 * (no network). The HTTP fetch + SSRF guards live in [RecipeRoutes] / the route handler.
 *
 * The web is messy, so we parse defensively via [JsonElement] navigation (never strict @Serializable
 * decoding): every field is best-effort, missing pieces fall back to sensible defaults, and the
 * user reviews the result in the editor before it is ever persisted.
 */
object RecipeImport {

    // Tolerant parser for the messy JSON-LD blobs (unknown keys, trailing commas). We reuse the
    // central [appJson] (isLenient + ignoreUnknownKeys) instead of a local Json instance: this is
    // pure *decoding* (parseToJsonElement), so encodeDefaults is irrelevant, and routing through the
    // one shared instance keeps the #134 "only Serialization.kt builds Json" convention intact.

    /** The five categories the app accepts (LUNCH was dropped — see migration V17). */
    private val VALID_CATEGORIES = setOf("BREAKFAST", "DINNER", "SNACK", "DESSERT", "DRINK")
    const val DEFAULT_CATEGORY = "DINNER"

    private val KNOWN_UNITS = setOf(
        "g", "kg", "mg", "ml", "cl", "dl", "l", "el", "tl", "msp", "prise", "prisen", "stück", "stk", "st",
        "dose", "dosen", "pkg", "packung", "päckchen", "bund", "zehe", "zehen", "scheibe", "scheiben",
        "tasse", "tassen", "becher", "glas", "cm", "mm", "kugel", "kugeln", "blatt", "blätter",
        // common English units seen on international recipe sites
        "cup", "cups", "tbsp", "tsp", "oz", "lb", "lbs", "pound", "pounds", "ounce", "ounces",
        "teaspoon", "teaspoons", "tablespoon", "tablespoons", "clove", "cloves", "slice", "slices",
        "pinch", "can", "cans", "package", "packages",
    )

    /**
     * Extract every `<script type="application/ld+json">` block from raw HTML.
     * Case-insensitive on the type attribute; tolerant of extra attributes / whitespace.
     */
    fun extractJsonLdBlocks(html: String): List<String> {
        val blocks = mutableListOf<String>()
        // Match <script ... type="application/ld+json" ...> CONTENT </script>
        val regex = Regex(
            """<script\b[^>]*\btype\s*=\s*["']application/ld\+json["'][^>]*>(.*?)</script>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        for (m in regex.findAll(html)) {
            val content = m.groupValues[1].trim()
            if (content.isNotEmpty()) blocks.add(content)
        }
        return blocks
    }

    /**
     * Top-level entry for the route: given raw HTML, find the schema.org Recipe and map it to a
     * draft. Returns null when no Recipe node is present (caller → 422).
     */
    fun fromHtml(html: String, sourceUrl: String? = null): RecipeDraftDto? {
        val node = extractJsonLdBlocks(html)
            .asSequence()
            .mapNotNull { runCatching { appJson.parseToJsonElement(it) }.getOrNull() }
            .firstNotNullOfOrNull { findRecipeNode(it) }
            ?: return null
        return mapRecipeNode(node, sourceUrl)
    }

    /**
     * Walk a parsed JSON-LD element and return the first node whose @type is (or contains) "Recipe".
     * Handles: a bare Recipe object, an array of nodes, and an `@graph` wrapper (recursively).
     */
    fun findRecipeNode(element: JsonElement): JsonObject? = when (element) {
        is JsonObject -> when {
            isRecipeType(element["@type"]) -> element
            element["@graph"] != null -> findRecipeNode(element["@graph"]!!)
            else -> null
        }
        is JsonArray -> element.firstNotNullOfOrNull { findRecipeNode(it) }
        else -> null
    }

    /** @type may be a string ("Recipe") or an array (["Recipe", "NewsArticle"]). */
    private fun isRecipeType(type: JsonElement?): Boolean = when (type) {
        is JsonPrimitive -> type.contentOrNull?.equals("Recipe", ignoreCase = true) == true
        is JsonArray -> type.any { it is JsonPrimitive && it.contentOrNull?.equals("Recipe", ignoreCase = true) == true }
        else -> false
    }

    /** Map a confirmed Recipe JSON object to the app's draft shape. Public for testing. */
    fun mapRecipeNode(node: JsonObject, sourceUrl: String? = null): RecipeDraftDto {
        val title = (firstString(node["name"]) ?: firstString(node["headline"]))?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Importiertes Rezept"
        val description = firstString(node["description"])?.trim()?.takeIf { it.isNotEmpty() }
        return RecipeDraftDto(
            title = title,
            description = description,
            servings = parseYield(node["recipeYield"]),
            prepTimeMinutes = parseIsoMinutes(node["prepTime"]),
            cookTimeMinutes = parseIsoMinutes(node["cookTime"]),
            category = parseCategory(node["recipeCategory"]),
            ingredients = parseIngredients(node["recipeIngredient"] ?: node["ingredients"]),
            steps = parseInstructions(node["recipeInstructions"]),
            sourceUrl = sourceUrl,
        )
    }

    /**
     * recipeCategory is freetext ("Dessert", "Hauptgericht", "Vorspeise"). Best-effort map to one
     * of our five enum values; anything unrecognized (incl. missing) falls back to DINNER. The user
     * can change it in the editor — this is only a suggestion.
     */
    fun parseCategory(el: JsonElement?): String {
        val raw = firstString(el)?.trim()?.lowercase() ?: return DEFAULT_CATEGORY
        // direct enum match (international JSON-LD sometimes already uses the enum-ish word)
        VALID_CATEGORIES.firstOrNull { it.equals(raw, ignoreCase = true) }?.let { return it }
        return when {
            listOf("frühstück", "breakfast", "brunch").any { raw.contains(it) } -> "BREAKFAST"
            listOf("dessert", "nachtisch", "nachspeise", "süßspeise", "kuchen", "gebäck", "cake", "sweet").any { raw.contains(it) } -> "DESSERT"
            listOf("getränk", "drink", "cocktail", "smoothie", "beverage").any { raw.contains(it) } -> "DRINK"
            listOf("snack", "fingerfood", "vorspeise", "appetizer", "beilage", "side").any { raw.contains(it) } -> "SNACK"
            else -> DEFAULT_CATEGORY
        }
    }

    // --- field parsers ----------------------------------------------------------------------

    /** First plain string from a primitive, or the first element of an array. */
    private fun firstString(el: JsonElement?): String? = when (el) {
        is JsonPrimitive -> el.contentOrNull
        is JsonArray -> el.firstNotNullOfOrNull { firstString(it) }
        else -> null
    }

    /**
     * recipeYield: "4", "4 servings", "Serves 4", or ["4 servings", "4"]. Pull the first integer.
     */
    fun parseYield(el: JsonElement?): Int? {
        val raw = when (el) {
            is JsonPrimitive -> el.contentOrNull
            is JsonArray -> el.firstNotNullOfOrNull {
                when (it) {
                    is JsonPrimitive -> it.contentOrNull
                    else -> null
                }
            }
            else -> null
        } ?: return null
        val n = Regex("""\d+""").find(raw)?.value?.toIntOrNull() ?: return null
        return if (n in 1..999) n else null
    }

    /**
     * Parse an ISO-8601 duration ("PT30M", "PT1H15M") to whole minutes. Tolerates a stray leading
     * string. Returns null on anything java.time can't parse.
     */
    fun parseIsoMinutes(el: JsonElement?): Int? {
        val raw = firstString(el)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { Duration.parse(raw).toMinutes().toInt() }
            .getOrNull()
            ?.takeIf { it in 0..100_000 }
    }

    /**
     * recipeIngredient is an array of strings ("200 g Mehl"). Best-effort split into
     * amount / unit / name, mirroring the web/Android free-text parser
     * (web/src/components/recipeIngredients.ts).
     */
    fun parseIngredients(el: JsonElement?): List<IngredientInput> {
        val lines = when (el) {
            is JsonArray -> el.mapNotNull { stringOrTextField(it) }
            is JsonPrimitive -> listOfNotNull(el.contentOrNull)
            else -> emptyList()
        }
        return lines.map { it.trim() }.filter { it.isNotEmpty() }.map { parseIngredientLine(it) }
    }

    /** A node may be a plain string or an object with a `text`/`name` field. */
    private fun stringOrTextField(el: JsonElement): String? = when (el) {
        is JsonPrimitive -> el.contentOrNull
        is JsonObject -> firstString(el["text"]) ?: firstString(el["name"])
        else -> null
    }

    /**
     * recipeInstructions: a single string (split on newlines), an array of strings, or an array of
     * HowToStep / HowToSection objects (each with `text`, sections nest `itemListElement`).
     */
    fun parseInstructions(el: JsonElement?): List<RecipeStepInput> {
        val texts: List<String> = when (el) {
            is JsonPrimitive -> el.contentOrNull
                ?.split(Regex("""\r?\n+"""))
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
            is JsonArray -> el.flatMap { flattenInstruction(it) }
            is JsonObject -> flattenInstruction(el)
            else -> emptyList()
        }
        return texts.map { it.trim() }.filter { it.isNotEmpty() }.map { RecipeStepInput(it) }
    }

    /** One instruction element → its step texts (HowToSection expands to its child steps). */
    private fun flattenInstruction(el: JsonElement): List<String> = when (el) {
        is JsonPrimitive -> listOfNotNull(el.contentOrNull)
        is JsonObject -> {
            val type = (el["@type"] as? JsonPrimitive)?.contentOrNull
            if (type?.equals("HowToSection", ignoreCase = true) == true) {
                val children = el["itemListElement"]
                if (children is JsonArray) children.flatMap { flattenInstruction(it) } else emptyList()
            } else {
                // HowToStep (or any object): prefer `text`, fall back to `name`.
                listOfNotNull(firstString(el["text"]) ?: firstString(el["name"]))
            }
        }
        is JsonArray -> el.flatMap { flattenInstruction(it) }
        else -> emptyList()
    }

    /**
     * Split a single ingredient line into amount / unit / name. Best-effort, intentionally
     * identical in spirit to web/src/components/recipeIngredients.ts:
     *  - leading number / fraction (a/b) / mixed number ("1 1/2") / range (a-b → lower bound)
     *  - first token after the amount is treated as a unit ONLY if it's a known unit
     */
    fun parseIngredientLine(line: String): IngredientInput {
        val trimmed = line.trim()
        val m = Regex("""^(\S+)\s+(.*)$""").find(trimmed)
            ?: return IngredientInput(name = trimmed)
        val first = m.groupValues[1]
        var rest = m.groupValues[2].trim()
        var amount = parseAmountToken(first) ?: return IngredientInput(name = trimmed)

        // mixed number: bare integer + "b/c" fraction ("1 1/2" → 1.5)
        if (Regex("""^\d+$""").matches(first)) {
            val fracNext = Regex("""^(\d+)/(\d+)(?:\s+(.*))?$""").find(rest)
            if (fracNext != null) {
                val b = fracNext.groupValues[2].toDouble()
                if (b != 0.0) {
                    amount = round3(first.toDouble() + fracNext.groupValues[1].toDouble() / b)
                    rest = fracNext.groupValues.getOrNull(3)?.trim() ?: ""
                }
            }
        }
        if (rest.isEmpty()) return IngredientInput(name = "", amount = amount)

        val parts = rest.split(Regex("""\s+"""))
        return if (parts.size > 1 && isUnitToken(parts[0])) {
            IngredientInput(name = parts.drop(1).joinToString(" "), amount = amount, unit = parts[0])
        } else {
            IngredientInput(name = rest, amount = amount)
        }
    }

    private fun isUnitToken(tok: String) = KNOWN_UNITS.contains(tok.lowercase().removeSuffix("."))

    /**
     * Leading amount token → normalized double, or null if it's not a clean number/fraction/range.
     * Accepts decimal comma. Range "a-b" → lower bound (predictable, never over-shops).
     */
    private fun parseAmountToken(tok: String): Double? {
        fun num(s: String) = s.replace(',', '.').toDoubleOrNull()
        Regex("""^(\d+(?:[.,]\d+)?)-(\d+(?:[.,]\d+)?)$""").find(tok)?.let {
            return num(it.groupValues[1])?.let { lo -> round3(lo) }
        }
        Regex("""^(\d+(?:[.,]\d+)?)/(\d+(?:[.,]\d+)?)$""").find(tok)?.let {
            val a = num(it.groupValues[1]); val b = num(it.groupValues[2])
            return if (a != null && b != null && b != 0.0) round3(a / b) else null
        }
        if (Regex("""^\d+(?:[.,]\d+)?$""").matches(tok)) return num(tok)?.let { round3(it) }
        return null
    }

    /** Round to 3 decimals via integer math (keeps 1/3, 2/3 honest), matching the web parser. */
    private fun round3(n: Double): Double = (n * 1000.0).roundToInt() / 1000.0
}
