package com.yin2hao.myvideos.data.repository

import android.content.Context
import android.util.Base64
import com.yin2hao.myvideos.crypto.CryptoManager
import com.yin2hao.myvideos.data.model.Settings
import com.yin2hao.myvideos.data.model.VideoItem
import com.yin2hao.myvideos.data.model.VideoMetadata
import com.yin2hao.myvideos.network.WebDAVClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 视频仓库 - 管理视频列表和元数据
 */
class VideoRepository(
    private val context: Context,
    private val settings: Settings
) {
    private val webdavClient = WebDAVClient(settings)
    private val cacheDir = File(context.cacheDir, "video_covers")
    
    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
    }
    
    /**
     * 获取所有视频列表
     */
    suspend fun getVideoList(): Result<List<VideoItem>> = withContext(Dispatchers.IO) {
        try {
            // 列出远程目录
            val files = webdavClient.listDirectory(settings.remoteBasePath).getOrThrow()
            
            // 过滤出视频目录（只保留目录）
            val videoDirs = files.filter { it.isDirectory }
            
            val videos = mutableListOf<VideoItem>()
            
            for (dir in videoDirs) {
                try {
                    val videoId = dir.name
                    val metadata = loadVideoMetadata(videoId)
                    
                    if (metadata != null) {
                        // 尝试加载封面
                        val coverPath = loadCover(videoId, metadata)
                        
                        videos.add(VideoItem(
                            videoId = videoId,
                            title = metadata.title,
                            description = metadata.description,
                            durationMs = metadata.durationMs,
                            coverUrl = coverPath,
                            remotePath = "${settings.remoteBasePath}$videoId/",
                            metadata = metadata
                        ))
                    }
                } catch (e: Exception) {
                    // 跳过无法解析的视频
                    e.printStackTrace()
                }
            }
            
            Result.success(videos.sortedByDescending { it.metadata?.createdAt ?: 0 })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 加载视频元数据
     */
    suspend fun loadVideoMetadata(videoId: String): VideoMetadata? = withContext(Dispatchers.IO) {
        try {
            val metaPath = "${settings.remoteBasePath}$videoId/meta.bin"
            val encryptedData = webdavClient.downloadFile(metaPath).getOrNull() ?: return@withContext null
            
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
     * 加载视频封面到本地缓存
     * @return 本地文件路径
     */
    private suspend fun loadCover(videoId: String, metadata: VideoMetadata): String? = withContext(Dispatchers.IO) {
        try {
            val localCover = File(cacheDir, "$videoId.jpg")
            
            // 如果已缓存，直接返回
            if (localCover.exists()) {
                return@withContext localCover.absolutePath
            }
            
            // 下载加密的封面
            val coverPath = "${settings.remoteBasePath}$videoId/cover.enc"
            val encryptedCover = webdavClient.downloadFile(coverPath).getOrNull() ?: return@withContext null
            
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
     * 刷新封面缓存
     */
    suspend fun refreshCover(videoId: String, metadata: VideoMetadata): String? {
        // 删除旧缓存
        val localCover = File(cacheDir, "$videoId.jpg")
        if (localCover.exists()) {
            localCover.delete()
        }
        return loadCover(videoId, metadata)
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
            val videoPath = "${settings.remoteBasePath}$videoId/"
            webdavClient.delete(videoPath)
            
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
}
