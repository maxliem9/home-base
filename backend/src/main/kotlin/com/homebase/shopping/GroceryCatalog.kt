package com.homebase.shopping

/**
 * Server-authoritative grocery catalog (#389/#390): maps a written item name to a shopping
 * **category key** + **emoji icon**, and defines the fixed category set (key, German label, header
 * emoji, route order).
 *
 * Seed data + `normalize` algorithm only (#411): the live, editable catalog (category list +
 * name→category rules) now lives in the DB and is read via `ShoppingCatalog`; this object just seeds
 * the empty tables and supplies `normalize`. Shopping item rows store the *resolved*
 * category + icon (a denormalized cache, overridable per household via `shopping_item_stats`), so
 * the clients only render. Emoji is deliberately kept swappable: the stored `icon` is just a cache
 * of catalog resolution, and the clients render it behind one `<ItemIcon>` seam — switching to an
 * SVG/icon-font scheme later means changing the icons here + that one component + a re-resolve, with
 * call sites untouched.
 *
 * The seed is the German household-staple list (ported & expanded from the design prototype's `FREQ`
 * table). It need not be exhaustive: an unknown name resolves to [OTHER] + a neutral cart icon and
 * can be corrected in the UI, which is then remembered in the stats table for next time.
 *
 * Categorization is a presentation nicety, not a hard contract — keep the seed pragmatic and lean.
 */
object GroceryCatalog {

    /** Catch-all category for names the catalog doesn't know. */
    const val OTHER: String = "OTHER"

    /** Neutral icon for unknown items (the "shopping cart" emoji). */
    const val DEFAULT_ICON: String = "🛒" // 🛒

    data class Category(val key: String, val label: String, val emoji: String, val order: Int)

    /**
     * The fixed category set, in shopping-route order. Clients group + order by [order] and render
     * [label]/[emoji] in the section header. Intentionally small (10 entries) — the clients mirror
     * this presentation metadata, like the recipe categories, while the big item→category map stays
     * here on the server.
     */
    val categories: List<Category> = listOf(
        Category("PRODUCE", "Obst & Gemüse", "🥦", 0),       // 🥦
        Category("BAKERY", "Backwaren", "🥐", 1),            // 🥐
        Category("DAIRY", "Milchprodukte & Eier", "🧀", 2),  // 🧀
        Category("MEAT_FISH", "Fleisch & Fisch", "🥩", 3),   // 🥩
        Category("FROZEN", "Tiefkühl", "🧊", 4),             // 🧊
        Category("PANTRY", "Vorrat", "🥫", 5),               // 🥫
        Category("SNACKS", "Snacks & Süßes", "🍫", 6),       // 🍫
        Category("DRINKS", "Getränke", "🥤", 7),             // 🥤
        Category("HOUSEHOLD", "Haushalt & Hygiene", "🧽", 8),// 🧽
        Category(OTHER, "Sonstiges", "❓", 9),                     // ❓
    )

    /** Resolution of a name to its category key + icon. */
    data class Resolution(val category: String, val icon: String)

    /** One catalog entry, exposed for the suggestions baseline. */
    data class CatalogItem(val name: String, val category: String, val icon: String, val normalized: String)

    // Leading "<number> <unit>" stripper for normalize(). Declared before `seed` on purpose: the
    // seed normalizes each name during init, and a Kotlin object initializes its properties top-down,
    // so this must exist before `seed` runs (else normalize() reads a null regex → init crash).
    private val LEADING_QTY = Regex(
        "^\\s*\\d+([.,]\\d+)?\\s*" +
            "(g|kg|mg|ml|l|el|tl|stk|stück|st|x|prise|prisen|bund|dose|dosen|pkg|pck|pack|packung|" +
            "tasse|cup|msp|glas|gläser|becher|flasche|flaschen)?\\.?\\s+",
        RegexOption.IGNORE_CASE,
    )

    // ---- Seed: written name -> emoji, grouped by category --------------------------------------

    // The auto-resolve seed (#411): used once to populate the editable `shopping_category_rules` table
    // (ShoppingCatalog.seedIfEmpty). Resolution itself reads the DB rules, not this list.
    val seed: List<CatalogItem> = buildList {
        fun cat(category: String, vararg pairs: Pair<String, String>) {
            for ((name, icon) in pairs) add(CatalogItem(name, category, icon, normalize(name)))
        }
        cat(
            "PRODUCE",
            "Tomaten" to "🍅", "Äpfel" to "🍎", "Bananen" to "🍌",
            "Kartoffeln" to "🥔", "Zwiebeln" to "🧅", "Knoblauch" to "🧄",
            "Karotten" to "🥕", "Möhren" to "🥕", "Paprika" to "🫑",
            "Gurke" to "🥒", "Gurken" to "🥒", "Salat" to "🥬",
            "Kopfsalat" to "🥬", "Feldsalat" to "🥬", "Avocado" to "🥑",
            "Avocados" to "🥑", "Zitronen" to "🍋", "Limetten" to "🍋",
            "Orangen" to "🍊", "Mandarinen" to "🍊", "Birnen" to "🍐",
            "Trauben" to "🍇", "Weintrauben" to "🍇", "Erdbeeren" to "🍓",
            "Heidelbeeren" to "🫐", "Blaubeeren" to "🫐", "Himbeeren" to "🍓",
            "Kirschen" to "🍒", "Pfirsiche" to "🍑", "Melone" to "🍈",
            "Wassermelone" to "🍉", "Ananas" to "🍍", "Mango" to "🥭",
            "Kiwi" to "🥝", "Brokkoli" to "🥦", "Blumenkohl" to "🥦",
            "Spinat" to "🥬", "Champignons" to "🍄", "Pilze" to "🍄",
            "Mais" to "🌽", "Zucchini" to "🥒", "Aubergine" to "🍆",
            "Kürbis" to "🎃", "Ingwer" to "🫕", "Chili" to "🌶️",
            "Spargel" to "🥬", "Lauch" to "🥬", "Sellerie" to "🥬",
            "Rucola" to "🥬", "Radieschen" to "🥬", "Süßkartoffel" to "🍠",
            "Erbsen" to "🫘", "Bohnen" to "🫘", "Petersilie" to "🌿",
            "Basilikum" to "🌿", "Kräuter" to "🌿",
        )
        cat(
            "BAKERY",
            "Brot" to "🍞", "Vollkornbrot" to "🍞", "Toastbrot" to "🍞",
            "Toast" to "🍞", "Brötchen" to "🥐", "Baguette" to "🥖",
            "Croissant" to "🥐", "Croissants" to "🥐", "Brezel" to "🥨",
            "Brezeln" to "🥨", "Knäckebrot" to "🍞", "Zwieback" to "🍞",
            "Kuchen" to "🍰", "Muffins" to "🧁", "Donuts" to "🍩",
        )
        cat(
            "DAIRY",
            "Milch" to "🥛", "Hafermilch" to "🥛", "Butter" to "🧈",
            "Margarine" to "🧈", "Käse" to "🧀", "Gouda" to "🧀",
            "Frischkäse" to "🧀", "Mozzarella" to "🧀", "Parmesan" to "🧀",
            "Feta" to "🧀", "Hüttenkäse" to "🧀", "Joghurt" to "🥣",
            "Naturjoghurt" to "🥣", "Quark" to "🥣", "Skyr" to "🥣",
            "Sahne" to "🥛", "Schmand" to "🥛", "Eier" to "🥚",
            "Pudding" to "🍮",
        )
        cat(
            "MEAT_FISH",
            "Hähnchen" to "🍗", "Hähnchenbrust" to "🍗", "Putenbrust" to "🍗",
            "Hackfleisch" to "🥩", "Rinderhack" to "🥩", "Schnitzel" to "🥩",
            "Steak" to "🥩", "Gulasch" to "🥩", "Wurst" to "🌭",
            "Würstchen" to "🌭", "Bratwurst" to "🌭", "Salami" to "🍖",
            "Schinken" to "🍖", "Speck" to "🥓", "Bacon" to "🥓",
            "Lachs" to "🐟", "Thunfisch" to "🐟", "Fisch" to "🐟",
            "Garnelen" to "🦐", "Frikadellen" to "🍖",
            // "…käse" but meat — pinned exact so the compound-head rule doesn't read them as cheese.
            "Leberkäse" to "🍖", "Fleischkäse" to "🍖",
        )
        cat(
            "FROZEN",
            "Pizza" to "🍕", "Tiefkühlpizza" to "🍕", "Pommes" to "🍟",
            "Eis" to "🍦", "Speiseeis" to "🍦", "Fischstäbchen" to "🐟",
            "Eiswürfel" to "🧊",
        )
        cat(
            "PANTRY",
            "Nudeln" to "🍝", "Spaghetti" to "🍝", "Reis" to "🍚",
            "Mehl" to "🌾", "Zucker" to "🧂", "Salz" to "🧂",
            "Pfeffer" to "🧂", "Gewürze" to "🧂", "Öl" to "🫗",
            "Olivenöl" to "🫒", "Essig" to "🫗", "Tomatensoße" to "🥫",
            "Passierte Tomaten" to "🥫", "Pesto" to "🫙", "Haferflocken" to "🥣",
            "Müsli" to "🥣", "Cornflakes" to "🥣", "Kaffee" to "☕",
            "Tee" to "🍵", "Kakao" to "☕", "Honig" to "🍯",
            "Marmelade" to "🍓", "Nutella" to "🍫", "Erdnussbutter" to "🥜",
            "Ketchup" to "🍅", "Senf" to "🌭", "Mayonnaise" to "🥚",
            "Kichererbsen" to "🫘", "Linsen" to "🫘", "Brühe" to "🥣",
            "Backpulver" to "🧁", "Vanillezucker" to "🧁", "Tofu" to "🥡",
            "Couscous" to "🍚", "Knödel" to "🥔", "Suppe" to "🥣",
            "Konserven" to "🥫",
        )
        cat(
            "SNACKS",
            "Schokolade" to "🍫", "Schokoriegel" to "🍫", "Riegel" to "🍫",
            "Müsliriegel" to "🍫", "Kekse" to "🍪", "Chips" to "🥨",
            "Salzstangen" to "🥨", "Gummibärchen" to "🍬", "Bonbons" to "🍬",
            "Popcorn" to "🍿", "Nüsse" to "🥜", "Studentenfutter" to "🥜",
            "Pralinen" to "🍫", "Waffeln" to "🧇",
        )
        cat(
            "DRINKS",
            "Wasser" to "💧", "Mineralwasser" to "💧", "Sprudel" to "💧",
            "Saft" to "🧃", "Apfelsaft" to "🧃", "Orangensaft" to "🧃",
            "Apfelschorle" to "🧃", "Schorle" to "🧃",
            "Cola" to "🥤", "Limonade" to "🥤", "Eistee" to "🧃",
            "Bier" to "🍺", "Wein" to "🍷", "Rotwein" to "🍷",
            "Weißwein" to "🍷", "Sekt" to "🍾", "Smoothie" to "🥤",
        )
        cat(
            "HOUSEHOLD",
            "Klopapier" to "🧻", "Toilettenpapier" to "🧻", "Küchenrolle" to "🧻",
            "Taschentücher" to "🤧", "Servietten" to "🧻", "Spülmittel" to "🧽",
            "Spülmaschinentabs" to "🧽", "Waschmittel" to "🧴", "Weichspüler" to "🧴",
            "Putzmittel" to "🧽", "Allzweckreiniger" to "🧽", "Schwämme" to "🧽",
            "Müllbeutel" to "🗑️", "Zahnpasta" to "🪥", "Zahnbürste" to "🪥",
            "Shampoo" to "🧴", "Duschgel" to "🧴", "Seife" to "🧼",
            "Handseife" to "🧼", "Deo" to "🧴", "Rasierer" to "🪒",
            "Windeln" to "🧷", "Feuchttücher" to "🧻", "Batterien" to "🔋",
            "Katzenfutter" to "🐱", "Hundefutter" to "🐶",
        )
    }

    /**
     * Normalize a written name to a lookup/stats key: lowercase, strip a leading quantity (+ optional
     * unit), drop punctuation, collapse whitespace. "500 g Mehl" / "2 Paprika" → "mehl" / "paprika".
     */
    fun normalize(raw: String): String {
        var s = raw.trim().lowercase()
        s = LEADING_QTY.replace(s, "")
        s = s.replace(Regex("[.,;:!?()\\[\\]/]+"), " ")
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
    }
}
