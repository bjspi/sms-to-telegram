package io.github.bjspi.smsrelayer.ui.common

import android.content.Context
import androidx.annotation.StringRes

/**
 * One-shot user-facing message emitted by ViewModels (snackbars). Carries a
 * resource id instead of resolved text so ViewModels stay free of Context and
 * the message localizes at display time.
 */
data class UiMessage(
    @StringRes val resId: Int,
    val args: List<Any> = emptyList(),
) {
    fun resolve(context: Context): String =
        if (args.isEmpty()) context.getString(resId) else context.getString(resId, *args.toTypedArray())
}
