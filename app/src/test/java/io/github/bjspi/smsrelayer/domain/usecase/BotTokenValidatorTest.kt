package io.github.bjspi.smsrelayer.domain.usecase

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BotTokenValidatorTest {

    @Test
    fun `accepts canonical botfather tokens`() {
        assertTrue(BotTokenValidator.isPlausible("123456789:AAHnf3kEXAMPLEtokenEXAMPLEtoken12345"))
        assertTrue(BotTokenValidator.isPlausible(" 42:ABCDEFGHIJKLMNOPQRSTUVWXYZ_- "))
    }

    @Test
    fun `rejects malformed tokens`() {
        assertFalse(BotTokenValidator.isPlausible(""))
        assertFalse(BotTokenValidator.isPlausible("no-colon-at-all"))
        assertFalse(BotTokenValidator.isPlausible("abc:AAHnf3kEXAMPLEtokenEXAMPLE"))
        assertFalse(BotTokenValidator.isPlausible("123456789:short"))
        assertFalse(BotTokenValidator.isPlausible("123:456:AAHnf3kEXAMPLEtokenEXAMPLE"))
    }
}
