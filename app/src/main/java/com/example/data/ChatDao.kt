package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_messages WHERE peerDeviceId = :peerDeviceId ORDER BY sentAt ASC")
    fun observeMessagesForPeer(peerDeviceId: String): Flow<List<ChatMessage>>

    @Query("SELECT COUNT(*) FROM chat_messages WHERE direction = :direction AND isRead = 0")
    fun observeUnreadCount(direction: String = ChatMessage.DIRECTION_IN): Flow<Int>

    @Query("SELECT * FROM chat_messages WHERE messageId = :messageId LIMIT 1")
    suspend fun findByMessageId(messageId: String): ChatMessage?

    @Query("SELECT * FROM chat_messages WHERE clientMessageId = :clientMessageId LIMIT 1")
    suspend fun findByClientMessageId(clientMessageId: String): ChatMessage?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: ChatMessage): Long

    @Update
    suspend fun update(message: ChatMessage)

    @Query("UPDATE chat_messages SET isRead = 1 WHERE peerDeviceId = :peerDeviceId AND direction = :direction")
    suspend fun markPeerRead(peerDeviceId: String, direction: String = ChatMessage.DIRECTION_IN)
}
