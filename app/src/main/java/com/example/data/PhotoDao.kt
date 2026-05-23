package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoDao {
    @Query("SELECT * FROM received_photos ORDER BY timestamp DESC")
    fun getAllPhotos(): Flow<List<ReceivedPhoto>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhoto(photo: ReceivedPhoto): Long

    @Delete
    suspend fun deletePhoto(photo: ReceivedPhoto)

    @Query("DELETE FROM received_photos WHERE id = :id")
    suspend fun deletePhotoById(id: Long)

    @Query("DELETE FROM received_photos")
    suspend fun clearAll()
}
