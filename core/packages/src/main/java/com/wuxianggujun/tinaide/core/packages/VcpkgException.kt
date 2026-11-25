package com.wuxianggujun.tinaide.core.packages

import android.content.Context
import androidx.annotation.StringRes
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr

/** Keeps validation independent of the current UI locale and retains the cause for logs. */
class VcpkgException(
    @StringRes val messageResource: Int,
    cause: Throwable? = null,
    val messageArguments: List<Any> = emptyList(),
) : IllegalArgumentException("vcpkg validation failed (resource=$messageResource)", cause)

fun Throwable.vcpkgUserMessage(context: Context): String =
    if (this is VcpkgException) messageResource.strOr(context, *messageArguments.toTypedArray())
    else Strings.vcpkg_operation_failed.strOr(context)
