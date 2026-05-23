package com.example.data

import kotlinx.coroutines.flow.Flow

class PhotoRepository(private val photoDao: PhotoDao) {
    val allPhotos: Flow<List<ReceivedPhoto>> = photoDao.getAllPhotos()

    suspend fun insertPhoto(photo: ReceivedPhoto): Long {
        return photoDao.insertPhoto(photo)
    }

    suspend fun deletePhoto(photo: ReceivedPhoto) {
        photoDao.deletePhoto(photo)
    }

    suspend fun deletePhotoById(id: Long) {
        photoDao.deletePhotoById(id)
    }

    suspend fun clearAll() {
        photoDao.clearAll()
    }
}
