package org.dlget.database

import kotlinx.coroutines.flow.Flow

class DownloadsRepository(private val downloadsDao: DownloadsDao) {

    val allDownloads: Flow<List<Download>> = downloadsDao.getAllDownloads()

    suspend fun insert(download: Download) {
        downloadsDao.insert(download)
    }

    suspend fun update(download: Download) {
        downloadsDao.update(download)
    }

    suspend fun delete(download: Download) {
        downloadsDao.delete(download)
    }

    suspend fun deleteByIds(ids: Set<Long>) {
        downloadsDao.deleteByIds(ids)
    }

    suspend fun clearAll() {
        downloadsDao.clearAll()
    }

}