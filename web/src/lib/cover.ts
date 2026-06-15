// HB-05 — generated recipe cover for recipes without a photo. A deterministic warm hue
// derived from the title plus a per-category glyph + label, so the recipe grid reads calm
// and individual instead of repeating a grey "Foto folgt" placeholder. Pure helpers, shared
// by the card today (and reusable by the detail/meal-plan views later).
import type { RecipeCategory } from '../types'

// Deterministic warm hue (~20–94: peach → amber → gold) from the recipe title. Title-derived
// per the ticket, so each recipe gets a stable, individual cover; a rename re-rolls the hue.
export function coverHue(title: string): number {
  let h = 0
  for (let i = 0; i < title.length; i++) h = (h * 31 + title.charCodeAt(i)) >>> 0
  return 20 + (h % 75)
}

// Category → cover glyph (Icon name). Warm, food-themed line icons defined in ui/Icon.tsx.
export const CATEGORY_ICON: Record<RecipeCategory, string> = {
  BREAKFAST: 'coffee',
  DINNER: 'utensils',
  SNACK: 'cookie',
  DESSERT: 'cake',
  DRINK: 'glass',
}
