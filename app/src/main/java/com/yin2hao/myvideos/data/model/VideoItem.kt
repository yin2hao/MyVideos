package com.yin2hao.myvideos.data.model

/**
 * 视频列表项 - 用于UI显示
 */
data class VideoItem(
    val videoId: String,
    val title: String,
    val description: String,
    val durationMs: Long,
    val coverUrl: String?,  // 本地缓存路径或null
    val remotePath: String, // WebDAV上的路径
    val metadata: VideoMetadata? = null
) {
    fun getDurationFormatted(): String {
        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }
}
