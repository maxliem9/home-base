package com.homebase.android

import com.homebase.android.data.model.IngredientInput
import com.homebase.android.data.model.RecipeDraftDto
import com.homebase.android.data.model.RecipeStepInput
import com.homebase.android.ui.recipes.IngredientDraft
import com.homebase.android.ui.recipes.RecipeFormPrefill
import com.homebase.android.ui.recipes.sectionsFromInputs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Recipe URL-import → editor prefill mapping (#460). Mirrors the web `draftFromImport`: a draft has
 * no id (always a NEW recipe), best-effort fields the page didn't provide stay blank, ingredient
 * amounts arrive as numbers and become editable text, and the category is folded into the offered
 * chip set.
 */
class RecipeImportMappingTest {

    @Test
    fun `a fully populated draft maps every field into the editor prefill`() {
        val draft = RecipeDraftDto(
            title = "Ofengemüse",
            description = "Schnell und einfach",
            servings = 4,
            prepTimeMinutes = 15,
            cookTimeMinutes = 30,
            category = "DINNER",
            ingredients = listOf(
                IngredientInput(name = "Kartoffeln", amount = 500.0, unit = "g"),
                IngredientInput(name = "Olivenöl", amount = 2.0, unit = "EL"),
            ),
            steps = listOf(RecipeStepInput("Vorheizen"), RecipeStepInput("Backen")),
            sourceUrl = "https://example.com/rezept",
        )

        val prefill = RecipeFormPrefill.ofImport(draft)

        assertEquals("Ofengemüse", prefill.title)
        assertEquals("Schnell und einfach", prefill.description)
        assertEquals("4", prefill.servings)
        assertEquals("15", prefill.prep)
        assertEquals("30", prefill.cook)
        assertEquals("DINNER", prefill.categoryCode)
        // one ungrouped section with both ingredients, amounts as editable text
        assertEquals(1, prefill.sections.size)
        assertEquals("", prefill.sections[0].name)
        assertEquals(
            listOf(
                IngredientDraft(name = "Kartoffeln", amount = "500", unit = "g"),
                IngredientDraft(name = "Olivenöl", amount = "2", unit = "EL"),
            ),
            prefill.sections[0].ingredients,
        )
        assertEquals("Vorheizen\nBacken", prefill.stepsText)
    }

    @Test
    fun `missing best-effort fields stay blank and the editor still has a row`() {
        // Only a title (everything else absent, like a page with minimal JSON-LD).
        val prefill = RecipeFormPrefill.ofImport(RecipeDraftDto(title = "Nur Titel", category = "SNACK"))

        assertEquals("Nur Titel", prefill.title)
        assertEquals("", prefill.description)
        assertEquals("", prefill.servings)
        assertEquals("", prefill.prep)
        assertEquals("", prefill.cook)
        assertEquals("SNACK", prefill.categoryCode)
        assertEquals("", prefill.stepsText)
        // a single blank section so the editor always has something to type into
        assertEquals(1, prefill.sections.size)
        assertEquals(listOf(IngredientDraft()), prefill.sections[0].ingredients)
    }

    @Test
    fun `an unknown or legacy category folds into the offered set`() {
        assertEquals("DINNER", RecipeFormPrefill.ofImport(RecipeDraftDto(title = "x", category = "LUNCH")).categoryCode)
        assertEquals("DINNER", RecipeFormPrefill.ofImport(RecipeDraftDto(title = "x", category = "WHATEVER")).categoryCode)
        assertEquals("DESSERT", RecipeFormPrefill.ofImport(RecipeDraftDto(title = "x", category = "dessert")).categoryCode)
    }

    @Test
    fun `consecutive ingredients sharing a section label group together`() {
        val sections = sectionsFromInputs(
            listOf(
                IngredientInput(name = "Mehl", amount = 200.0, unit = "g", section = "Boden"),
                IngredientInput(name = "Butter", amount = 100.0, unit = "g", section = "Boden"),
                IngredientInput(name = "Sahne", amount = 200.0, unit = "ml", section = "Füllung"),
            ),
        )
        assertEquals(2, sections.size)
        assertEquals("Boden", sections[0].name)
        assertEquals(listOf("Mehl", "Butter"), sections[0].ingredients.map { it.name })
        assertEquals("Füllung", sections[1].name)
        assertEquals(listOf("Sahne"), sections[1].ingredients.map { it.name })
    }
}
