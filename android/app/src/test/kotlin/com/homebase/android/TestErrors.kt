package com.homebase.android

import com.homebase.android.data.repository.ApiException
import com.homebase.android.data.repository.AppError

/**
 * The typed [AppError] a failed repository [Result] carries (#558 test helper). Every repository
 * failure is an [ApiException] with a code; the UI resolves the code to text (asserted separately in
 * [ErrorCodeMappingRobolectricTest] against strings.xml). Fails loudly if the failure isn't the
 * expected shape.
 */
internal fun Result<*>.appError(): AppError = (exceptionOrNull() as ApiException).code
