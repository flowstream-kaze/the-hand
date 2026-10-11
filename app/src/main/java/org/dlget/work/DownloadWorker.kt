package org.dlget.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.apache.commons.io.IOUtils
import org.dlget.App
import org.dlget.R
import org.dlget.database.AppDatabase
import org.dlget.database.Download
import org.dlget.database.DownloadsRepository
import org.dlget.utils.FileNameUtils
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

class DownloadWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as
                NotificationManager?

    override suspend fun doWork(): Result {

        val url = inputData.getString(urlKey)!!
        val name = FileNameUtils.createFilename(inputData.getString(nameKey)!!)
        // absent on a redownload, which passes the whole format spec instead
        val formatId = inputData.getString(formatIdKey).orEmpty()
        val acodec = inputData.getString(acodecKey)
        val vcodec = inputData.getString(vcodecKey)
        val downloadDir = inputData.getString(downloadDirKey)!!
        val size = inputData.getLong(sizeKey, 0L)
        // Expected bytes on disk: for a video-only format yt-dlp also pulls bestaudio,
        // so the real total is bigger than the selected format's own size.
        val expectedTotal = inputData.getLong(totalSizeKey, 0L).let { if (it > 0) it else size }
        val taskId = inputData.getString(taskIdKey)!!
        val toMp3 = inputData.getBoolean(mp3Key, false)
        val embedSubs = inputData.getBoolean(embedSubsKey, false)

        createNotificationChannel()
        val notificationId = id.hashCode()
        // WorkManager cancels the foreground-service notification id when the work
        // stops, so the result notification must live under a different id or it is
        // removed the moment doWork() returns.
        val resultId = notificationId + RESULT_ID_OFFSET
        val notification = NotificationCompat.Builder(
            applicationContext,
            channelId
        )
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(name)
            .setContentText(applicationContext.getString(R.string.download_start))
            .build()

        val foregroundInfo = ForegroundInfo(
            notificationId,
            notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        try {
            setForeground(foregroundInfo)
        } catch (e: Exception) {
            // Android 12+ rejects foreground-service starts while the app is in the
            // background (e.g. WorkManager resuming a download after a reboot).
            // Losing the progress notification beats losing the download.
            Log.w("DownloadWorker", "could not start foreground service", e)
        }

        // App-private temp dir, per task so concurrent downloads don't collide.
        // Not cacheDir: the system and OEM "cleaner" apps trim cache, which can make a
        // finished download vanish before it is copied out.
        val tmpDir = File(applicationContext.noBackupFilesDir, "dlget_tmp/$id")
        if (!tmpDir.exists()) tmpDir.mkdirs()

        val videoOnly = vcodec != "none" && acodec == "none"
        // A redownload passes the spec its row recorded; a fresh download derives it here.
        val formatSpec = inputData.getString(formatSpecKey)?.takeIf { it.isNotBlank() }
            ?: when {
                toMp3 -> "mp3"
                videoOnly -> "${formatId}+bestaudio"
                else -> formatId
            }
        val merges = formatSpec.contains("+") || formatSpec == "mp3" || embedSubs

        // Show the download in the Downloads tab from the start, not only after it finishes
        val downloadsDao = AppDatabase.getDatabase(
            applicationContext
        ).downloadsDao()
        val repository =
            DownloadsRepository(downloadsDao)
        val row =
            Download(name, Date().time, expectedTotal)
        row.downloadedPath = ""
        row.mediaType = if (vcodec == "none" || formatSpec == "mp3") "audio" else "video"
        row.status = Download.STATUS_ACTIVE
        row.taskId = taskId
        row.url = url
        row.formatSpec = formatSpec
        row.embedSubs = if (embedSubs) 1 else 0
        val existingRowId = inputData.getLong(rowIdKey, 0L)
        if (existingRowId > 0) {
            // Redownload: keep one row per download instead of stacking a new one
            row.id = existingRowId
            downloadsDao.update(row)
        } else {
            row.id = downloadsDao.insert(row)
        }

        var destUri: Uri? = null
        val createdDocs = mutableListOf<Uri>()
        var actualSize = 0L
        var lastPercent = -1
        try {
            val treeUri = Uri.parse(downloadDir)
            val granted = applicationContext.contentResolver.persistedUriPermissions.any {
                it.uri == treeUri && it.isReadPermission && it.isWritePermission
            }
            if (!granted) {
                throw SecurityException(
                    "No access to the download folder - re-pick it in Settings"
                )
            }
            // Dying after 100% of the bytes arrive is the worst place to find a full disk
            checkStorage(if (expectedTotal > 0) expectedTotal else size, merges, treeUri, tmpDir)
            App.initLatch.await(120, TimeUnit.SECONDS)
            var wantSubs = embedSubs
            while (true) {
                val request = buildRequest(url, name, tmpDir, formatSpec, wantSubs)
                if (existingRowId > 0) request.addOption("-v")
                try {
                    YoutubeDL.getInstance().execute(request, taskId) { _, _, line ->
                        val p = parseProgress(line)
                        if (p != null) {
                            val (bytes, overall) = p
                            showProgress(id.hashCode(), taskId, name, overall, bytes)
                            if (overall != lastPercent) {
                                lastPercent = overall
                                row.downloadedPercent = overall.toDouble()
                                row.downloadedSize = bytes
                                // A row created without a known size gets one as soon as
                                // yt-dlp reveals the real total for the file it is on
                                if (expectedTotal == 0L && bytes > 0) {
                                    row.totalSize = (completedBytes + bytes /
                                            maxOf(lastFrac, 0.0001)).toLong()
                                }
                                runBlocking { downloadsDao.update(row) }
                            }
                        }
                    }
                    break
                } catch (e: YoutubeDLException) {
                    // YouTube throttles the subtitle endpoint on its own (HTTP 429) and
                    // yt-dlp treats that as fatal, which throws away a perfectly
                    // downloadable video. The video matters more than the captions.
                    val msg = e.message.orEmpty()
                    if (wantSubs && (msg.contains("download video subtitles") ||
                            msg.contains("download subtitles"))
                    ) {
                        Log.w("DownloadWorker", "subtitles unavailable, retrying without them", e)
                        wantSubs = false
                        resetProgress()
                        continue
                    }
                    throw e
                }
            }
            val all = tmpDir.listFiles().orEmpty()
            // With --write-subs yt-dlp keeps the subtitle file next to the video even after
            // embedding it, and the copy loop would otherwise leave a stray sidecar (and
            // possibly point the DB row at it). Only drop sidecars when real media is there.
            val media = all.filter { it.extension.lowercase() !in SUBTITLE_EXTS }
            val downloaded: List<File> = if (wantSubs && media.isNotEmpty()) media else all.toList()
            if (downloaded.isEmpty()) {
                throw IllegalStateException("Download produced no files")
            }
            // Copy the biggest file last: the last created document becomes the row's path
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val destDir = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            downloaded.sortedBy { it.length() }.forEach {
                val mimeType =
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.extension) ?: "*/*"
                val created = DocumentsContract.createDocument(
                    applicationContext.contentResolver,
                    destDir,
                    mimeType,
                    it.name
                ) ?: throw java.io.IOException("Could not create ${it.name} in the download folder")
                destUri = created
                createdDocs += created
                it.inputStream().use { ins ->
                    applicationContext.contentResolver.openOutputStream(created)?.use { ops ->
                        IOUtils.copy(ins, ops)
                    } ?: throw java.io.IOException("Could not write ${it.name}")
                }
                actualSize += it.length()
                // Free the temp copy straight away: a big download needs the destination
                // space too, and holding both until the end doubles the peak usage
                it.delete()
            }
        } catch (e: Exception) {
            Log.e("DownloadWorker", "download failed: $name", e)
            // Drop half-written files from the user's folder - a truncated copy is worse than none
            createdDocs.forEach {
                try {
                    DocumentsContract.deleteDocument(applicationContext.contentResolver, it)
                } catch (ignored: Exception) {
                }
            }
            // Keep the row visible as failed - silently deleting it hides the problem
            val reason = failureReason(e)
            row.status = Download.STATUS_FAILED
            row.failReason = reason
            runBlocking { repository.update(row) }
            showFailure(resultId, name, reason)
            return Result.failure()
        } finally {
            tmpDir.deleteRecursively()
        }

        row.downloadedPath = destUri.toString()
        row.failReason = ""
        row.downloadedPercent = 100.00
        row.downloadedSize = actualSize
        row.status = Download.STATUS_DONE
        // totalSize = actual size on disk: yt-dlp often reports no size for adaptive
        // formats, and a finished download's total is by definition what we got
        row.totalSize = if (actualSize > 0) actualSize else size
        repository.update(row)

        notificationManager?.notify(
            resultId,
            NotificationCompat.Builder(applicationContext, channelId)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle(name)
                .setContentText(applicationContext.getString(R.string.download_complete))
                .setOngoing(false)
                .setAutoCancel(true)
                .build()
        )

        return Result.success()
    }

    private var bestPercent = 0
    private var completedBytes = 0L
    private var lastFileBytes = 0L
    private var lastFrac = 0.0

    // DLGET <downloaded_bytes> <total_bytes> <total_bytes_estimate> <frag_index>/<frag_count>
    // missing values come through as "NA"
    private val progressRegex = Regex("""^DLGET (\S+) (\S+) (\S+) (\S+)/(\S+)""")
    private val destinationRegex = Regex("""^\[download\] Destination: (.+)$""")
    private var skipCurrentFile = false

    /**
     * Turn one yt-dlp progress line into (bytes downloaded so far, overall percent).
     * Overall percent is size-weighted across the files of a merged download: the
     * current file's share is bytes/total, falling back to fragment_index/fragment_count
     * (exact for DASH/HLS) and then to yt-dlp's running size estimate.
     * The returned percent never decreases.
     */
    private fun parseProgress(line: String): Pair<Long, Int>? {
        // Subtitle/thumbnail files are downloaded too, and a 440-byte .vtt would
        // otherwise jump the bar to 99% before the video has even started.
        val dest = destinationRegex.find(line)
        if (dest != null) {
            skipCurrentFile =
                dest.groupValues[1].substringAfterLast('.', "").lowercase() in SUBTITLE_EXTS
            return null
        }
        if (skipCurrentFile) return null

        val m = progressRegex.find(line) ?: return null
        fun num(s: String) = s.toDoubleOrNull()

        val downloaded = num(m.groupValues[1]) ?: return null
        val total = num(m.groupValues[2])
        val estimate = num(m.groupValues[3])
        val fragIndex = num(m.groupValues[4])
        val fragCount = num(m.groupValues[5])

        if (downloaded < lastFileBytes) {   // yt-dlp moved on to the next file (merge)
            completedBytes += lastFileBytes
            lastFileBytes = 0
        }
        lastFileBytes = maxOf(lastFileBytes, downloaded.toLong())

        val frac = when {
            total != null && total > 0 -> downloaded / total
            fragCount != null && fragCount > 0 && fragIndex != null -> fragIndex / fragCount
            estimate != null && estimate > 0 -> downloaded / estimate
            else -> lastFrac
        }.coerceIn(0.0, 1.0)
        if (frac > 0) lastFrac = frac

        val fileTotal = if (total != null && total > 0) total.toLong()
        else (downloaded / maxOf(lastFrac, 0.0001)).toLong()
        val done = completedBytes + downloaded.toLong()
        val overall = if (completedBytes + fileTotal > 0) {
            (done * 100 / (completedBytes + fileTotal)).toInt().coerceIn(0, 99)
        } else 0
        bestPercent = maxOf(bestPercent, overall)
        return done to bestPercent
    }

    private fun buildRequest(
        url: String,
        name: String,
        tmpDir: File,
        formatSpec: String,
        embedSubs: Boolean
    ): YoutubeDLRequest {
        val request = YoutubeDLRequest(url)
        // Share links often carry &list=...; without this yt-dlp fetches the whole playlist
        request.addOption("--no-playlist")
        // DASH/HLS downloads are split into fragments. Fetching four at a time is a
        // useful speed-up without overwhelming the device or hammering the server.
        // Single-file HTTP formats ignore this; they need an external downloader for
        // multi-connection transfers, which this app deliberately does not bundle.
        request.addOption("--concurrent-fragments", "4")
        // App-private temp dir, per task so concurrent downloads don't collide
        request.addOption("-o", "${tmpDir.absolutePath}/${name}.%(ext)s")
        // Machine-readable progress. yt-dlp's own "NN.N%" is per-fragment for DASH/HLS
        // formats (and the library parser keeps repeating the last value it saw), which
        // is what made the bar flash and peg at 99% seconds into a long download.
        request.addOption(
            "--progress-template",
            "download:DLGET %(progress.downloaded_bytes)s %(progress.total_bytes)s " +
                    "%(progress.total_bytes_estimate)s %(progress.fragment_index)s/%(progress.fragment_count)s"
        )
        when (formatSpec) {
            "mp3" -> {
                request.addOption("-f", "bestaudio")
                request.addOption("-x")
                request.addOption("--audio-format", "mp3")
                request.addOption("--audio-quality", "0")
            }
            // rows saved before the format was recorded: let yt-dlp choose
            "default" -> Unit
            else -> request.addOption("-f", formatSpec)
        }
        if (embedSubs) {
            // English subtitles: manual captions preferred, auto-generated as fallback,
            // embedded as a selectable track inside the video container (no sidecar file).
            request.addOption("--write-subs")
            request.addOption("--write-auto-subs")
            request.addOption("--sub-langs", "en")
            request.addOption("--embed-subs")
        }
        return request
    }

    /** A retry starts the size-weighted percent over; the bar must not stay pegged. */
    private fun resetProgress() {
        bestPercent = 0
        completedBytes = 0L
        lastFileBytes = 0L
        lastFrac = 0.0
        skipCurrentFile = false
    }

    /**
     * A download needs the space twice over: yt-dlp writes the fragments - and the
     * merged file on top of them when merging - into the temp dir, and the finished
     * file is then copied into the user's folder. Refuse up front instead of failing
     * after the whole file has been fetched.
     */
    private fun checkStorage(expected: Long, merges: Boolean, treeUri: Uri, tmpDir: File) {
        if (expected <= 0) return   // unknown size: nothing honest to promise
        val headroom = expected / 10
        val tmpPeak = if (merges) expected * 2 + headroom else expected + headroom
        // A tree on "primary:" shares the pool the temp dir lives in; an SD card does not
        val dest = treeUri.toString()
        val sameVolume = dest.contains("primary%3A") || dest.contains("primary:")
        // The merge (fragments + output) and the copy (merged file + destination copy)
        // happen one after the other, so the peak is the larger of the two, not their sum
        val copyPeak = if (sameVolume) expected + headroom + expected + headroom else 0L
        val required = maxOf(tmpPeak, copyPeak)
        val free = android.os.StatFs(tmpDir.absolutePath).availableBytes
        if (free < required) {
            throw java.io.IOException(
                applicationContext.getString(
                    R.string.not_enough_space,
                    android.text.format.Formatter.formatShortFileSize(applicationContext, required),
                    android.text.format.Formatter.formatShortFileSize(applicationContext, free)
                )
            )
        }
    }

    /**
     * Short, human-usable failure text. yt-dlp puts the real error at the END of its
     * stderr, so keep the tail rather than the head.
     */
    private fun failureReason(e: Exception): String {
        val msg = (e.message ?: e.javaClass.simpleName).trim()
        return if (msg.length <= 600) msg else "…" + msg.takeLast(600)
    }

    private fun showProgress(
        id: Int,
        taskId: String,
        name: String,
        progress: Int,
        bytes: Long
    ) {
        // yt-dlp emits a progress line every ~100ms; notifying per line spams the
        // notification channel and burns CPU/battery.
        val now = SystemClock.elapsedRealtime()
        if (progress == lastNotifiedPercent && now - lastNotifyAt < NOTIFY_THROTTLE_MS) return
        lastNotifiedPercent = progress
        lastNotifyAt = now
        val text = android.text.format.Formatter.formatShortFileSize(
            applicationContext, bytes
        )
        val intent = Intent(applicationContext, CancelReceiver::class.java)
            .putExtra("taskId", taskId)
            .putExtra("notificationId", id)

        val pendingIntent = PendingIntent.getBroadcast(
            applicationContext, 0, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(
            applicationContext,
            channelId
        )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(name)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(text)
            )
            .setProgress(100, progress, progress < 0)
            .addAction(
                R.drawable.baseline_stop_24,
                applicationContext.getString(R.string.cancel_download),
                pendingIntent
            )
            .build()
        notificationManager?.notify(id, notification)
    }

    private fun showFailure(id: Int, name: String, message: String?) {
        // message is usually raw yt-dlp stderr - keep the headline short, details expandable
        notificationManager?.notify(
            id,
            NotificationCompat.Builder(applicationContext, channelId)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle(name)
                .setContentText(applicationContext.getString(R.string.download_failed))
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(message ?: applicationContext.getString(R.string.download_failed))
                )
                .setOngoing(false)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            var notificationChannel =
                notificationManager?.getNotificationChannel(channelId)
            if (notificationChannel == null) {
                val channelName = applicationContext.getString(R.string.download_noti_channel_name)
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

    private var lastNotifyAt = 0L
    private var lastNotifiedPercent = -2

    companion object {
        private const val channelId = "dlget_download"
        private const val NOTIFY_THROTTLE_MS = 1000L
        // Result notifications must not share the foreground-service notification id
        const val RESULT_ID_OFFSET = 10_000
        const val urlKey = "url"
        const val nameKey = "name"
        const val formatIdKey = "formatId"
        const val acodecKey = "acodec"
        const val vcodecKey = "vcodec"
        const val downloadDirKey = "downloadDir"
        const val sizeKey = "size"
        const val totalSizeKey = "total_size"
        const val taskIdKey = "taskId"
        const val mp3Key = "toMp3"
        const val embedSubsKey = "embed_subs"
        const val formatSpecKey = "format_spec"
        const val rowIdKey = "row_id"

        // yt-dlp subtitle sidecar extensions (only relevant when subtitles are embedded)
        private val SUBTITLE_EXTS = setOf(
            "vtt", "srt", "ass", "ssa", "ttml", "json3", "json", "srv1", "srv2", "srv3", "lrc"
        )
    }
}