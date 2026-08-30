package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val peerDeviceId: String,
    val peerDisplayName: String,
    val direction: String,
    val text: String,
    val sentAt: Long,
    val messageId: String,
    val clientMessageId: String? = null,
    val status: String = "delivered",
    val isRead: Boolean = false
) {
    val isOutgoing: Boolean
        get() = direction == DIRECTION_OUT

    companion object {
        const val PEER_GLOBAL = "__global__"
        const val DIRECTION_IN = "in"
        const val DIRECTION_OUT = "out"
        const val STATUS_SENDING = "sending"
        const val STATUS_DELIVERED = "delivered"
        const val STATUS_QUEUED = "queued"
        const val STATUS_FAILED = "failed"
    }
}
