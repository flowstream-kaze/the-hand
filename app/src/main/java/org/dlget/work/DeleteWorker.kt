package org.dlget.work

import android.content.Context
import android.widget.Toast
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dlget.R
import org.dlget.database.AppDatabase
import org.dlget.database.DownloadsRepository

class DeleteWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    /**
     * Removes the entry from the list only - the downloaded file stays where it is.
     * A download still in flight is left running on purpose: it finishes into the
     * download folder as usual, it just stops being tracked here.
     */
    override suspend fun doWork(): Result {

        val fileId = inputData.getLong(fileIdKey, 0)

        val downloadsDao = AppDatabase.getDatabase(applicationContext).downloadsDao()
        val repository = DownloadsRepository(downloadsDao)
        val download = downloadsDao.getById(fileId) ?: return Result.success()

        repository.delete(download)

        withContext(Dispatchers.Main) {
            Toast.makeText(
                applicationContext,
                applicationContext.getString(R.string.removed_entry, download.name),
                Toast.LENGTH_SHORT
            ).show()
        }

        return Result.success()
    }

    companion object {
        const val fileIdKey = "id"
    }
}
