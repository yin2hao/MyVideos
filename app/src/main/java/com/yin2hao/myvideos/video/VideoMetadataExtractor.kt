package com.yin2hao.myvideos.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * 视频信息提取器
 */
class VideoMetadataExtractor(private val context: Context) {
    
    /**
     * 提取视频元信息
     */
    suspend fun extractMetadata(uri: Uri): VideoInfo = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "video/mp4"
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
            
            // 获取文件大小
            val fileSize = getFileSize(uri)
            
            VideoInfo(
                duration = duration,
                width = width,
                height = height,
                mimeType = mimeType,
                bitrate = bitrate,
                fileSize = fileSize
            )
        } finally {
            retriever.release()
        }
    }
    
    /**
     * 提取视频封面
     * @return JPEG格式的字节数组
     */
    suspend fun extractCover(uri: Uri, timeUs: Long = 0L): ByteArray? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            
            // 尝试获取指定时间的帧，如果失败则获取第一帧
            val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
            
            bitmap?.let {
                val outputStream = ByteArrayOutputStream()
                // 压缩为JPEG，质量85%
                it.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
                it.recycle()
                outputStream.toByteArray()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            retriever.release()
        }
    }
    
    /**
     * 提取并保存封面到文件
     */
    suspend fun extractCoverToFile(uri: Uri, outputFile: File, timeUs: Long = 0L): Boolean = withContext(Dispatchers.IO) {
        val coverData = extractCover(uri, timeUs)
        if (coverData != null) {
            try {
                FileOutputStream(outputFile).use {
                    it.write(coverData)
                }
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        } else {
            false
        }
    }
    
    private fun getFileSize(uri: Uri): Long {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use {
                it.statSize
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}

/**
 * 视频基本信息
 */
data class VideoInfo(
    val duration: Long,      // 毫秒
    val width: Int,
    val height: Int,
    val mimeType: String,
    val bitrate: Int,
    val fileSize: Long       // 字节
)
