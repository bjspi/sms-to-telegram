package io.github.bjspi.smsrelayer.data.telegram.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Standard Bot API envelope: `{ ok, result }` or `{ ok, error_code, description }`. */
@Serializable
data class TelegramResponse<T>(
    val ok: Boolean = false,
    val result: T? = null,
    @SerialName("error_code") val errorCode: Int? = null,
    val description: String? = null,
    val parameters: ResponseParameters? = null,
)

@Serializable
data class ResponseParameters(
    @SerialName("retry_after") val retryAfter: Int? = null,
)

@Serializable
data class UserDto(
    val id: Long,
    @SerialName("is_bot") val isBot: Boolean = false,
    @SerialName("first_name") val firstName: String = "",
    val username: String? = null,
)

@Serializable
data class ChatDto(
    val id: Long,
    val type: String? = null,
    val title: String? = null,
    val username: String? = null,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
)

/** Only the chat reference is needed from messages — everything else is ignored. */
@Serializable
data class MessageDto(
    val chat: ChatDto? = null,
)

@Serializable
data class ChatMemberUpdatedDto(
    val chat: ChatDto? = null,
)

@Serializable
data class UpdateDto(
    val message: MessageDto? = null,
    @SerialName("edited_message") val editedMessage: MessageDto? = null,
    @SerialName("channel_post") val channelPost: MessageDto? = null,
    @SerialName("edited_channel_post") val editedChannelPost: MessageDto? = null,
    @SerialName("my_chat_member") val myChatMember: ChatMemberUpdatedDto? = null,
) {
    /** All chats referenced by this update, in declaration order. */
    fun chats(): List<ChatDto> = listOfNotNull(
        message?.chat,
        editedMessage?.chat,
        channelPost?.chat,
        editedChannelPost?.chat,
        myChatMember?.chat,
    )
}

@Serializable
data class SendMessageRequest(
    @SerialName("chat_id") val chatId: String,
    val text: String,
    @SerialName("parse_mode") val parseMode: String = "HTML",
    @SerialName("link_preview_options") val linkPreviewOptions: LinkPreviewOptions = LinkPreviewOptions(),
)

@Serializable
data class LinkPreviewOptions(
    @SerialName("is_disabled") val isDisabled: Boolean = true,
)
