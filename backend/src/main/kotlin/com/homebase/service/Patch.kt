package com.homebase.service

/**
 * Typed carrier for the tri-state PATCH convention (#265/#556). A request field on the wire is one of:
 * - **absent** (`null`, because `encodeDefaults = false` drops unsent fields) → [Keep] the stored value,
 * - **blank** (`""`) → [Clear] the value to null,
 * - **present** → [Set] the new value.
 *
 * Replaces the `if (req.x != null) req.x.ifBlank { null } else existing[x]` that each PUT handler
 * rebuilt by hand per field — a convention previously carried only by comments, not by types. Build a
 * patch from a nullable request String with [asPatch]; collapse a merge with [resolve].
 *
 * `resolve` is a top-level extension rather than a member because a member `resolve(current: T?)` would
 * put the covariant `out T` in an `in` (parameter) position, which the compiler rejects.
 */
sealed interface Patch<out T> {
    /** Field absent from the request → keep whatever is stored. */
    data object Keep : Patch<Nothing>

    /** Field sent blank → clear the stored value to null. */
    data object Clear : Patch<Nothing>

    /** Field sent with a value → set it. */
    data class Set<out T>(val value: T) : Patch<T>
}

/** Resolve a patch against the [current] stored value: Keep → current, Clear → null, Set → the value. */
fun <T> Patch<T>.resolve(current: T?): T? = when (this) {
    Patch.Keep -> current
    Patch.Clear -> null
    is Patch.Set -> value
}

/** The #265 String convention as a [Patch]: `null` → Keep, blank → Clear, else Set(trimmed-as-is). */
fun String?.asPatch(): Patch<String> = when {
    this == null -> Patch.Keep
    isBlank() -> Patch.Clear
    else -> Patch.Set(this)
}
