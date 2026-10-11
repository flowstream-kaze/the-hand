package org.dlget

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch

class App : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        AppCompatDelegate.setDefaultNightMode(
            preferences.getString(
                getString(R.string.theme_key),
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM.toString()
            )!!.toInt()
        )
        context = this.applicationContext
        appScope.launch {
            // A one-off init failure would leave YoutubeDL uninitialised for the whole
            // process lifetime, so every later fetch/download fails. Retry a few times
            // before giving up.
            var initialised = false
            for (attempt in 1..INIT_ATTEMPTS) {
                try {
                    withContext(Dispatchers.IO) {
                        YoutubeDL.getInstance().init(this@App)
                        FFmpeg.getInstance().init(this@App)
                    }
                    initialised = true
                    break
                } catch (e: Exception) {
                    Log.e("App", "YoutubeDL/FFmpeg init failed (attempt $attempt)", e)
                    if (attempt < INIT_ATTEMPTS) delay(INIT_RETRY_DELAY_MS)
                }
            }
            if (!initialised) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, R.string.init_failed, Toast.LENGTH_LONG)
                        .show()
                }
            }
            initLatch.countDown()
        }
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        lateinit var context: Context

        private const val INIT_ATTEMPTS = 3
        private const val INIT_RETRY_DELAY_MS = 2000L

        /** Released once YoutubeDL/FFmpeg init has finished (success or failure). */
        val initLatch = CountDownLatch(1)
    }
}