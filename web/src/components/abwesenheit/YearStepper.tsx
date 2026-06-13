// Shared year stepper for the absence calendar and its settings sub-page (#133).
// Both call sites previously duplicated the markup plus the clamp/bounds helpers;
// extracting them here keeps the bounds in one place and — the point of #133 —
// gives the stepper accessible semantics so a screen-reader announces the NEW
// year after stepping: the value is an aria-live region AND the whole control is
// a role="group" labelled with the current year. Markup classes are unchanged
// (abw-yearnav / abw-yearnav__y, styled in abw.css) so existing CSS/e2e match.
import { t } from '../../i18n'
import { Icon } from '../../ui/Icon'

// Keep the visible year inside the same window the backend accepts for settings,
// so paging the year nav can never produce a year the settings PUT would reject.
export const YEAR_MIN = 2000
export const YEAR_MAX = 2200
export const clampYear = (y: number): number => Math.min(YEAR_MAX, Math.max(YEAR_MIN, y))

export function YearStepper({ year, onChange }: { year: number; onChange: (y: number) => void }) {
  return (
    <div className="abw-yearnav" role="group" aria-label={t.abwesenheit.yearNav.replace('{year}', String(year))}>
      <button className="hb-iconbtn" onClick={() => onChange(clampYear(year - 1))} aria-label={t.abwesenheit.prevYear}>
        <Icon name="chevronLeft" size={17} stroke={2.2} />
      </button>
      <span className="abw-yearnav__y hb-mono" aria-live="polite">{year}</span>
      <button className="hb-iconbtn" onClick={() => onChange(clampYear(year + 1))} aria-label={t.abwesenheit.nextYear}>
        <Icon name="chevronRight" size={17} stroke={2.2} />
      </button>
    </div>
  )
}
