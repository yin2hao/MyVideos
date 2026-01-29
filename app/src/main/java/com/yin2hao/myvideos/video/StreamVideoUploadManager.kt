package com.yin2hao.myvideos.video

import android.content.ContentResolver
import android.net.Uri
import com.yin2hao.myvideos.crypto.CryptoManager
import com.yin2hao.myvideos.crypto.StreamCryptoManager
import com.yin2hao.myvideos.data.StreamVideoMetadata
import com.yin2hao.myvideos.data.model.VideoIndex
import com.yin2hao.myvideos.data.model.VideoIndexEntry
import com.yin2hao.myvideos.network.WebDAVClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * 流式视频上传管理器（不分块版本）
 * 
 * 特点：
 * - 使用AES-CTR模式加密整个文件
 * - 支持Range请求实现流式播放
 * - 上传单个加密文件而非多个分块
 */
class StreamVideoUploadManager(
    private val webDAVClient: WebDAVClient,
    private val contentResolver: ContentResolver,
    private val masterPassword: String
) {
    data class UploadProgress(
        val videoId: String,
        val phase: String,      // "encrypting", "uploading", "finishing"
        val progress: Float,    // 0.0 - 1.0
        val bytesProcessed: Long,
        val totalBytes: Long
    )
    
    private val _uploadProgress = MutableStateFlow<UploadProgress?>(null)
    val uploadProgress: StateFlow<UploadProgress?> = _uploadProgress
    
    /**
     * 上传视频（流式加密版本）
     * 
     * @param videoUri 视频文件URI
     * @param title 视频标题
     * @param description 视频描述
     * @param masterPassword 主密码
     * @param basePath WebDAV基础路径
     * @param onExtractCover 提取封面的回调
     */
    suspend fun uploadVideo(
        videoUri: Uri,
        title: String,
        description: String,
        masterPassword: String,
        basePath: String,
        onExtractCover: suspend (Uri) -> ByteArray?
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val videoId = UUID.randomUUID().toString()
            
            // 获取文件信息
            val fileSize = contentResolver.openInputStream(videoUri)?.use {
                it.available().toLong()
            } ?: contentResolver.query(videoUri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex("_size")
                    if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
                } else 0L
            } ?: throw Exception("无法获取文件大小")
            
            val mimeType = contentResolver.getType(videoUri) ?: "video/mp4"
            
            // 获取实际文件大小
            val actualFileSize = contentResolver.openInputStream(videoUri)?.use { input ->
                var total = 0L
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    total += read
                }
                total
            } ?: fileSize
            
            _uploadProgress.value = UploadProgress(
                videoId = videoId,
                phase = "encrypting",
                progress = 0f,
                bytesProcessed = 0,
                totalBytes = actualFileSize
            )
            
            // 确保目录存在
            ensureDirectories(basePath, videoId)
            
            // 生成视频加密密钥
            val videoKey = StreamCryptoManager.generateRandomKey()
            
            // 加密视频数据
            val encryptedVideoData = ByteArrayOutputStream()
            var iv: String
            
            contentResolver.openInputStream(videoUri)?.use { input ->
                iv = StreamCryptoManager.encryptStream(
                    input = input,
                    output = encryptedVideoData,
                    keyBase64 = videoKey,
                    bufferSize = 65536
                )
                
                _uploadProgress.value = UploadProgress(
                    videoId = videoId,
                    phase = "encrypting",
                    progress = 1f,
                    bytesProcessed = actualFileSize,
                    totalBytes = actualFileSize
                )
            } ?: throw Exception("无法读取视频文件")
            
            val encryptedData = encryptedVideoData.toByteArray()
            
            _uploadProgress.value = UploadProgress(
                videoId = videoId,
                phase = "uploading",
                progress = 0f,
                bytesProcessed = 0,
                totalBytes = encryptedData.size.toLong()
            )
            
            // 上传加密视频
            val videoPath = "${basePath}videos/$videoId/video.enc"
            webDAVClient.uploadFile(videoPath, encryptedData).getOrThrow()
            
            _uploadProgress.value = UploadProgress(
                videoId = videoId,
                phase = "uploading",
                progress = 0.8f,
                bytesProcessed = encryptedData.size.toLong(),
                totalBytes = encryptedData.size.toLong()
            )
            
            // 使用主密码加密视频密钥（使用GCM模式）
            val masterKey = CryptoManager.deriveKeyFromPassword(masterPassword)
            val keyIV = CryptoManager.generateIV()
            val encryptedVideoKeyData = CryptoManager.encrypt(videoKey.toByteArray(Charsets.UTF_8), masterKey, keyIV)
            
            // 合并 IV + 加密数据
            val keyIVBytes = android.util.Base64.decode(keyIV, android.util.Base64.NO_WRAP)
            val encryptedVideoKey = ByteArray(keyIVBytes.size + encryptedVideoKeyData.size)
            System.arraycopy(keyIVBytes, 0, encryptedVideoKey, 0, keyIVBytes.size)
            System.arraycopy(encryptedVideoKeyData, 0, encryptedVideoKey, keyIVBytes.size, encryptedVideoKeyData.size)
            
            val encryptedKeyBase64 = android.util.Base64.encodeToString(encryptedVideoKey, android.util.Base64.NO_WRAP)
            
            // 创建并上传元数据
            val metadata = StreamVideoMetadata(
                originalSize = actualFileSize,
                encryptedSize = encryptedData.size.toLong(),
                iv = iv,
                encryptedKey = encryptedKeyBase64,
                mimeType = mimeType
            )
            
            val metadataPath = "${basePath}videos/$videoId/meta.bin"
            webDAVClient.uploadFile(metadataPath, metadata.toBinary()).getOrThrow()
            
            // 提取并上传封面
            var hasCover = false
            try {
                val coverData = onExtractCover(videoUri)
                if (coverData != null && coverData.isNotEmpty()) {
                    val coverIV = CryptoManager.generateIV()
                    val encryptedCoverData = CryptoManager.encrypt(coverData, masterKey, coverIV)
                    
                    // 合并 IV + 加密数据
                    val coverIVBytes = android.util.Base64.decode(coverIV, android.util.Base64.NO_WRAP)
                    val encryptedCover = ByteArray(coverIVBytes.size + encryptedCoverData.size)
                    System.arraycopy(coverIVBytes, 0, encryptedCover, 0, coverIVBytes.size)
                    System.arraycopy(encryptedCoverData, 0, encryptedCover, coverIVBytes.size, encryptedCoverData.size)
                    
                    val coverPath = "${basePath}covers/$videoId.enc"
                    webDAVClient.uploadFile(coverPath, encryptedCover).getOrNull()
                    hasCover = true
                }
            } catch (e: Exception) {
                // 封面提取失败不影响上传
                e.printStackTrace()
            }
            
            // 更新索引
            _uploadProgress.value = UploadProgress(
                videoId = videoId,
                phase = "finishing",
                progress = 0.9f,
                bytesProcessed = encryptedData.size.toLong(),
                totalBytes = encryptedData.size.toLong()
            )
            
            updateIndex(
                basePath = basePath,
                videoId = videoId,
                title = title,
                description = description,
                fileSize = actualFileSize,
                mimeType = mimeType,
                hasCover = hasCover,
                isStream = true,  // 标记为流式加密
                masterPassword = masterPassword
            )
            
            _uploadProgress.value = UploadProgress(
                videoId = videoId,
                phase = "finishing",
                progress = 1f,
                bytesProcessed = encryptedData.size.toLong(),
                totalBytes = encryptedData.size.toLong()
            )
            
            Result.success(videoId)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        } finally {
            _uploadProgress.value = null
        }
    }
    
    private suspend fun ensureDirectories(basePath: String, videoId: String) {
        webDAVClient.createDirectory(basePath)
        webDAVClient.createDirectory("${basePath}videos/")
        webDAVClient.createDirectory("${basePath}videos/$videoId/")
        webDAVClient.createDirectory("${basePath}covers/")
    }
    
    private suspend fun updateIndex(
        basePath: String,
        videoId: String,
        title: String,
        description: String,
        fileSize: Long,
        mimeType: String,
        hasCover: Boolean,
        isStream: Boolean,
        masterPassword: String
    ) {
        val indexPath = "${basePath}index.bin"
        val masterKey = CryptoManager.deriveKeyFromPassword(masterPassword)
        
        // 尝试加载现有索引
        val existingVideos = try {
            val encryptedIndex = webDAVClient.downloadFile(indexPath).getOrNull()
            if (encryptedIndex != null && encryptedIndex.size > 12) {
                // 解密索引
                val iv = encryptedIndex.copyOfRange(0, 12)
                val encrypted = encryptedIndex.copyOfRange(12, encryptedIndex.size)
                val ivBase64 = android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP)
                val decrypted = CryptoManager.decrypt(encrypted, masterKey, ivBase64)
                VideoIndex.fromBytes(decrypted).videos.toMutableList()
            } else {
                mutableListOf()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            mutableListOf()
        }
        
        // 添加新视频条目
        val newEntry = VideoIndexEntry(
            videoId = videoId,
            title = title,
            description = description,
            durationMs = 0L,  // 暂不提取时长
            originalFileSize = fileSize,
            createdAt = System.currentTimeMillis(),
            hasCover = hasCover,
            mimeType = mimeType,
            isStream = isStream
        )
        
        existingVideos.add(0, newEntry)
        
        // 加密并上传索引
        val newIndex = VideoIndex(existingVideos)
        val indexIv = CryptoManager.generateIV()
        val indexBytes = newIndex.toBytes()
        val encryptedIndex = CryptoManager.encrypt(indexBytes, masterKey, indexIv)
        
        // 合并 IV + 加密数据
        val ivBytes = android.util.Base64.decode(indexIv, android.util.Base64.NO_WRAP)
        val finalIndex = ByteArray(ivBytes.size + encryptedIndex.size)
        System.arraycopy(ivBytes, 0, finalIndex, 0, ivBytes.size)
        System.arraycopy(encryptedIndex, 0, finalIndex, ivBytes.size, encryptedIndex.size)
        
        webDAVClient.uploadFile(indexPath, finalIndex).getOrThrow()
    }
}
