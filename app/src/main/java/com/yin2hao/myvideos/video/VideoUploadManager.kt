package com.yin2hao.myvideos.video

import android.content.Context
import android.net.Uri
import com.yin2hao.myvideos.crypto.CryptoManager
import com.yin2hao.myvideos.data.model.ChunkInfo
import com.yin2hao.myvideos.data.model.Settings
import com.yin2hao.myvideos.data.model.UploadState
import com.yin2hao.myvideos.data.model.VideoMetadata
import com.yin2hao.myvideos.network.WebDAVClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 视频上传管理器
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
            val remotePath = "${settings.remoteBasePath}$videoId/"
            
            // 创建远程目录
            webdavClient.createDirectory(settings.remoteBasePath).getOrThrow()
            webdavClient.createDirectory(remotePath).getOrThrow()
            
            // 提取视频信息
            val videoInfo = metadataExtractor.extractMetadata(videoUri)
            
            // 生成加密密钥
            val videoKey = CryptoManager.generateRandomKey()
            val videoIv = CryptoManager.generateIV()
            val coverKey = CryptoManager.generateRandomKey()
            val coverIv = CryptoManager.generateIV()
            
            // 分块并加密上传视频
            val chunkInfos = mutableListOf<ChunkInfo>()
            var totalChunks = 0
            
            // 计算总块数
            val chunkSize = settings.getChunkSizeBytes()
            totalChunks = ((videoInfo.fileSize + chunkSize - 1) / chunkSize).toInt()
            
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
                    val chunkPath = "$remotePath$chunkFilename"
                    
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
            
            // 提取并加密上传封面
            _uploadState.value = UploadState.UploadingCover
            val coverData = metadataExtractor.extractCover(videoUri)
            if (coverData != null) {
                val encryptedCover = CryptoManager.encrypt(coverData, coverKey, coverIv)
                webdavClient.uploadFile("${remotePath}cover.enc", encryptedCover).getOrThrow()
            }
            
            // 创建并加密上传元数据
            _uploadState.value = UploadState.UploadingMetadata
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
                createdAt = System.currentTimeMillis(),
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
            
            webdavClient.uploadFile("${remotePath}meta.bin", finalMetadata).getOrThrow()
            
            _uploadState.value = UploadState.Success
            videoId
            
        } catch (e: Exception) {
            e.printStackTrace()
            _uploadState.value = UploadState.Error(e.message ?: "上传失败")
            null
        }
    }
    
    fun resetState() {
        _uploadState.value = UploadState.Idle
    }
}
