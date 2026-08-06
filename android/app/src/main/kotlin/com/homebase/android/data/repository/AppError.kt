package com.homebase.android.data.repository

/**
 * Typed, presentation-free error codes a repository can return (#558).
 *
 * The data layer maps every failure — HTTP `ErrorResponse.code`, transport, parse — to one of these
 * codes ([ApiException.code]); the **UI layer** resolves a code to a localized string
 * (`ui/ErrorText.kt` → `strings.xml`, DE + EN). This keeps user-facing wording (German or otherwise)
 * out of the repositories, so an i18n switch (#204/#399/#6) reaches these texts and the app stays
 * portable for any household.
 *
 * One value per distinct user-facing message. A handful of short, deliberately identical messages are
 * shared across domains (e.g. [INVALID_DATE], [NAME_REQUIRED], [SAVE_FAILED]); if a domain's wording
 * later diverges, split it into its own code then.
 */
enum class AppError {
    // --- Shared: transport / parse / generic ---
    NETWORK,        // offline, DNS, timeout, TLS
    GENERIC,        // parse failure or anything unexpected

    // --- Shared: domain-neutral wording ---
    DATE_CONFLICT,  // 409 on an already-occupied date (absence editors)
    INVALID_DATE,   // time entry / split / absence
    INVALID_COLOR,  // project colour / avatar hue
    NAME_REQUIRED,  // project name / template name empty
    SAVE_FAILED,    // generic "could not be saved" (time entry / calendar feed)

    // --- Auth ---
    LOGIN_FAILED,
    LOGIN_THROTTLED,
    PASSWORD_WRONG,
    PASSWORD_SAVE_FAILED,

    // --- Todos ---
    TODO_INVALID,
    TODO_INVALID_STATUS,
    TODO_INVALID_PRIORITY,
    TODO_INVALID_DUE_DATE,
    TODO_INVALID_RECURRENCE,
    TODO_INVALID_LIST,
    TODO_NOT_FOUND,
    TODO_SAVE_FAILED,

    // --- Time / projects / split ---
    TIME_PROJECT_ARCHIVED,
    TIME_INVALID_RANGE,
    TIME_ENTRY_NOT_FOUND,
    PROJECT_NOT_FOUND,
    PROJECT_SAVE_FAILED,
    SPLIT_FAILED,

    // --- Shopping: templates / categories / rules ---
    TEMPLATE_NOT_FOUND,
    TEMPLATE_SAVE_FAILED,
    CATEGORY_PROTECTED,
    CATEGORY_INVALID,
    CATEGORY_NOT_FOUND,
    CATEGORY_SAVE_FAILED,
    RULE_INVALID,
    RULE_INVALID_CATEGORY,
    RULE_NOT_FOUND,
    RULE_SAVE_FAILED,

    // --- Absence (Kita-Schließtage / eigene Feiertage) ---
    RANGE_TOO_LARGE,
    ABSENCE_NOT_FOUND,
    KITA_SAVE_FAILED,
    HOLIDAY_SAVE_FAILED,

    // --- Notes attachments ---
    ATTACHMENT_TOO_LARGE,
    ATTACHMENT_TYPE,
    ATTACHMENT_UPLOAD_FAILED,

    // --- Recipe URL import ---
    RECIPE_IMPORT_NO_DATA,
    RECIPE_IMPORT_FAILED,

    // --- Config / settings ---
    HOUSEHOLD_NAME_INVALID,
    HOUSEHOLD_NAME_SAVE_FAILED,
    AVATAR_COLOR_SAVE_FAILED,
    DONE_WINDOW_INVALID,
    DONE_WINDOW_SAVE_FAILED,
    DIGEST_TIME_INVALID,
    SETTINGS_SAVE_FAILED,
    CALENDAR_FEED_INVALID,
}
