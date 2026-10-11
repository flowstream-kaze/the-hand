package org.dlget.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.dlget.database.AppDatabase
import org.dlget.database.Download
import org.dlget.database.DownloadsRepository

class DownloadsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository =
        DownloadsRepository(AppDatabase.getDatabase(application).downloadsDao())
    val allDownloads: Flow<List<Download>> = repository.allDownloads

    fun insert(word: Download) = viewModelScope.launch(Dispatchers.IO) {
        repository.insert(word)
    }

    fun update(word: Download) = viewModelScope.launch(Dispatchers.IO) {
        repository.update(word)
    }

    fun delete(word: Download) = viewModelScope.launch(Dispatchers.IO) {
        repository.delete(word)
    }

    fun deleteByIds(ids: Set<Long>) = viewModelScope.launch(Dispatchers.IO) {
        repository.deleteByIds(ids)
    }

    fun clearAll() = viewModelScope.launch(Dispatchers.IO) {
        repository.clearAll()
    }
}