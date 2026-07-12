package io.github.bjspi.smsrelayer.domain.usecase

import io.github.bjspi.smsrelayer.domain.Clock
import io.github.bjspi.smsrelayer.domain.model.AppSettings
import io.github.bjspi.smsrelayer.domain.model.LogCategory
import io.github.bjspi.smsrelayer.domain.model.TelegramChatTarget
import io.github.bjspi.smsrelayer.domain.model.TestResult
import io.github.bjspi.smsrelayer.domain.repository.ChatTargetRepository
import io.github.bjspi.smsrelayer.domain.repository.EventLog
import io.github.bjspi.smsrelayer.domain.repository.SettingsRepository
import io.github.bjspi.smsrelayer.domain.telegram.TelegramGateway
import io.github.bjspi.smsrelayer.domain.telegram.TelegramMessageFormatter
import io.github.bjspi.smsrelayer.domain.telegram.TelegramSendOutcome
import kotlinx.coroutines.flow.first

/**
 * Sends a clearly-marked test message to one or all enabled chat targets and
 * records the result on the target (last test time + outcome) so the UI can
 * show per-target health.
 */
class SendTestMessage(
    private val chatTargets: ChatTargetRepository,
    private val settings: SettingsRepository,
    private val telegram: TelegramGateway,
    private val formatter: TelegramMessageFormatter,
    private val eventLog: EventLog,
    private val clock: Clock,
) {

    suspend fun toTarget(targetId: Long): TestResult {
        val target = chatTargets.getById(targetId)
            ?: return TestResult(
                targetId = targetId,
                chatId = "",
                displayName = "",
                success = false,
                httpCode = null,
                errorMessage = "Target not found",
            )
        return send(target, settings.settings.first())
    }

    suspend fun toAllEnabled(): List<TestResult> {
        // One settings snapshot for the whole batch instead of one read per target.
        val appSettings = settings.settings.first()
        return chatTargets.getEnabled().map { send(it, appSettings) }
    }

    private suspend fun send(target: TelegramChatTarget, appSettings: AppSettings): TestResult {
        val now = clock.now()
        val token = appSettings.telegramBotToken?.trim().orEmpty()

        if (token.isEmpty()) {
            eventLog.error(
                LogCategory.Telegram,
                "Test message not sent: bot token missing",
                "targetId=${target.id}",
            )
            return TestResult(target.id, target.chatId, target.displayName, false, null, "Bot token missing")
        }

        val message = formatter.formatTestMessage(
            deviceName = appSettings.deviceName,
            targetDisplayName = target.displayName,
            timestamp = now,
        )

        val outcome = telegram.sendMessage(token, target.chatId, message)
        val result = when (outcome) {
            is TelegramSendOutcome.Delivered ->
                TestResult(target.id, target.chatId, target.displayName, true, outcome.httpCode, null)

            is TelegramSendOutcome.RetryLater ->
                TestResult(target.id, target.chatId, target.displayName, false, outcome.httpCode, outcome.reason)

            is TelegramSendOutcome.Rejected ->
                TestResult(target.id, target.chatId, target.displayName, false, outcome.httpCode, outcome.reason)
        }

        chatTargets.update(target.copy(lastTestAt = now, lastTestSuccessful = result.success))

        if (result.success) {
            eventLog.info(
                LogCategory.Telegram,
                "Test message delivered",
                "targetId=${target.id}, chat=${target.displayName}",
            )
        } else {
            eventLog.warn(
                LogCategory.Telegram,
                "Test message failed",
                "targetId=${target.id}, chat=${target.displayName}, " +
                    "httpCode=${result.httpCode ?: "n/a"}, error=${result.errorMessage}",
            )
        }
        return result
    }
}
