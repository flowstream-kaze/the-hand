package org.dlget.adapters

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat.startActivity
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.DiffUtil
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.dlget.R
import org.dlget.database.Download
import org.dlget.databinding.FragmentDownloadsBinding
import org.dlget.work.DeleteWorker
import org.dlget.work.DownloadWorker

class DownloadsAdapter(
    private val onSelectionRequested: () -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<DownloadsAdapter.ViewHolder>() {

    private var mValues: List<Download> = emptyList()
    private val selectedIds = linkedSetOf<Long>()
    private var selectionMode = false

    fun addItems(items: List<Download>) {
        val old = mValues
        // DiffUtil instead of notifyDataSetChanged(): the list is re-emitted on every
        // progress tick, and a full rebind restarts each row's progress animation
        // (visible flicker) instead of moving it forward.
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = items.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                old[oldPos].id == items[newPos].id

            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                sameContent(old[oldPos], items[newPos])
        }, false)
        mValues = items
        val oldSelectionCount = selectedIds.size
        selectedIds.retainAll(items.mapTo(hashSetOf()) { it.id })
        diff.dispatchUpdatesTo(this)
        if (selectedIds.size != oldSelectionCount) {
            onSelectionChanged(selectedIds.size)
        }
    }

    private fun sameContent(a: Download, b: Download) =
        a.name == b.name &&
                a.status == b.status &&
                a.downloadedPercent == b.downloadedPercent &&
                a.downloadedSize == b.downloadedSize &&
                a.totalSize == b.totalSize &&
                a.mediaType == b.mediaType &&
                a.downloadedPath == b.downloadedPath

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = FragmentDownloadsBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = mValues[position]
        val binding = holder.binding

        with(binding) {
            val selected = item.id in selectedIds
            root.isChecked = selected
            root.strokeWidth = if (selected) {
                (2 * root.resources.displayMetrics.density).toInt()
            } else {
                0
            }
            itemMore.visibility = if (selectionMode) View.GONE else View.VISIBLE
            titleTv.text = item.name
            val failed = item.status == Download.STATUS_FAILED
            @SuppressLint("SetTextI18n")
            downloadPercentTv.text =
                if (failed) root.context.getString(R.string.download_failed_short)
                else "${item.downloadedPercent.toInt()}%"
            val totalSize = Formatter.formatShortFileSize(root.context, item.totalSize)
            val downloadedSize = Formatter.formatShortFileSize(root.context, item.downloadedSize)
            @SuppressLint("SetTextI18n")
            downloadSizeTv.text =
                if (item.totalSize > 0) "${downloadedSize}/${totalSize}" else downloadedSize
            when {
                failed -> downloadProgress.visibility = View.GONE
                else -> {
                    downloadProgress.visibility = View.VISIBLE
                    val percent = item.downloadedPercent.toInt().coerceIn(0, 100)
                    // Always determinate while running: the worker now derives a
                    // monotonic percent from bytes on disk, so an indeterminate
                    // marquee (the "flashing" bar) is never needed.
                    downloadProgress.isIndeterminate = false
                    downloadProgress.max = 100
                    downloadProgress.setProgressCompat(percent, true)
                }
            }
            if (item.mediaType == "audio") {
                formatIc.setImageResource(R.drawable.ic_baseline_audiotrack_24)
            } else {
                formatIc.setImageResource(R.drawable.ic_baseline_video_library_24)
            }
            itemMore.setOnClickListener {
                val popupMenu = PopupMenu(root.context, it)
                val inflater = popupMenu.menuInflater
                inflater.inflate(R.menu.sub_menu, popupMenu.menu)
                popupMenu.menu.findItem(R.id.redownload)?.isVisible =
                    item.status == Download.STATUS_FAILED
                popupMenu.show()
                popupMenu.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        R.id.redownload -> {
                            redownload(item, root.context)
                        }
                        R.id.share -> {
                            shareContent(item.downloadedPath, root.context)
                        }
                        R.id.delete -> {
                            startDelete(item.id, root.context)
                        }
                    }
                    true
                }
            }
            root.setOnClickListener {
                if (selectionMode) {
                    toggleSelection(item.id)
                } else if (item.status == Download.STATUS_FAILED) {
                    showFailureReason(item, it.context)
                } else {
                    viewContent(item.downloadedPath, it.context)
                }
            }
            root.setOnLongClickListener {
                if (!selectionMode) onSelectionRequested()
                if (selectionMode) toggleSelection(item.id)
                true
            }
        }
    }

    override fun getItemCount(): Int = mValues.size

    @SuppressLint("NotifyDataSetChanged")
    fun setSelectionMode(enabled: Boolean) {
        if (selectionMode == enabled) return
        selectionMode = enabled
        if (!enabled) selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.size)
    }

    fun selectedIds(): Set<Long> = selectedIds.toSet()

    private fun toggleSelection(id: Long) {
        if (!selectionMode) return
        if (!selectedIds.add(id)) selectedIds.remove(id)
        val position = mValues.indexOfFirst { it.id == id }
        if (position >= 0) notifyItemChanged(position)
        onSelectionChanged(selectedIds.size)
    }

    inner class ViewHolder(val binding: FragmentDownloadsBinding) :
        RecyclerView.ViewHolder(binding.root)

    /**
     * Run the same request again, reusing the row. Rows saved before the URL and format
     * were recorded fall back to the video id (YouTube) and yt-dlp's default format.
     */
    private fun redownload(item: Download, context: Context) {
        val url = item.url.ifBlank {
            if (item.taskId.isNotBlank()) "https://www.youtube.com/watch?v=${item.taskId}" else ""
        }
        val dir = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(context.getString(R.string.download_location_key), null)
        if (url.isBlank() || dir.isNullOrEmpty()) {
            Toast.makeText(context, R.string.redownload_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val tag = item.taskId.ifBlank { "redownload_${item.id}" }
        val workData = workDataOf(
            DownloadWorker.urlKey to url,
            DownloadWorker.nameKey to item.name,
            DownloadWorker.downloadDirKey to dir,
            DownloadWorker.taskIdKey to tag,
            DownloadWorker.formatSpecKey to item.formatSpec.ifBlank { "default" },
            DownloadWorker.totalSizeKey to item.totalSize,
            DownloadWorker.embedSubsKey to (item.embedSubs == 1),
            DownloadWorker.rowIdKey to item.id
        )
        val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .addTag(tag)
            .setInputData(workData)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(tag, ExistingWorkPolicy.REPLACE, workRequest)
    }

    private fun showFailureReason(item: Download, context: Context) {
        val reason = item.failReason.ifBlank {
            context.getString(R.string.no_failure_reason)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.download_failed)
            .setMessage(reason)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun viewContent(path: String, context: Context) {
        val intent = Intent(Intent.ACTION_VIEW)
        val uri = Uri.parse(path)
        val downloadedFile = DocumentFile.fromSingleUri(context, uri) ?: return
        if (!downloadedFile.exists()) {
            Toast.makeText(context, R.string.file_not_found, Toast.LENGTH_SHORT).show()
            return
        }
        val mimeType = context.contentResolver.getType(uri) ?: "*/*"
        intent.setDataAndType(uri, mimeType)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(context, intent, null)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.app_not_found, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareContent(path: String, context: Context) {
        val file = DocumentFile.fromSingleUri(context, Uri.parse(path)) ?: return
        if (file.exists()) {
            val intent = Intent(Intent.ACTION_SEND)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.type = context.contentResolver.getType(Uri.parse(path)) ?: "*/*"
            intent.putExtra(Intent.EXTRA_STREAM, Uri.parse(path))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(context, Intent.createChooser(intent, null), null)
        } else {
            Toast.makeText(context, R.string.file_cannot_be_shared, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startDelete(id: Long, context: Context) {
        val workTag = "tag_$id"
        val workManager = WorkManager.getInstance(context.applicationContext)
        val workData = workDataOf(
            DeleteWorker.fileIdKey to id
        )
        val workRequest = OneTimeWorkRequestBuilder<DeleteWorker>()
            .addTag(workTag)
            .setInputData(workData)
            .build()

        // Unique per row + REPLACE, so a repeated tap cannot queue two deletes and we
        // never have to query WorkManager from this click handler (that would block
        // the main thread on ListenableFuture.get()).
        workManager.enqueueUniqueWork(
            workTag,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

}