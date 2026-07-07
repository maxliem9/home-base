package com.homebase.android.ui.shopping

import com.homebase.android.data.model.ShoppingItemDto

/** One pickable item icon: the svg basename [key] (stored as the override) and its bundled asset URI. */
data class IconChoice(val key: String, val assetUri: String)

/**
 * Designed SVG icon set (#443) — the Android mirror of the web `shoppingCategories.tsx` resolution.
 * The 173 SVGs are bundled under `assets/shopping-icons/{items,categories}/`; this maps an item to its
 * asset path. Rendered via Coil's SVG decoder (see `ShoppingItemIcon`), with the emoji as a fallback.
 *
 * Item icons are named in English; [ITEM_ICON_KEY] maps a normalized German item name
 * ([slugifyIconKey]) to the English file. Keep in sync with the web `shoppingIconMap.ts`.
 */
object ShoppingIcons {

    private const val BASE = "file:///android_asset/shopping-icons"

    /** Category key → English category-icon basename (MEAT_FISH keeps its hyphen). */
    private val CATEGORY_ICON_KEY: Map<String, String> = mapOf(
        "PRODUCE" to "produce",
        "BAKERY" to "bakery",
        "DAIRY" to "dairy",
        "MEAT_FISH" to "meat-fish",
        "FROZEN" to "frozen",
        "PANTRY" to "pantry",
        "SNACKS" to "snacks",
        "DRINKS" to "drinks",
        "HOUSEHOLD" to "household",
        "OTHER" to "other",
    )

    /**
     * Normalized German item name → English item-icon basename. Generated from / kept in sync with the
     * web `shoppingIconMap.ts` (210 names → 162 distinct icons; synonyms share an icon).
     */
    private val ITEM_ICON_KEY: Map<String, String> = mapOf(
    "aepfel" to "apples",
    "allzweckreiniger" to "cleaner",
    "ananas" to "pineapple",
    "apfelsaft" to "juice",
    "aubergine" to "eggplant",
    "avocado" to "avocado",
    "avocados" to "avocado",
    "backpulver" to "baking-powder",
    "bacon" to "bacon",
    "baguette" to "baguette",
    "bananen" to "bananas",
    "basilikum" to "basil",
    "batterien" to "batteries",
    "bier" to "beer",
    "birnen" to "pears",
    "blaubeeren" to "blueberries",
    "blumenkohl" to "cauliflower",
    "bohnen" to "beans",
    "bonbons" to "gummy-bears",
    "bratwurst" to "sausage",
    "brezel" to "pretzel",
    "brezeln" to "pretzel",
    "broetchen" to "roll",
    "brokkoli" to "broccoli",
    "brot" to "bread",
    "bruehe" to "broth",
    "butter" to "butter",
    "champignons" to "mushrooms",
    "chili" to "chili",
    "chips" to "chips",
    "cola" to "cola",
    "cornflakes" to "cereal",
    "couscous" to "couscous",
    "croissant" to "croissant",
    "croissants" to "croissant",
    "deo" to "deodorant",
    "donuts" to "donut",
    "duschgel" to "shower-gel",
    "eier" to "eggs",
    "eis" to "ice-cream",
    "eistee" to "iced-tea",
    "eiswuerfel" to "ice-cubes",
    "erbsen" to "peas",
    "erdbeeren" to "strawberries",
    "erdnussbutter" to "peanut-butter",
    "essig" to "vinegar",
    "feldsalat" to "lettuce",
    "feta" to "cheese",
    "feuchttuecher" to "wet-wipes",
    "fisch" to "fish",
    "fischstaebchen" to "fish-sticks",
    "frikadellen" to "meatballs",
    "frischkaese" to "cheese",
    "garnelen" to "shrimp",
    "gewuerze" to "spices",
    "gouda" to "cheese",
    "gulasch" to "goulash",
    "gummibaerchen" to "gummy-bears",
    "gurke" to "cucumber",
    "gurken" to "cucumber",
    "hackfleisch" to "ground-meat",
    "haehnchen" to "chicken",
    "haehnchenbrust" to "chicken",
    "haferflocken" to "oats",
    "hafermilch" to "oat-milk",
    "handseife" to "soap",
    "heidelbeeren" to "blueberries",
    "himbeeren" to "raspberries",
    "honig" to "honey",
    "huettenkaese" to "cheese",
    "hundefutter" to "dog-food",
    "ingwer" to "ginger",
    "joghurt" to "yogurt",
    "kaese" to "cheese",
    "kaffee" to "coffee",
    "kakao" to "cocoa",
    "karotten" to "carrots",
    "kartoffeln" to "potatoes",
    "katzenfutter" to "cat-food",
    "kekse" to "cookies",
    "ketchup" to "ketchup",
    "kichererbsen" to "chickpeas",
    "kirschen" to "cherries",
    "kiwi" to "kiwi",
    "klopapier" to "toilet-paper",
    "knaeckebrot" to "crispbread",
    "knoblauch" to "garlic",
    "knoedel" to "dumpling",
    "konserven" to "canned-food",
    "kopfsalat" to "lettuce",
    "kraeuter" to "herbs",
    "kuchen" to "cake",
    "kuechenrolle" to "paper-towels",
    "kuerbis" to "pumpkin",
    "lachs" to "salmon",
    "lauch" to "leek",
    "leberkaese" to "meatloaf",
    "limetten" to "limes",
    "limonade" to "lemonade",
    "linsen" to "lentils",
    "mais" to "corn",
    "mandarinen" to "mandarins",
    "mango" to "mango",
    "margarine" to "butter",
    "marmelade" to "jam",
    "mayonnaise" to "mayonnaise",
    "mehl" to "flour",
    "melone" to "melon",
    "milch" to "milk",
    "mineralwasser" to "water",
    "moehren" to "carrots",
    "mozzarella" to "cheese",
    "muellbeutel" to "trash-bags",
    "muesli" to "cereal",
    "muesliriegel" to "candy-bar",
    "muffins" to "muffin",
    "naturjoghurt" to "yogurt",
    "nudeln" to "pasta",
    "nuesse" to "nuts",
    "nutella" to "chocolate-spread",
    "oel" to "oil",
    "olivenoel" to "olive-oil",
    "orangen" to "oranges",
    "orangensaft" to "juice",
    "paprika" to "bell-pepper",
    "parmesan" to "cheese",
    "passierte-tomaten" to "tomato-sauce",
    "pesto" to "pesto",
    "petersilie" to "parsley",
    "pfeffer" to "pepper",
    "pfirsiche" to "peaches",
    "pilze" to "mushrooms",
    "pizza" to "pizza",
    "pommes" to "fries",
    "popcorn" to "popcorn",
    "pralinen" to "chocolate",
    "pudding" to "pudding",
    "putenbrust" to "chicken",
    "putzmittel" to "cleaner",
    "quark" to "quark",
    "radieschen" to "radish",
    "rasierer" to "razor",
    "reis" to "rice",
    "riegel" to "candy-bar",
    "rinderhack" to "ground-meat",
    "rotwein" to "wine",
    "rucola" to "lettuce",
    "saft" to "juice",
    "sahne" to "cream",
    "salami" to "salami",
    "salat" to "lettuce",
    "salz" to "salt",
    "salzstangen" to "pretzel-sticks",
    "schinken" to "ham",
    "schmand" to "cream",
    "schnitzel" to "schnitzel",
    "schokolade" to "chocolate",
    "schokoriegel" to "candy-bar",
    "schwaemme" to "sponge",
    "seife" to "soap",
    "sekt" to "sparkling-wine",
    "sellerie" to "celery",
    "senf" to "mustard",
    "servietten" to "napkins",
    "shampoo" to "shampoo",
    "skyr" to "quark",
    "smoothie" to "smoothie",
    "spaghetti" to "pasta",
    "spargel" to "asparagus",
    "speck" to "bacon",
    "speiseeis" to "ice-cream",
    "spinat" to "spinach",
    "sprudel" to "water",
    "spuelmaschinentabs" to "dishwasher-tabs",
    "spuelmittel" to "dish-soap",
    "steak" to "steak",
    "studentenfutter" to "nuts",
    "suesskartoffel" to "sweet-potato",
    "suppe" to "soup",
    "taschentuecher" to "tissues",
    "tee" to "tea",
    "thunfisch" to "tuna",
    "tiefkuehlpizza" to "pizza",
    "toast" to "toast",
    "toastbrot" to "toast",
    "tofu" to "tofu",
    "toilettenpapier" to "toilet-paper",
    "tomaten" to "tomatoes",
    "tomatensosse" to "tomato-sauce",
    "trauben" to "grapes",
    "vanillezucker" to "vanilla-sugar",
    "vollkornbrot" to "bread",
    "waffeln" to "waffles",
    "waschmittel" to "detergent",
    "wasser" to "water",
    "wassermelone" to "watermelon",
    "weichspueler" to "fabric-softener",
    "wein" to "wine",
    "weintrauben" to "grapes",
    "weisswein" to "wine",
    "windeln" to "diapers",
    "wuerstchen" to "sausage",
    "wurst" to "sausage",
    "zahnbuerste" to "toothbrush",
    "zahnpasta" to "toothpaste",
    "zitronen" to "lemons",
    "zucchini" to "zucchini",
    "zucker" to "sugar",
    "zwieback" to "rusk",
    "zwiebeln" to "onions",
    )

    private val LEADING_QTY = Regex(
        "^\\s*\\d+([.,]\\d+)?\\s*" +
            "(g|kg|mg|ml|l|el|tl|stk|stück|st|x|prise|prisen|bund|dose|dosen|pkg|pck|pack|packung|" +
            "tasse|cup|msp|glas|gläser|becher|flasche|flaschen)?\\.?\\s+",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Normalize a written name to the [ITEM_ICON_KEY] lookup key: lowercase, strip a leading
     * "<qty> <unit>", transliterate umlauts to ASCII, collapse the rest to a-z0-9 joined by '-'.
     * Mirror of the web `slugifyIconKey`. "500 g Möhren" → "moehren", "Leberkäse" → "leberkaese".
     */
    fun slugifyIconKey(raw: String): String {
        var s = raw.trim().lowercase()
        s = LEADING_QTY.replace(s, "")
        s = s.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
        s = s.replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return s
    }

    /** Set of available item-icon basenames (used to validate an explicit override; #442 parity later). */
    private val itemKeys: Set<String> = ITEM_ICON_KEY.values.toHashSet() + "misc"

    /**
     * The asset URI for an item's icon: explicit override (icon is a basename) → item-name match →
     * category icon → the neutral `misc` icon. Always non-null (misc is the floor).
     */
    private fun resolve(name: String, category: String?, iconOverride: String?): String {
        iconOverride?.let { if (it in itemKeys) return "$BASE/items/$it.svg" }
        ITEM_ICON_KEY[slugifyIconKey(name)]?.let { return "$BASE/items/$it.svg" }
        category?.let { cat -> CATEGORY_ICON_KEY[cat]?.let { return "$BASE/categories/$it.svg" } }
        return "$BASE/items/misc.svg"
    }

    fun assetForItem(item: ShoppingItemDto): String = resolve(item.name, item.category, item.icon)

    /** Resolve by a written name + category (for autocomplete suggestions, which carry no override). */
    fun assetForName(name: String, category: String?): String = resolve(name, category, null)

    // ---- Icon picker support (#508, web parity #442) ----------------------------------------------

    /**
     * All pickable item icons (svg basename + asset URI), alphabetically. Mirror of the web
     * `ITEM_ICON_CHOICES` — every designed item icon (the [ITEM_ICON_KEY] values, deduped; synonyms
     * share one icon) except the neutral `misc` fallback. The override stores [IconChoice.key].
     */
    val itemIconChoices: List<IconChoice> =
        ITEM_ICON_KEY.values.toSortedSet().map { IconChoice(it, "$BASE/items/$it.svg") }

    /**
     * English icon key → the German normalized names that resolve to it, so the picker is searchable
     * in German ("möhren" finds the carrots icon) even though the files are named in English. Mirror
     * of the web `germanSlugsByIconKey`.
     */
    private val germanSlugsByIconKey: Map<String, List<String>> =
        ITEM_ICON_KEY.entries.groupBy({ it.value }, { it.key })

    /**
     * Is [key] a real svg-basename override (a removable pick), as opposed to a legacy emoji stored in
     * `item.icon` or no override at all? Excludes the neutral `misc` (never offered by the picker).
     * Mirror of the web `ITEM_ICON_KEYS` membership test (#508/#511).
     */
    fun isItemIconKey(key: String?): Boolean = key != null && key != "misc" && key in itemKeys

    /** Does an icon choice match a search query (English key substring or any German name)? */
    fun iconMatchesQuery(key: String, query: String): Boolean {
        val raw = query.trim().lowercase()
        if (raw.isEmpty()) return true
        if (key.contains(raw)) return true
        val slug = slugifyIconKey(query)
        return slug.isNotEmpty() && germanSlugsByIconKey[key]?.any { it.contains(slug) } == true
    }

    /** The asset URI for a category header/menu icon, or null (caller shows the emoji). */
    fun assetForCategory(key: String?): String? =
        key?.let { CATEGORY_ICON_KEY[it] }?.let { "$BASE/categories/$it.svg" }
}
