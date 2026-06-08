package com.homebase.routes

import com.homebase.model.IngredientDto
import com.homebase.model.RecipeDto
import com.lowagie.text.Document
import com.lowagie.text.Font
import com.lowagie.text.ListItem
import com.lowagie.text.PageSize
import com.lowagie.text.Paragraph
import com.lowagie.text.pdf.PdfWriter
import java.awt.Color
import java.io.ByteArrayOutputStream
import java.text.Normalizer

/**
 * Single-recipe export in two human-readable formats (issue #136):
 *  - Markdown — lightweight, shareable, no dependencies;
 *  - PDF — print-friendly, rendered server-side with OpenPDF.
 *
 * Both render the same German-facing content (mirroring the CSV export's German
 * convention): a title, a meta line (category · servings · prep/cook time), the
 * optional description, the ingredient list and the numbered steps. The recipe is
 * already serving-scaled by the caller when `?servings=N` is supplied, so these
 * builders just format whatever amounts they are handed.
 */

internal fun buildRecipeMarkdown(r: RecipeDto): String {
    val sb = StringBuilder()
    sb.append("# ").append(r.title).append("\n\n")
    sb.append("_").append(metaLine(r)).append("_\n\n")
    r.description?.takeIf { it.isNotBlank() }?.let { sb.append(it.trim()).append("\n\n") }

    if (r.ingredients.isNotEmpty()) {
        sb.append("## Zutaten\n\n")
        for (ing in r.ingredients) sb.append("- ").append(ingredientLine(ing)).append("\n")
        sb.append("\n")
    }
    if (r.steps.isNotEmpty()) {
        sb.append("## Zubereitung\n\n")
        r.steps.forEachIndexed { i, step -> sb.append(i + 1).append(". ").append(step.description.trim()).append("\n") }
        sb.append("\n")
    }
    return sb.toString().trimEnd() + "\n"
}

internal fun buildRecipePdf(r: RecipeDto): ByteArray {
    val out = ByteArrayOutputStream()
    val doc = Document(PageSize.A4, 56f, 56f, 54f, 54f)
    PdfWriter.getInstance(doc, out)
    doc.open()
    try {
        val titleFont = Font(Font.HELVETICA, 22f, Font.BOLD)
        val metaFont = Font(Font.HELVETICA, 10f, Font.ITALIC, Color(0x6B7280))
        val sectionFont = Font(Font.HELVETICA, 14f, Font.BOLD)
        val bodyFont = Font(Font.HELVETICA, 11f)

        doc.add(Paragraph(r.title, titleFont).apply { spacingAfter = 6f })
        doc.add(Paragraph(metaLine(r), metaFont).apply { spacingAfter = 12f })
        r.description?.takeIf { it.isNotBlank() }?.let {
            doc.add(Paragraph(it.trim(), bodyFont).apply { spacingAfter = 14f })
        }

        if (r.ingredients.isNotEmpty()) {
            doc.add(Paragraph("Zutaten", sectionFont).apply { spacingAfter = 6f })
            val list = com.lowagie.text.List(false, 14f).apply { setListSymbol("•  ") }
            for (ing in r.ingredients) list.add(ListItem(ingredientLine(ing), bodyFont))
            doc.add(list)
        }
        if (r.steps.isNotEmpty()) {
            doc.add(Paragraph("Zubereitung", sectionFont).apply { spacingBefore = 16f; spacingAfter = 6f })
            val list = com.lowagie.text.List(true, 16f)
            for (step in r.steps) list.add(ListItem(step.description.trim(), bodyFont).apply { spacingAfter = 4f })
            doc.add(list)
        }
    } finally {
        doc.close()
    }
    return out.toByteArray()
}

/** "Frühstück · 2 Portionen · Vorbereitung 10 Min · Kochzeit 15 Min" */
private fun metaLine(r: RecipeDto): String = listOfNotNull(
    germanCategory(r.category),
    "${r.servings} ${if (r.servings == 1) "Portion" else "Portionen"}",
    r.prepTimeMinutes?.let { "Vorbereitung $it Min" },
    r.cookTimeMinutes?.let { "Kochzeit $it Min" },
).joinToString(" · ")

/** "200 g Mehl" / "Salz" / "2 Eier" — amount and unit are both optional. */
private fun ingredientLine(ing: IngredientDto): String = listOfNotNull(
    ing.amount?.let { fmtAmount(it) },
    ing.unit?.takeIf { it.isNotBlank() },
    ing.name,
).joinToString(" ")

/** Integers print bare ("200"); fractions use a German decimal comma ("1,5"). */
private fun fmtAmount(value: Double): String {
    val rounded = Math.round(value * 1000.0) / 1000.0
    return if (rounded == Math.floor(rounded)) {
        rounded.toLong().toString()
    } else {
        rounded.toString().trimEnd('0').trimEnd('.').replace('.', ',')
    }
}

private fun germanCategory(category: String): String = when (category.uppercase()) {
    "BREAKFAST" -> "Frühstück"
    "LUNCH" -> "Mittagessen"
    "DINNER" -> "Abendessen"
    "SNACK" -> "Snack"
    "DESSERT" -> "Dessert"
    "DRINK" -> "Getränk"
    else -> category
}

/**
 * ASCII slug for the download filename. German umlauts expand (ä→ae …); remaining
 * accents are stripped via NFD; everything else collapses to single hyphens.
 */
internal fun recipeSlug(title: String): String {
    val expanded = title.lowercase()
        .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
    val noAccents = Normalizer.normalize(expanded, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    val slug = buildString {
        for (c in noAccents) append(if (c in 'a'..'z' || c in '0'..'9') c else '-')
    }.replace(Regex("-+"), "-").trim('-')
    return slug.ifBlank { "rezept" }
}
