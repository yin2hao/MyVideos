package com.yin2hao.myvideos.video

import android.content.Context
import android.net.Uri
import com.yin2hao.myvideos.crypto.CryptoManager
import com.yin2hao.myvideos.data.model.ChunkInfo
import com.yin2hao.myvideos.data.model.Settings
import com.yin2hao.myvideos.data.model.UploadState
import com.yin2hao.myvideos.data.model.VideoIndex
import com.yin2hao.myvideos.data.model.VideoIndexEntry
import com.yin2hao.myvideos.data.model.VideoMetadata
import com.yin2hao.myvideos.network.WebDAVClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 视频上传管理器
 * 
 * 新的目录结构：
 * /MyVideos/
 *   ├── index.bin           ← 加密的总索引
 *   ├── covers/
 *   │   └── {video_id}.enc  ← 封面单独存放
 *   └── videos/
 *       └── {video_id}/
 *           ├── meta.bin    ← 完整元数据
 *           └── chunk_xxxx.enc
 */
class VideoUploadManager(
    private val context: Context,
    private val settings: Settings
) {
    private val webdavClient = WebDAVClient(settings)
    private val metadataExtractor = VideoMetadataExtractor(context)
    private val chunker = VideoChunker(context)
    
    private val _uploadState = MutableStateFlow<UploadState>(UploadState.Idle)
    val uploadState: StateFlow<UploadState> = _uploadState
    
    // 路径常量
    private val basePath get() = settings.remoteBasePath.trimEnd('/') + "/"
    private val indexPath get() = "${basePath}index.bin"
    private val coversPath get() = "${basePath}covers/"
    private val videosPath get() = "${basePath}videos/"
    
    /**
     * 上传视频
     * @param videoUri 本地视频URI
     * @param title 视频标题
     * @param description 视频描述
     * @return 成功返回视频ID，失败返回null
     */
    suspend fun uploadVideo(
        videoUri: Uri,
        title: String,
        description: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            _uploadState.value = UploadState.Preparing
            
            // 生成视频ID
            val videoId = UUID.randomUUID().toString()
            val videoPath = "${videosPath}$videoId/"
            
            // 创建远程目录结构
            webdavClient.createDirectory(basePath).getOrThrow()
            webdavClient.createDirectory(coversPath).getOrThrow()
            webdavClient.createDirectory(videosPath).getOrThrow()
            webdavClient.createDirectory(videoPath).getOrThrow()
            
            // 提取视频信息
            val videoInfo = metadataExtractor.extractMetadata(videoUri)
            
            // 生成加密密钥
            val videoKey = CryptoManager.generateRandomKey()
            val videoIv = CryptoManager.generateIV()
            val coverKey = CryptoManager.generateRandomKey()
            val coverIv = CryptoManager.generateIV()
            
            // 分块并加密上传视频
            val chunkInfos = mutableListOf<ChunkInfo>()
            val chunkSize = settings.getChunkSizeBytes()
            val totalChunks = ((videoInfo.fileSize + chunkSize - 1) / chunkSize).toInt()
            var currentChunk = 0
            
            chunker.chunkVideo(videoUri, chunkSize, object : VideoChunker.ChunkCallback {
                override suspend fun onChunk(chunk: VideoChunker.ChunkResult) {
                    _uploadState.value = UploadState.Encrypting(
                        progress = (currentChunk + 0.5f) / totalChunks,
                        currentChunk = currentChunk + 1,
                        totalChunks = totalChunks
                    )
                    
                    // 加密分块
                    val encryptedData = CryptoManager.encryptChunk(chunk.data, videoKey)
                    
                    _uploadState.value = UploadState.Uploading(
                        progress = currentChunk.toFloat() / totalChunks,
                        currentChunk = currentChunk + 1,
                        totalChunks = totalChunks
                    )
                    
                    // 上传分块
                    val chunkFilename = "chunk_${String.format("%04d", chunk.index)}.enc"
                    val chunkPath = "$videoPath$chunkFilename"
                    
                    webdavClient.uploadFile(chunkPath, encryptedData).getOrThrow()
                    
                    chunkInfos.add(ChunkInfo(
                        index = chunk.index,
                        originalSize = chunk.originalSize.toLong(),
                        encryptedSize = encryptedData.size.toLong(),
                        filename = chunkFilename
                    ))
                    
                    currentChunk++
                }
                
                override fun onProgress(current: Int, total: Int) {
                    // 进度已在onChunk中更新
                }
            })
            
            // 提取并加密上传封面到 covers 目录
            _uploadState.value = UploadState.UploadingCover
            val coverData = metadataExtractor.extractCover(videoUri)
            val hasCover = if (coverData != null) {
                val encryptedCover = CryptoManager.encrypt(coverData, coverKey, coverIv)
                webdavClient.uploadFile("$coversPath$videoId.enc", encryptedCover).getOrThrow()
                true
            } else {
                false
            }
            
            // 创建并加密上传元数据到 videos/{videoId}/ 目录
            _uploadState.value = UploadState.UploadingMetadata
            val createdAt = System.currentTimeMillis()
            val metadata = VideoMetadata(
                videoId = videoId,
                title = title,
                description = description,
                durationMs = videoInfo.duration,
                originalFileSize = videoInfo.fileSize,
                totalChunks = totalChunks,
                chunkSize = chunkSize.toInt(),
                encryptionKey = videoKey,
                iv = videoIv,
                coverEncryptionKey = coverKey,
                coverIv = coverIv,
                createdAt = createdAt,
                mimeType = videoInfo.mimeType,
                chunks = chunkInfos.sortedBy { it.index }
            )
            
            // 使用主密码加密元数据
            val masterKey = CryptoManager.deriveKeyFromPassword(settings.masterPassword)
            val metaIv = CryptoManager.generateIV()
            val metadataBytes = metadata.toBytes()
            val encryptedMetadata = CryptoManager.encrypt(metadataBytes, masterKey, metaIv)
            
            // 将IV前置到加密数据
            val ivBytes = android.util.Base64.decode(metaIv, android.util.Base64.NO_WRAP)
            val finalMetadata = ByteArray(ivBytes.size + encryptedMetadata.size)
            System.arraycopy(ivBytes, 0, finalMetadata, 0, ivBytes.size)
            System.arraycopy(encryptedMetadata, 0, finalMetadata, ivBytes.size, encryptedMetadata.size)
            
            webdavClient.uploadFile("${videoPath}meta.bin", finalMetadata).getOrThrow()
            
            // 更新总索引文件
            updateIndex(VideoIndexEntry(
                videoId = videoId,
                title = title,
                description = description,
                durationMs = videoInfo.duration,
                originalFileSize = videoInfo.fileSize,
                createdAt = createdAt,
                hasCover = hasCover,
                mimeType = videoInfo.mimeType
            ))
            
            _uploadState.value = UploadState.Success
            videoId
            
        } catch (e: Exception) {
            e.printStackTrace()
            _uploadState.value = UploadState.Error(e.message ?: "上传失败")
            null
        }
    }
    
    /**
     * 更新总索引文件
     */
    private suspend fun updateIndex(newEntry: VideoIndexEntry) {
        val masterKey = CryptoManager.deriveKeyFromPassword(settings.masterPassword)
        
        // 尝试加载现有索引
        val currentIndex = try {
            val encryptedIndex = webdavClient.downloadFile(indexPath).getOrNull()
            if (encryptedIndex != null && encryptedIndex.size > 12) {
                val iv = encryptedIndex.copyOfRange(0, 12)
                val encrypted = encryptedIndex.copyOfRange(12, encryptedIndex.size)
                val ivBase64 = android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP)
                val decrypted = CryptoManager.decrypt(encrypted, masterKey, ivBase64)
                VideoIndex.fromBytes(decrypted)
            } else {
                VideoIndex(emptyList())
            }
        } catch (e: Exception) {
            VideoIndex(emptyList())
        }
        
        // 添加新条目
        val updatedIndex = currentIndex.addVideo(newEntry)
        
        // 加密并上传
        val indexIv = CryptoManager.generateIV()
        val indexBytes = updatedIndex.toBytes()
        val encryptedIndex = CryptoManager.encrypt(indexBytes, masterKey, indexIv)
        
        val ivBytes = android.util.Base64.decode(indexIv, android.util.Base64.NO_WRAP)
        val finalIndex = ByteArray(ivBytes.size + encryptedIndex.size)
        System.arraycopy(ivBytes, 0, finalIndex, 0, ivBytes.size)
        System.arraycopy(encryptedIndex, 0, finalIndex, ivBytes.size, encryptedIndex.size)
        
        webdavClient.uploadFile(indexPath, finalIndex).getOrThrow()
    }
    
    fun resetState() {
        _uploadState.value = UploadState.Idle
    }
}
