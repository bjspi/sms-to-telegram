package io.github.bjspi.smsrelayer.domain.util

private val WHITESPACE = Regex("\\s+")

/**
 * Collapses all whitespace runs into single spaces and truncates to [maxChars].
 * Used for log details and UI previews so multi-line SMS bodies and stack
 * traces never blow up list rows or log storage.
 */
fun String?.compactSingleLine(maxChars: Int): String =
    orEmpty().replace(WHITESPACE, " ").trim().take(maxChars)

/** Short, single-line description of a throwable for structured logs. */
fun Throwable.compactMessage(maxChars: Int = 240): String {
    val detail = message?.compactSingleLine(maxChars).orEmpty()
    val type = this::class.java.simpleName
    return if (detail.isEmpty()) type else "$type: $detail".take(maxChars)
}
