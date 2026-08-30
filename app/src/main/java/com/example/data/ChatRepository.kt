package com.example.data

import kotlinx.coroutines.flow.Flow

class ChatRepository(private val chatDao: ChatDao) {
    fun observeMessagesForPeer(peerDeviceId: String): Flow<List<ChatMessage>> =
        chatDao.observeMessagesForPeer(peerDeviceId)

    fun observeUnreadCount(): Flow<Int> = chatDao.observeUnreadCount()

    suspend fun findByMessageId(messageId: String): ChatMessage? =
        chatDao.findByMessageId(messageId)

    suspend fun findByClientMessageId(clientMessageId: String): ChatMessage? =
        chatDao.findByClientMessageId(clientMessageId)

    suspend fun insert(message: ChatMessage): Long = chatDao.insert(message)

    suspend fun update(message: ChatMessage) = chatDao.update(message)

    suspend fun markPeerRead(peerDeviceId: String) = chatDao.markPeerRead(peerDeviceId)
}
