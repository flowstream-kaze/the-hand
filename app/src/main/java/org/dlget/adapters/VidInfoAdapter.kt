package org.dlget.adapters

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat.startActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dlget.R
import org.dlget.databinding.VidFormatBinding
import org.dlget.databinding.VidHeaderBinding
import org.dlget.model.VidInfoItem
import org.dlget.utils.FormatLabel
import org.dlget.utils.NumberUtils
import java.util.concurrent.TimeUnit

private const val ITEM_VIEW_TYPE_HEADER = 0
private const val ITEM_VIEW_TYPE_ITEM = 1

class VidInfoAdapter(private val clickListener: VidInfoListener) :
    ListAdapter<VidInfoItem, RecyclerView.ViewHolder>(
        VidInfoDiffCallback()
    ) {

    private val adapterScope = CoroutineScope(Dispatchers.Default)
    private var recommendedFormatId: String? = null

    fun fill(vidInfo: VideoInfo?) {
        adapterScope.launch {
            if (vidInfo == null) {
                submitList(emptyList())
                return@launch
            }
            val items = mutableListOf<VidInfoItem>()
            withContext(Dispatchers.Default) {
                val formats = FormatLabel.displayable(vidInfo.formats.orEmpty())
                val videos = formats.filter { it.vcodec != "none" }
                val shownVideos = if (videos.size > 2) videos.take(3) else videos
                recommendedFormatId = FormatLabel.bestFormat(shownVideos)?.formatId
                items.add(VidInfoItem.VidHeaderItem(vidInfo))
                shownVideos.forEach { format ->
                    items.add(
                        VidInfoItem.VidFormatItem(
                            vidInfo,
                            format.formatId
                        )
                    )
                }
                if (formats.any { it.acodec != "none" }) {
                    items.add(VidInfoItem.VidAudioMp3Item(vidInfo))
                }
            }

            withContext(Dispatchers.Main) {
                submitList(items.toList())
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is ViewHolder -> {
                when (val item = getItem(position)) {
                    is VidInfoItem.VidFormatItem -> {
                        val vidFormat = item.vidFormat
                        with(holder.binding) {
                            formatTv.text = FormatLabel.primary(vidFormat)
                            extTv.text = FormatLabel.secondary(root.context, vidFormat)
                            recTv.visibility =
                                if (vidFormat.formatId == recommendedFormatId) View.VISIBLE else View.GONE
                            if (vidFormat.acodec != "none" && vidFormat.vcodec == "none") {
                                formatIc.setImageResource(R.drawable.ic_baseline_audiotrack_24)
                            } else {
                                formatIc.setImageResource(R.drawable.ic_baseline_video_library_24)
                            }
                            itemShare.visibility = View.VISIBLE
                            itemShare.setOnClickListener {
                                vidFormat.url?.let { shareUrl(it, root.context) }
                            }
                            root.setOnClickListener { clickListener.onClick(item) }
                        }
                    }
                    is VidInfoItem.VidAudioMp3Item -> {
                        with(holder.binding) {
                            formatTv.text = root.context.getString(R.string.audio_mp3_label)
                            extTv.setText(R.string.audio_mp3_sub)
                            recTv.visibility = View.GONE
                            formatIc.setImageResource(R.drawable.ic_baseline_audiotrack_24)
                            itemShare.visibility = View.GONE
                            root.setOnClickListener { clickListener.onClick(item) }
                        }
                    }
                    else -> throw ClassCastException("Unknown item ${item.javaClass}")
                }
            }
            is HeaderViewHolder -> {
                val vidItem = getItem(position) as VidInfoItem.VidHeaderItem
                val vidInfo = vidItem.vidInfo
                with(holder.binding) {
                    titleTv.text = vidInfo.title
                    uploaderTv.text = vidInfo.uploader
                    thumbIv.load(vidInfo.thumbnail)
                    viewsTv.text = vidInfo.viewCount?.toLongOrNull()?.let {
                        NumberUtils.format(it)
                    } ?: vidInfo.viewCount
                    likesTv.text = vidInfo.likeCount?.toLongOrNull()?.let {
                        NumberUtils.format(it)
                    } ?: vidInfo.likeCount
                    val hasLikes = !vidInfo.likeCount.isNullOrBlank()
                    thumbsUpIc.visibility = if (hasLikes) View.VISIBLE else View.GONE
                    likesTv.visibility = if (hasLikes) View.VISIBLE else View.GONE
                    val hasDislikes = !vidInfo.dislikeCount.isNullOrBlank()
                    thumbsDownIc.visibility = if (hasDislikes) View.VISIBLE else View.GONE
                    dislikesTv.visibility = if (hasDislikes) View.VISIBLE else View.GONE
                    dislikesTv.text = vidInfo.dislikeCount?.toLongOrNull()?.let {
                        NumberUtils.format(it)
                    } ?: vidInfo.dislikeCount
                    uploadDateTv.text = vidInfo.uploadDate
                    vidInfo.duration.toLong().apply {
                        val minutes = TimeUnit.SECONDS.toMinutes(this)
                        val seconds = this - TimeUnit.MINUTES.toSeconds(minutes)
                        durationTv.text = root.context.getString(R.string.duration, minutes, seconds)
                    }
                }
            }
            else -> throw ClassCastException("Unknown viewType ${getItemViewType(position)}")
        }
    }

    private fun shareUrl(url: String, context: Context) {
        val intent = Intent()
        intent.action = Intent.ACTION_SEND
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_TEXT, url)
        startActivity(context, Intent.createChooser(intent, null), null)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            ITEM_VIEW_TYPE_HEADER -> HeaderViewHolder(
                VidHeaderBinding.inflate(layoutInflater, parent, false)
            )
            ITEM_VIEW_TYPE_ITEM -> ViewHolder(
                VidFormatBinding.inflate(layoutInflater, parent, false)
            )
            else -> throw ClassCastException("Unknown viewType $viewType")
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is VidInfoItem.VidHeaderItem -> ITEM_VIEW_TYPE_HEADER
            is VidInfoItem.VidFormatItem, is VidInfoItem.VidAudioMp3Item -> ITEM_VIEW_TYPE_ITEM
        }
    }

    class HeaderViewHolder(val binding: VidHeaderBinding) :
        RecyclerView.ViewHolder(binding.root)

    class ViewHolder(val binding: VidFormatBinding) :
        RecyclerView.ViewHolder(binding.root)
}

class VidInfoDiffCallback : DiffUtil.ItemCallback<VidInfoItem>() {
    override fun areItemsTheSame(oldItem: VidInfoItem, newItem: VidInfoItem): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: VidInfoItem, newItem: VidInfoItem): Boolean {
        return oldItem == newItem
    }
}


class VidInfoListener(val clickListener: (VidInfoItem) -> Unit) {
    fun onClick(vidInfo: VidInfoItem) = clickListener(vidInfo)
}