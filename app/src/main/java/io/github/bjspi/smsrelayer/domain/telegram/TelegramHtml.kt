package io.github.bjspi.smsrelayer.domain.telegram

/**
 * Escapes user-controlled text for Telegram's HTML parse mode. Telegram only
 * requires `&`, `<` and `>` to be escaped in text content; `&` must be replaced
 * first so already-escaped entities are not double-mangled.
 */
fun escapeTelegramHtml(input: String): String =
    input
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
