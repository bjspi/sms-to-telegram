package io.github.bjspi.smsrelayer.domain.usecase

import io.github.bjspi.smsrelayer.domain.model.TelegramChatTarget
import io.github.bjspi.smsrelayer.domain.repository.ChatTargetRepository

/**
 * Single owner of the "add a chat target" business rules (trimming, required
 * fields, duplicate chat IDs), shared by onboarding and settings so the two
 * flows can never drift apart.
 */
class AddChatTarget(private val chatTargets: ChatTargetRepository) {

    sealed interface Outcome {
        data class Added(val target: TelegramChatTarget) : Outcome
        data object MissingFields : Outcome
        data object DuplicateChatId : Outcome
    }

    suspend operator fun invoke(displayName: String, chatId: String): Outcome {
        val name = displayName.trim()
        val id = chatId.trim()
        return when {
            name.isEmpty() || id.isEmpty() -> Outcome.MissingFields
            chatTargets.getByChatId(id) != null -> Outcome.DuplicateChatId
            else -> Outcome.Added(chatTargets.add(name, id, enabled = true))
        }
    }
}
