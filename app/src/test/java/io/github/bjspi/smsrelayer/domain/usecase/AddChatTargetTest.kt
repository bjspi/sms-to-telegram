package io.github.bjspi.smsrelayer.domain.usecase

import io.github.bjspi.smsrelayer.testing.FakeChatTargetRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class AddChatTargetTest {

    private val repository = FakeChatTargetRepository()
    private val addChatTarget = AddChatTarget(repository)

    @Test
    fun `adds a trimmed, enabled target`() = runTest {
        val outcome = addChatTarget("  Team Chat  ", " -100123 ")

        val added = assertIs<AddChatTarget.Outcome.Added>(outcome)
        assertEquals("Team Chat", added.target.displayName)
        assertEquals("-100123", added.target.chatId)
        assertTrue(added.target.enabled)
    }

    @Test
    fun `rejects blank fields`() = runTest {
        assertIs<AddChatTarget.Outcome.MissingFields>(addChatTarget("", "123"))
        assertIs<AddChatTarget.Outcome.MissingFields>(addChatTarget("Name", "   "))
    }

    @Test
    fun `rejects duplicate chat ids`() = runTest {
        addChatTarget("First", "42")

        assertIs<AddChatTarget.Outcome.DuplicateChatId>(addChatTarget("Second", " 42 "))
        assertEquals(1, repository.getEnabled().size)
    }
}
