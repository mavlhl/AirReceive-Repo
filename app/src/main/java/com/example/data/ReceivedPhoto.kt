package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "received_photos")
data class ReceivedPhoto(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val formattedSize: String,
    val timestamp: Long = System.currentTimeMillis(),
    val senderIp: String = "Unknown iOS Device",
    val mimeType: String = "image/jpeg"
)
