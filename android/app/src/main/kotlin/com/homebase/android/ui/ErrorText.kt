package com.homebase.android.ui

import android.content.Context
import androidx.annotation.StringRes
import com.homebase.android.R
import com.homebase.android.data.repository.ApiException
import com.homebase.android.data.repository.AppError

/**
 * UI-layer mapping of a data-layer [AppError] code to its localized `strings.xml` resource (#558).
 * The repositories return codes only; this is the single place that turns a code into user-facing
 * text, so a language switch (system locale → `values-en`) reaches every repository error.
 */
@StringRes
fun AppError.stringRes(): Int = when (this) {
    AppError.NETWORK -> R.string.error_network
    AppError.GENERIC -> R.string.error_generic
    AppError.DATE_CONFLICT -> R.string.error_date_conflict
    AppError.INVALID_DATE -> R.string.error_invalid_date
    AppError.INVALID_COLOR -> R.string.error_invalid_color
    AppError.NAME_REQUIRED -> R.string.error_name_required
    AppError.SAVE_FAILED -> R.string.error_save_failed
    AppError.LOGIN_FAILED -> R.string.error_login_failed
    AppError.LOGIN_THROTTLED -> R.string.error_login_throttled
    AppError.PASSWORD_WRONG -> R.string.error_password_wrong
    AppError.PASSWORD_SAVE_FAILED -> R.string.error_password_save_failed
    AppError.TODO_INVALID -> R.string.error_todo_invalid
    AppError.TODO_INVALID_STATUS -> R.string.error_todo_invalid_status
    AppError.TODO_INVALID_PRIORITY -> R.string.error_todo_invalid_priority
    AppError.TODO_INVALID_DUE_DATE -> R.string.error_todo_invalid_due_date
    AppError.TODO_INVALID_RECURRENCE -> R.string.error_todo_invalid_recurrence
    AppError.TODO_INVALID_LIST -> R.string.error_todo_invalid_list
    AppError.TODO_NOT_FOUND -> R.string.error_todo_not_found
    AppError.TODO_SAVE_FAILED -> R.string.error_todo_save_failed
    AppError.TIME_PROJECT_ARCHIVED -> R.string.error_time_project_archived
    AppError.TIME_INVALID_RANGE -> R.string.error_time_invalid_range
    AppError.TIME_ENTRY_NOT_FOUND -> R.string.error_time_entry_not_found
    AppError.PROJECT_NOT_FOUND -> R.string.error_project_not_found
    AppError.PROJECT_SAVE_FAILED -> R.string.error_project_save_failed
    AppError.SPLIT_FAILED -> R.string.error_split_failed
    AppError.TEMPLATE_NOT_FOUND -> R.string.error_template_not_found
    AppError.TEMPLATE_SAVE_FAILED -> R.string.error_template_save_failed
    AppError.CATEGORY_PROTECTED -> R.string.error_category_protected
    AppError.CATEGORY_INVALID -> R.string.error_category_invalid
    AppError.CATEGORY_NOT_FOUND -> R.string.error_category_not_found
    AppError.CATEGORY_SAVE_FAILED -> R.string.error_category_save_failed
    AppError.RULE_INVALID -> R.string.error_rule_invalid
    AppError.RULE_INVALID_CATEGORY -> R.string.error_rule_invalid_category
    AppError.RULE_NOT_FOUND -> R.string.error_rule_not_found
    AppError.RULE_SAVE_FAILED -> R.string.error_rule_save_failed
    AppError.RANGE_TOO_LARGE -> R.string.error_range_too_large
    AppError.ABSENCE_NOT_FOUND -> R.string.error_absence_not_found
    AppError.KITA_SAVE_FAILED -> R.string.error_kita_save_failed
    AppError.HOLIDAY_SAVE_FAILED -> R.string.error_holiday_save_failed
    AppError.ATTACHMENT_TOO_LARGE -> R.string.error_attachment_too_large
    AppError.ATTACHMENT_TYPE -> R.string.error_attachment_type
    AppError.ATTACHMENT_UPLOAD_FAILED -> R.string.error_attachment_upload_failed
    AppError.RECIPE_IMPORT_NO_DATA -> R.string.error_recipe_import_no_data
    AppError.RECIPE_IMPORT_FAILED -> R.string.error_recipe_import_failed
    AppError.HOUSEHOLD_NAME_INVALID -> R.string.error_household_name_invalid
    AppError.HOUSEHOLD_NAME_SAVE_FAILED -> R.string.error_household_name_save_failed
    AppError.AVATAR_COLOR_SAVE_FAILED -> R.string.error_avatar_color_save_failed
    AppError.DONE_WINDOW_INVALID -> R.string.error_done_window_invalid
    AppError.DONE_WINDOW_SAVE_FAILED -> R.string.error_done_window_save_failed
    AppError.DIGEST_TIME_INVALID -> R.string.error_digest_time_invalid
    AppError.SETTINGS_SAVE_FAILED -> R.string.error_settings_save_failed
    AppError.CALENDAR_FEED_INVALID -> R.string.error_calendar_feed_invalid
}

/**
 * Resolve any repository failure to a localized user-facing message. An [ApiException] carries a
 * typed [AppError] → `strings.xml`; anything else (a raw HttpException that had no mapper, or a
 * non-repo throwable) falls back to its own message, then the generic string. This is the resolver
 * the ViewModels are given so they can keep their `error: String?` state while the data layer stays
 * text-free (#558).
 */
fun Context.errorText(e: Throwable): String = when (e) {
    is ApiException -> getString(e.code.stringRes())
    else -> e.message ?: getString(R.string.error_generic)
}
