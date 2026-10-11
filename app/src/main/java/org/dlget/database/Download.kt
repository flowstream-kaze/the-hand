package org.dlget.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloads_table")
data class Download(
    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "total_size")
    var totalSize: Long
) {
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0

    @ColumnInfo(name = "downloaded_percent")
    var downloadedPercent: Double = 0.0

    @ColumnInfo(name = "downloaded_size")
    var downloadedSize: Long = 0L

    @ColumnInfo(name = "downloaded_path")
    lateinit var downloadedPath: String

    @ColumnInfo(name = "media_type")
    lateinit var mediaType: String

    @ColumnInfo(name = "status", defaultValue = "1")
    var status: Int = STATUS_ACTIVE

    // WorkManager tag of the download (the upstream video id) - lets a row cancel
    // its own in-flight download instead of being orphaned by it.
    @ColumnInfo(name = "task_id", defaultValue = "")
    var taskId: String = ""

    // Why a FAILED row failed - shown when the row is tapped
    @ColumnInfo(name = "fail_reason", defaultValue = "")
    var failReason: String = ""

    @ColumnInfo(name = "url", defaultValue = "")
    var url: String = ""

    // The -f value this row used ("137+bestaudio", "18", "mp3") - a redownload
    // rebuilds the exact same request from it
    @ColumnInfo(name = "format_spec", defaultValue = "")
    var formatSpec: String = ""

    @ColumnInfo(name = "embed_subs", defaultValue = "0")
    var embedSubs: Int = 0

    companion object {
        const val STATUS_ACTIVE = 0
        const val STATUS_DONE = 1
        const val STATUS_FAILED = 2
    }
}