package org.dlget.vm

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dlget.App
import org.dlget.model.VidInfoItem
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import java.util.concurrent.TimeUnit

class VidInfoViewModel : ViewModel() {

    private val _vidFormats = MutableStateFlow<VideoInfo?>(null)
    val vidFormats: StateFlow<VideoInfo?> = _vidFormats.asStateFlow()

    private val _loadState = MutableStateFlow<LoadState>(LoadState.Initial)
    val loadState: StateFlow<LoadState> = _loadState.asStateFlow()

    var selectedItem: VidInfoItem? = null

    // SearchView can deliver the same submit twice (IME action + key path);
    // ignore a duplicate fetch of the same URL within a short window.
    private var lastFetch: Pair<String, Long>? = null

    // Only the most recent search may publish results; a slow earlier fetch must not
    // overwrite a newer one.
    private var fetchJob: Job? = null

    fun fetchInfo(url: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        lastFetch?.let { if (it.first == url && now - it.second < 2000) return }
        lastFetch = url to now
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            _loadState.value = LoadState.Loading
            _vidFormats.value = null
            try {
                val vidInfo = withContext(Dispatchers.IO) {
                    App.initLatch.await(120, TimeUnit.SECONDS)
                    // Share links often carry &list=...; without this yt-dlp dumps the
                    // whole playlist and downloads every entry.
                    val request = YoutubeDLRequest(url)
                    request.addOption("--no-playlist")
                    YoutubeDL.getInstance().getInfo(request)
                }
                _vidFormats.value = vidInfo
                _loadState.value = LoadState.Loaded
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("VidInfoViewModel", "fetchInfo failed for $url", e)
                _loadState.value = LoadState.Error(e.message ?: "Failed to fetch video info")
            }
        }
    }
}

sealed class LoadState {
    object Initial : LoadState()
    object Loading : LoadState()
    object Loaded : LoadState()
    data class Error(val message: String) : LoadState()
}