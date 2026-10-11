package org.dlget.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.dlget.R
import java.util.*
import java.util.regex.Matcher
import java.util.regex.Pattern


class CommandWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as
                NotificationManager?


    override suspend fun doWork(): Result {

        val command = inputData.getString(commandKey)!!

        createNotificationChannel()
        val notificationId = id.hashCode()
        val resultId = notificationId + DownloadWorker.RESULT_ID_OFFSET
        val notification = NotificationCompat.Builder(
            applicationContext,
            channelId
        )
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(applicationContext.getString(R.string.command_noti_title))
            .setContentText(applicationContext.getString(R.string.command_noti_text))
            .build()

        val foregroundInfo = ForegroundInfo(
            notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        try {
            setForeground(foregroundInfo)
        } catch (e: Exception) {
            // Foreground service may be refused while the app is backgrounded;
            // the command can still run without it.
            android.util.Log.w("CommandWorker", "could not start foreground service", e)
        }

        // this is not the recommended way to add options/flags/url and might break in future
        // use the constructor for url, addOption(key) for flags, addOption(key, value) for options
        val request = YoutubeDLRequest(Collections.emptyList())
        val m: Matcher = Pattern.compile(commandRegex).matcher(command)
        while (m.find()) {
            if (m.group(1) != null) {
                request.addOption(m.group(1))
            } else {
                request.addOption(m.group(2))
            }
        }

        try {
            YoutubeDL.getInstance()
                .execute(request) { progress, _, line ->
                    showProgress(id.hashCode(), progress.toInt(), line)
                }
        } catch (e: Exception) {
            notificationManager?.notify(
                resultId,
                NotificationCompat.Builder(applicationContext, channelId)
                    .setSmallIcon(R.drawable.ic_stat_download)
                    .setContentTitle(applicationContext.getString(R.string.command_noti_title))
                    .setContentText(e.message ?: applicationContext.getString(R.string.command_failed))
                    .setOngoing(false)
                    .build()
            )
            return Result.failure()
        }

        return Result.success()
    }

    private fun showProgress(id: Int, progress: Int, line: String) {
        val now = SystemClock.elapsedRealtime()
        if (progress == lastNotifiedPercent && now - lastNotifyAt < NOTIFY_THROTTLE_MS) return
        lastNotifiedPercent = progress
        lastNotifyAt = now
        val notification = NotificationCompat.Builder(
            applicationContext,
            channelId
        )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(applicationContext.getString(R.string.command_noti_title))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(line)
            )
            .setProgress(100, progress, progress == -1)
            .build()
        notificationManager?.notify(id, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            var notificationChannel =
                notificationManager?.getNotificationChannel(channelId)
            if (notificationChannel == null) {
                val channelName = applicationContext.getString(R.string.command_noti_channel_name)
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
        private const val channelId = "dlget_command"
        private const val NOTIFY_THROTTLE_MS = 1000L
        const val commandKey = "command"
        const val commandWorkTag = "command_work"
        private const val commandRegex = "\"([^\"]*)\"|(\\S+)"
    }

    private var lastNotifyAt = 0L
    private var lastNotifiedPercent = -2
}

