package org.dlget.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import org.dlget.R
import org.dlget.App
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit


class YoutubeDLUpdateWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as
                NotificationManager?


    override suspend fun doWork(): Result {

        createNotificationChannel()
        val notificationId = id.hashCode()
        val notification = NotificationCompat.Builder(
            applicationContext,
            channelId
        )
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(applicationContext.getString(R.string.youtubedl_update_noti_title))
            .build()

        val foregroundInfo = ForegroundInfo(
            notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        try {
            setForeground(foregroundInfo)
        } catch (e: Exception) {
            // Foreground service may be refused while the app is backgrounded.
            android.util.Log.w("YoutubeDLUpdateWorker", "could not start foreground service", e)
        }

        try {
            // updateYoutubeDL() asserts YoutubeDL is initialised; the async init in
            // App.onCreate may still be unzipping Python when the user taps Update.
            App.initLatch.await(120, TimeUnit.SECONDS)
            val result = YoutubeDL.getInstance().updateYoutubeDL(applicationContext)
            if (result == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, R.string.already_updated, Toast.LENGTH_SHORT)
                        .show()
                }
            }
            if (result == YoutubeDL.UpdateStatus.DONE) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, R.string.updated_successful, Toast.LENGTH_SHORT)
                        .show()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(applicationContext, R.string.update_failed, Toast.LENGTH_LONG).show()
            }
            return Result.failure()
        }
        return Result.success()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            var notificationChannel =
                notificationManager?.getNotificationChannel(channelId)
            if (notificationChannel == null) {
                val channelName =
                    applicationContext.getString(R.string.youtubedl_update_noti_channel_name)
                notificationChannel = NotificationChannel(
                    channelId,
                    channelName, NotificationManager.IMPORTANCE_LOW
                )
                notificationChannel.description =
                    channelName
                notificationManager?.createNotificationChannel(notificationChannel)
            }
        }
    }

    companion object {
        private const val channelId = "youtubedl_update"
        const val workTag = "youtubedl_update_work"
    }
}

