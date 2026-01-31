package com.yin2hao.myvideos.data.repository

import android.content.Context
import android.util.Base64
import android.util.Log
import com.yin2hao.myvideos.crypto.CryptoManager
import com.yin2hao.myvideos.crypto.StreamCryptoManager
import com.yin2hao.myvideos.data.StreamVideoMetadata
import com.yin2hao.myvideos.data.model.Settings
import com.yin2hao.myvideos.data.model.VideoItem
import com.yin2hao.myvideos.network.WebDAVClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream

/**
 * 视频缓存仓库 - 管理视频的本地缓存
 */
class VideoCacheRepository(
    private val context: Context
) {
    companion object {
        private const val TAG = "VideoCacheRepository"
        private const val CACHE_INDEX_FILE = "cache_index.json"
    }
    
    // 缓存目录
    private val cacheDir = File(context.filesDir, "video_cache")
    private val indexFile = File(cacheDir, CACHE_INDEX_FILE)
    
    // 下载进度
    private val _downloadProgress = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress: StateFlow<DownloadProgress?> = _downloadProgress
    
    data class DownloadProgress(
        val videoId: String,
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val status: DownloadStatus
    )
    
    enum class DownloadStatus {
        DOWNLOADING, COMPLETED, FAILED, CANCELLED
    }
    
    data class CachedVideoInfo(
        val videoId: String,
        val title: String,
        val description: String,
        val durationMs: Long,
        val fileSize: Long,
        val cachedAt: Long,
        val localPath: String,
        val coverPath: String?,
        val tags: List<String>
    )
    
    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
    }
    
    /**
     * 检查视频是否已缓存
     */
    fun isVideoCached(videoId: String): Boolean {
        val videoFile = File(cacheDir, "$videoId.mp4")
        return videoFile.exists() && videoFile.length() > 0
    }
    
    /**
     * 获取缓存视频的本地路径
     */
    fun getCachedVideoPath(videoId: String): String? {
        val videoFile = File(cacheDir, "$videoId.mp4")
        return if (videoFile.exists()) videoFile.absolutePath else null
    }
    
    /**
     * 获取所有已缓存的视频列表
     */
    suspend fun getCachedVideos(): List<CachedVideoInfo> = withContext(Dispatchers.IO) {
        if (!indexFile.exists()) return@withContext emptyList()
        
        try {
            val json = indexFile.readText()
            val jsonArray = JSONArray(json)
            val videos = mutableListOf<CachedVideoInfo>()
            
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val videoId = obj.getString("videoId")
                
                // 验证文件是否存在
                val videoFile = File(cacheDir, "$videoId.mp4")
                if (videoFile.exists()) {
                    val tagsArray = obj.optJSONArray("tags")
                    val tags = mutableListOf<String>()
                    if (tagsArray != null) {
                        for (j in 0 until tagsArray.length()) {
                            tags.add(tagsArray.getString(j))
                        }
                    }
                    
                    videos.add(CachedVideoInfo(
                        videoId = videoId,
                        title = obj.getString("title"),
                        description = obj.optString("description", ""),
                        durationMs = obj.optLong("durationMs", 0),
                        fileSize = obj.optLong("fileSize", videoFile.length()),
                        cachedAt = obj.optLong("cachedAt", 0),
                        localPath = videoFile.absolutePath,
                        coverPath = obj.optString("coverPath", null),
                        tags = tags
                    ))
                }
            }
            
            videos.sortedByDescending { it.cachedAt }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cached videos", e)
            emptyList()
        }
    }
    
    /**
     * 缓存视频到本地（下载并解密）
     */
    suspend fun cacheVideo(
        video: VideoItem,
        settings: Settings
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val videoId = video.videoId
            val videoFile = File(cacheDir, "$videoId.mp4")
            
            // 如果已缓存，直接返回
            if (videoFile.exists() && videoFile.length() > 0) {
                return@withContext Result.success(videoFile.absolutePath)
            }
            
            val webDavClient = WebDAVClient(settings)
            val basePath = settings.remoteBasePath.trimEnd('/') + "/"
            
            _downloadProgress.value = DownloadProgress(
                videoId = videoId,
                progress = 0f,
                downloadedBytes = 0,
                totalBytes = video.originalFileSize,
                status = DownloadStatus.DOWNLOADING
            )
            
            // 1. 下载元数据
            val metaPath = "${basePath}videos/$videoId/meta.bin"
            val encryptedMeta = webDavClient.downloadFile(metaPath).getOrThrow()
            
            // 解密元数据获取视频密钥
            val metadata = StreamVideoMetadata.fromBinary(ByteArrayInputStream(encryptedMeta))
            val masterKey = CryptoManager.deriveKeyFromPassword(settings.masterPassword)
            
            // 解密视频密钥
            val encryptedKeyData = Base64.decode(metadata.encryptedKey, Base64.NO_WRAP)
            val keyIv = encryptedKeyData.copyOfRange(0, 12)
            val keyEncrypted = encryptedKeyData.copyOfRange(12, encryptedKeyData.size)
            val keyIvBase64 = Base64.encodeToString(keyIv, Base64.NO_WRAP)
            val decryptedKeyBytes = CryptoManager.decrypt(keyEncrypted, masterKey, keyIvBase64)
            val videoKey = String(decryptedKeyBytes, Charsets.UTF_8)
            
            // 2. 下载加密视频
            val videoPath = "${basePath}videos/$videoId/video.enc"
            val encryptedVideo = webDavClient.downloadFile(videoPath).getOrThrow()
            
            _downloadProgress.value = DownloadProgress(
                videoId = videoId,
                progress = 0.5f,
                downloadedBytes = encryptedVideo.size.toLong(),
                totalBytes = metadata.encryptedSize,
                status = DownloadStatus.DOWNLOADING
            )
            
            // 3. 解密视频（加密视频包含IV头）
            val tempFile = File(cacheDir, "$videoId.tmp")
            FileOutputStream(tempFile).use { output ->
                StreamCryptoManager.decryptStream(
                    input = ByteArrayInputStream(encryptedVideo),
                    output = output,
                    keyBase64 = videoKey,
                    bufferSize = 65536
                )
            }
            
            // 重命名为最终文件
            tempFile.renameTo(videoFile)
            
            // 4. 缓存封面
            var coverPath: String? = null
            if (video.hasCover) {
                try {
                    val coverRemotePath = "${basePath}covers/$videoId.enc"
                    val encryptedCover = webDavClient.downloadFile(coverRemotePath).getOrNull()
                    if (encryptedCover != null && encryptedCover.size > 12) {
                        val coverIv = encryptedCover.copyOfRange(0, 12)
                        val coverEncrypted = encryptedCover.copyOfRange(12, encryptedCover.size)
                        val coverIvBase64 = Base64.encodeToString(coverIv, Base64.NO_WRAP)
                        val decryptedCover = CryptoManager.decrypt(coverEncrypted, masterKey, coverIvBase64)
                        
                        val coverFile = File(cacheDir, "$videoId.jpg")
                        coverFile.writeBytes(decryptedCover)
                        coverPath = coverFile.absolutePath
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to cache cover", e)
                }
            }
            
            // 5. 更新缓存索引
            updateCacheIndex(CachedVideoInfo(
                videoId = videoId,
                title = video.title,
                description = video.description,
                durationMs = video.durationMs,
                fileSize = videoFile.length(),
                cachedAt = System.currentTimeMillis(),
                localPath = videoFile.absolutePath,
                coverPath = coverPath,
                tags = video.tags
            ))
            
            _downloadProgress.value = DownloadProgress(
                videoId = videoId,
                progress = 1f,
                downloadedBytes = videoFile.length(),
                totalBytes = videoFile.length(),
                status = DownloadStatus.COMPLETED
            )
            
            Result.success(videoFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache video", e)
            _downloadProgress.value = _downloadProgress.value?.copy(status = DownloadStatus.FAILED)
            Result.failure(e)
        } finally {
            // 延迟清除进度
            kotlinx.coroutines.delay(2000)
            _downloadProgress.value = null
        }
    }
    
    /**
     * 删除缓存的视频
     */
    suspend fun deleteCachedVideo(videoId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val videoFile = File(cacheDir, "$videoId.mp4")
            val coverFile = File(cacheDir, "$videoId.jpg")
            
            videoFile.delete()
            coverFile.delete()
            
            // 更新索引
            removeCacheIndex(videoId)
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete cached video", e)
            false
        }
    }
    
    /**
     * 获取缓存占用空间
     */
    fun getCacheSize(): Long {
        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0
    }
    
    /**
     * 清除所有缓存
     */
    suspend fun clearAllCache(): Boolean = withContext(Dispatchers.IO) {
        try {
            cacheDir.listFiles()?.forEach { it.delete() }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear cache", e)
            false
        }
    }
    
    private fun updateCacheIndex(info: CachedVideoInfo) {
        try {
            val videos = mutableListOf<CachedVideoInfo>()
            
            // 加载现有索引
            if (indexFile.exists()) {
                val json = indexFile.readText()
                val jsonArray = JSONArray(json)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    if (obj.getString("videoId") != info.videoId) {
                        val tagsArray = obj.optJSONArray("tags")
                        val tags = mutableListOf<String>()
                        if (tagsArray != null) {
                            for (j in 0 until tagsArray.length()) {
                                tags.add(tagsArray.getString(j))
                            }
                        }
                        videos.add(CachedVideoInfo(
                            videoId = obj.getString("videoId"),
                            title = obj.getString("title"),
                            description = obj.optString("description", ""),
                            durationMs = obj.optLong("durationMs", 0),
                            fileSize = obj.optLong("fileSize", 0),
                            cachedAt = obj.optLong("cachedAt", 0),
                            localPath = obj.getString("localPath"),
                            coverPath = obj.optString("coverPath", null),
                            tags = tags
                        ))
                    }
                }
            }
            
            // 添加新的
            videos.add(info)
            
            // 保存
            val jsonArray = JSONArray()
            videos.forEach { video ->
                val obj = JSONObject().apply {
                    put("videoId", video.videoId)
                    put("title", video.title)
                    put("description", video.description)
                    put("durationMs", video.durationMs)
                    put("fileSize", video.fileSize)
                    put("cachedAt", video.cachedAt)
                    put("localPath", video.localPath)
                    put("coverPath", video.coverPath)
                    put("tags", JSONArray(video.tags))
                }
                jsonArray.put(obj)
            }
            
            indexFile.writeText(jsonArray.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update cache index", e)
        }
    }
    
    private fun removeCacheIndex(videoId: String) {
        try {
            if (!indexFile.exists()) return
            
            val json = indexFile.readText()
            val jsonArray = JSONArray(json)
            val newArray = JSONArray()
            
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                if (obj.getString("videoId") != videoId) {
                    newArray.put(obj)
                }
            }
            
            indexFile.writeText(newArray.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove from cache index", e)
        }
    }
}
