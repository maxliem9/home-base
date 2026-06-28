package com.homebase.android.ui.shopping

import com.homebase.android.data.model.ShoppingItemDto

/**
 * Free-text quantity parsing for the tile/row detail line (#447) — the Android mirror of the web
 * `shoppingQuantity.ts`. Display-only; an independent copy of the catalog unit list (keep roughly in
 * sync). Never throws, never yields an empty title.
 */
object ShoppingQuantity {

    private const val UNITS =
        "g|kg|mg|ml|l|el|tl|stk|stück|st|x|prise|prisen|bund|dose|dosen|pkg|pck|pack|packung|" +
            "tasse|cup|msp|glas|gläser|becher|flasche|flaschen"

    private val QTY_PREFIX = Regex("""^\s*(\d+([.,]\d+)?\s*($UNITS)?\.?)\s+(.+)$""", RegexOption.IGNORE_CASE)
    // Same but the unit is mandatory — gates the destructive add-time split so a bare leading number
    // ("3 Musketiere", "2 Äpfel") is not torn apart when persisting.
    private val QTY_PREFIX_WITH_UNIT = Regex("""^\s*\d+([.,]\d+)?\s*($UNITS)\.?\s+.+$""", RegexOption.IGNORE_CASE)

    data class Parts(val title: String, val detail: String? = null)

    /**
     * Split a leading "<qty> <unit>" prefix off a name. [requireUnit] (used at add-time, where the
     * split is persisted) only splits when a real unit is present; the lenient default (display) also
     * splits a bare leading count. "2 L Milch" → ("Milch", "2 L"); no prefix → (name, null).
     */
    fun splitQuantity(name: String, requireUnit: Boolean = false): Parts {
        val m = QTY_PREFIX.matchEntire(name) ?: return Parts(name)
        val rest = m.groupValues[4].trim()
        if (rest.isEmpty()) return Parts(name)
        if (requireUnit && !QTY_PREFIX_WITH_UNIT.matches(name)) return Parts(name)
        return Parts(rest, m.groupValues[1].trim())
    }

    /**
     * Title + quantity to display for an item (#447): an explicit [ShoppingItemDto.quantity] wins
     * (name stays whole); otherwise the quantity is parsed out of the name. Keeps tiles and rows
     * consistent whether the amount was entered structurally or typed into the name.
     */
    fun displayParts(item: ShoppingItemDto): Parts {
        val q = item.quantity?.trim()
        if (!q.isNullOrEmpty()) return Parts(item.name, q)
        return splitQuantity(item.name)
    }
}
