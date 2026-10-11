package org.dlget.utils

import android.content.Context
import android.text.format.Formatter
import com.yausername.youtubedl_android.mapper.VideoFormat

/**
 * Turns raw yt-dlp format fields into human-readable labels.
 */
object FormatLabel {

    /** Primary line, e.g. "1080p · 30 fps · H.264" or "Audio only · Opus · 128 kbps". */
    fun primary(f: VideoFormat): String {
        val parts = mutableListOf<String>()
        if (f.vcodec == "none") {
            parts.add("Audio only")
            codecName(f.acodec)?.let { parts.add(it) }
            if (f.abr >= 1000) parts.add("${f.abr / 1000} kbps")
        } else {
            if (f.height > 0) parts.add("${f.height}p")
            else if (f.width > 0) parts.add("${f.width}w")
            if (f.fps > 0) parts.add("${f.fps} fps")
            // The container (mp4/webm) says less than the codec: two rows can both be
            // "mp4" and still be H.264 and VP9 streams of different sizes.
            codecName(f.vcodec)?.let { parts.add(it) }
        }
        return parts.joinToString(" · ")
    }

    /** yt-dlp codec ids ("avc1.640028", "vp09.00.40.08") spelled the way people say them. */
    private fun codecName(codec: String?): String? = when {
        codec.isNullOrBlank() || codec == "none" -> null
        codec.startsWith("avc1") || codec.startsWith("h264") -> "H.264"
        codec.startsWith("vp09") || codec.startsWith("vp9") -> "VP9"
        codec.startsWith("av01") || codec.startsWith("av1") -> "AV1"
        codec.startsWith("mp4a") || codec.startsWith("aac") -> "AAC"
        codec.startsWith("opus") -> "Opus"
        codec.startsWith("ec-3") -> "E-AC-3"
        codec.startsWith("ac-3") -> "AC-3"
        else -> codec
    }

    /** Secondary line, e.g. "137 · 80 MB". HLS entries only report an approximate size. */
    fun secondary(context: Context, f: VideoFormat): String {
        val parts = mutableListOf(f.formatId)
        val size = if (f.fileSize > 0) f.fileSize else f.fileSizeApproximate
        when {
            f.fileSize > 0 -> parts.add(Formatter.formatShortFileSize(context, size))
            size > 0 -> parts.add("\u2248 ${Formatter.formatShortFileSize(context, size)}")
            f.tbr > 0 -> parts.add("${f.tbr / 1000} kbps")
            else -> parts.add("size unknown")
        }
        return parts.joinToString(" · ")
    }

    /**
     * Best format to recommend: highest-resolution format that carries both video and audio,
     * falling back to the best video-only, then best audio-only.
     */
    fun bestFormat(formats: List<VideoFormat>): VideoFormat? {
        val withVideo = formats.filter { it.vcodec != "none" }
        val combined = withVideo.filter { it.acodec != "none" }
        return (combined.ifEmpty { withVideo })
            .maxByOrNull { it.height.toLong() * 1_000_000 + it.tbr }
            ?: formats.maxByOrNull { it.abr.toLong() }
    }

    /**
     * Formats worth showing: real media only (drops storyboards/mhtml, which carry
     * neither audio nor video), duplicates of the same stream collapsed (yt-dlp
     * returns e.g. 233-0..233-4 for one stream), video first by resolution,
     * then audio by bitrate.
     */
    fun displayable(formats: List<VideoFormat>): List<VideoFormat> {
        val media = formats.filter { it.vcodec != "none" || it.acodec != "none" }
        val deduped = media
            .groupBy { it.formatId.substringBefore('-') }
            .values.mapNotNull { g -> g.maxByOrNull { maxOf(it.fileSize, it.tbr.toLong()) } }
        // One row per picture: yt-dlp offers the same encode several times over (an HLS
        // and a DASH copy of one stream), which reads as duplicates in the list. Within a
        // resolution prefer a format that needs no ffmpeg merge, then one whose size is
        // known up front, then the better bitrate.
        val videos = deduped.filter { it.vcodec != "none" }
            .groupBy { "${it.height}x${it.width}/${it.fps}" }
            .values.mapNotNull { g ->
                g.maxWithOrNull(
                    compareBy(
                        { if (it.acodec != "none") 1 else 0 },
                        { if (it.fileSize > 0) 1 else 0 },
                        { it.tbr.toLong() }
                    )
                )
            }
        val audio = deduped.filter { it.vcodec == "none" }
        return videos.sortedWith(
            compareByDescending<VideoFormat> { it.height }.thenByDescending { it.width }
        ) + audio.sortedByDescending { it.abr }
    }

    fun isRecommended(formats: List<VideoFormat>, f: VideoFormat): Boolean =
        bestFormat(displayable(formats))?.formatId == f.formatId
}