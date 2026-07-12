package io.github.bjspi.smsrelayer.domain.usecase

/**
 * Cheap offline plausibility check for Telegram bot tokens
 * (`<numeric bot id>:<secret>`), used to reject obvious typos before making a
 * network call. The authoritative check is always `getMe`.
 */
object BotTokenValidator {

    private val TOKEN_PATTERN = Regex("""\d+:[A-Za-z0-9_-]{20,}""")

    fun isPlausible(token: String): Boolean = TOKEN_PATTERN.matches(token.trim())
}
