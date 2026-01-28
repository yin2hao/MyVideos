package com.yin2hao.myvideos.data.repository

import android.content.Context
import android.util.Base64
import com.yin2hao.myvideos.crypto.CryptoManager
import com.yin2hao.myvideos.data.model.Settings
import com.yin2hao.myvideos.data.model.VideoIndex
import com.yin2hao.myvideos.data.model.VideoIndexEntry
import com.yin2hao.myvideos.data.model.VideoItem
import com.yin2hao.myvideos.data.model.VideoMetadata
import com.yin2hao.myvideos.network.WebDAVClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 视频仓库 - 管理视频列表和元数据
 * 
 * 目录结构：
 * /MyVideos/
 *   ├── index.bin           ← 加密的总索引（1次请求获取所有视频信息）
 *   ├── covers/
 *   │   └── {video_id}.enc  ← 封面（懒加载）
 *   └── videos/
 *       └── {video_id}/
 *           ├── meta.bin    ← 完整元数据（播放时加载）
 *           └── chunk_xxxx.enc
 */
class VideoRepository(
    private val context: Context,
    private val settings: Settings
) {
    private val webdavClient = WebDAVClient(settings)
    private val cacheDir = File(context.cacheDir, "video_covers")
    
    // 路径常量
    private val basePath get() = settings.remoteBasePath.trimEnd('/') + "/"
    private val indexPath get() = "${basePath}index.bin"
    private val coversPath get() = "${basePath}covers/"
    private val videosPath get() = "${basePath}videos/"
    
    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
    }
    
    /**
     * 获取所有视频列表 - 只需1次请求下载index.bin
     */
    suspend fun getVideoList(): Result<List<VideoItem>> = withContext(Dispatchers.IO) {
        try {
            val masterKey = CryptoManager.deriveKeyFromPassword(settings.masterPassword)
            
            // 下载并解密索引文件
            val encryptedIndex = webdavClient.downloadFile(indexPath).getOrNull()
            
            if (encryptedIndex == null || encryptedIndex.size <= 12) {
                // 索引文件不存在或为空，返回空列表
                return@withContext Result.success(emptyList())
            }
            
            // 解密索引
            val iv = encryptedIndex.copyOfRange(0, 12)
            val encrypted = encryptedIndex.copyOfRange(12, encryptedIndex.size)
            val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val decrypted = CryptoManager.decrypt(encrypted, masterKey, ivBase64)
            
            val index = VideoIndex.fromBytes(decrypted)
            
            // 转换为VideoItem列表
            val videos = index.videos.map { entry ->
                VideoItem(
                    videoId = entry.videoId,
                    title = entry.title,
                    description = entry.description,
                    durationMs = entry.durationMs,
                    coverUrl = getCachedCoverPath(entry.videoId), // 先检查本地缓存
                    remotePath = "${videosPath}${entry.videoId}/",
                    metadata = null, // 播放时再加载完整元数据
                    hasCover = entry.hasCover,
                    originalFileSize = entry.originalFileSize,
                    createdAt = entry.createdAt
                )
            }
            
            Result.success(videos.sortedByDescending { it.createdAt })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 获取本地缓存的封面路径（如果存在）
     */
    private fun getCachedCoverPath(videoId: String): String? {
        val localCover = File(cacheDir, "$videoId.jpg")
        return if (localCover.exists()) localCover.absolutePath else null
    }
    
    /**
     * 懒加载封面 - 按需调用
     * @return 本地文件路径
     */
    suspend fun loadCover(videoId: String): String? = withContext(Dispatchers.IO) {
        try {
            val localCover = File(cacheDir, "$videoId.jpg")
            
            // 如果已缓存，直接返回
            if (localCover.exists()) {
                return@withContext localCover.absolutePath
            }
            
            // 先加载视频元数据获取封面密钥
            val metadata = loadVideoMetadata(videoId) ?: return@withContext null
            
            // 下载加密的封面
            val coverPath = "$coversPath$videoId.enc"
            val encryptedCover = webdavClient.downloadFile(coverPath).getOrNull() 
                ?: return@withContext null
            
            // 解密
            val decryptedCover = CryptoManager.decrypt(
                encryptedCover,
                metadata.coverEncryptionKey,
                metadata.coverIv
            )
            
            // 保存到本地
            localCover.writeBytes(decryptedCover)
            
            localCover.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 加载视频元数据（播放时需要）
     */
    suspend fun loadVideoMetadata(videoId: String): VideoMetadata? = withContext(Dispatchers.IO) {
        try {
            val metaPath = "${videosPath}$videoId/meta.bin"
            val encryptedData = webdavClient.downloadFile(metaPath).getOrNull() 
                ?: return@withContext null
            
            // 提取IV（前12字节）
            if (encryptedData.size < 12) return@withContext null
            
            val iv = encryptedData.copyOfRange(0, 12)
            val encrypted = encryptedData.copyOfRange(12, encryptedData.size)
            
            // 使用主密码解密
            val masterKey = CryptoManager.deriveKeyFromPassword(settings.masterPassword)
            val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            
            val decryptedBytes = CryptoManager.decrypt(encrypted, masterKey, ivBase64)
            
            VideoMetadata.fromBytes(decryptedBytes)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 刷新封面缓存
     */
    suspend fun refreshCover(videoId: String): String? {
        // 删除旧缓存
        val localCover = File(cacheDir, "$videoId.jpg")
        if (localCover.exists()) {
            localCover.delete()
        }
        return loadCover(videoId)
    }
    
    /**
     * 清除所有封面缓存
     */
    fun clearCoverCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }
    
    /**
     * 删除视频
     */
    suspend fun deleteVideo(videoId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            // 删除视频目录
            val videoPath = "${videosPath}$videoId/"
            webdavClient.delete(videoPath)
            
            // 删除封面
            val coverPath = "$coversPath$videoId.enc"
            webdavClient.delete(coverPath)
            
            // 更新索引
            removeFromIndex(videoId)
            
            // 删除本地封面缓存
            val localCover = File(cacheDir, "$videoId.jpg")
            if (localCover.exists()) {
                localCover.delete()
            }
            
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 从索引中移除视频
     */
    private suspend fun removeFromIndex(videoId: String) {
        try {
            val masterKey = CryptoManager.deriveKeyFromPassword(settings.masterPassword)
            
            // 加载现有索引
            val encryptedIndex = webdavClient.downloadFile(indexPath).getOrNull() ?: return
            if (encryptedIndex.size <= 12) return
            
            val iv = encryptedIndex.copyOfRange(0, 12)
            val encrypted = encryptedIndex.copyOfRange(12, encryptedIndex.size)
            val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val decrypted = CryptoManager.decrypt(encrypted, masterKey, ivBase64)
            
            val currentIndex = VideoIndex.fromBytes(decrypted)
            
            // 移除视频
            val updatedIndex = currentIndex.removeVideo(videoId)
            
            // 加密并上传
            val newIv = CryptoManager.generateIV()
            val indexBytes = updatedIndex.toBytes()
            val encryptedNew = CryptoManager.encrypt(indexBytes, masterKey, newIv)
            
            val ivBytes = Base64.decode(newIv, Base64.NO_WRAP)
            val finalIndex = ByteArray(ivBytes.size + encryptedNew.size)
            System.arraycopy(ivBytes, 0, finalIndex, 0, ivBytes.size)
            System.arraycopy(encryptedNew, 0, finalIndex, ivBytes.size, encryptedNew.size)
            
            webdavClient.uploadFile(indexPath, finalIndex)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
