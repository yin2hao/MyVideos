package com.yin2hao.myvideos.video

import android.util.Base64
import android.util.Log
import com.yin2hao.myvideos.crypto.CryptoManager
import com.yin2hao.myvideos.crypto.StreamCryptoManager
import com.yin2hao.myvideos.data.StreamVideoMetadata
import com.yin2hao.myvideos.network.WebDAVClient
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread

/**
 * 流式视频本地代理服务器（不分块版本）
 * 
 * 特点：
 * - 使用AES-CTR模式，支持Range请求
 * - 直接从WebDAV下载加密数据，实时解密后返回
 * - 真正的流式播放，无需完整下载
 */
class StreamLocalProxyServer(
    port: Int,
    private val webDAVClient: WebDAVClient,
    private val masterPassword: String,
    private val basePath: String
) : NanoHTTPD(port) {
    
    companion object {
        private const val TAG = "StreamLocalProxyServer"
        private const val IV_LENGTH = 16
    }
    
    // 缓存元数据
    private val metadataCache = mutableMapOf<String, StreamVideoMetadata>()
    private val decryptedKeyCache = mutableMapOf<String, String>()
    
    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Log.d(TAG, "Request: $uri")
        
        // 路径格式: /stream/{videoId}
        if (!uri.startsWith("/stream/")) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/plain",
                "Not found"
            )
        }
        
        val videoId = uri.removePrefix("/stream/")
        if (videoId.isEmpty()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "text/plain",
                "Missing video ID"
            )
        }
        
        return try {
            serveStreamVideo(session, videoId)
        } catch (e: Exception) {
            Log.e(TAG, "Error serving video: $videoId", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Error: ${e.message}"
            )
        }
    }
    
    private fun serveStreamVideo(session: IHTTPSession, videoId: String): Response {
        // 获取或加载元数据
        val metadata = getMetadata(videoId)
        val videoKey = getDecryptedKey(videoId, metadata)
        
        val originalSize = metadata.originalSize
        val mimeType = metadata.mimeType
        
        // 解析Range请求
        val rangeHeader = session.headers["range"]
        
        return if (rangeHeader != null) {
            serveRangeRequest(videoId, metadata, videoKey, rangeHeader, originalSize, mimeType)
        } else {
            serveFullRequest(videoId, metadata, videoKey, originalSize, mimeType)
        }
    }
    
    /**
     * 处理Range请求（部分内容）
     */
    private fun serveRangeRequest(
        videoId: String,
        metadata: StreamVideoMetadata,
        videoKey: String,
        rangeHeader: String,
        originalSize: Long,
        mimeType: String
    ): Response {
        // 解析Range: bytes=start-end
        val rangeMatch = Regex("bytes=(\\d+)-(\\d*)").find(rangeHeader)
        if (rangeMatch == null) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "text/plain",
                "Invalid range header"
            )
        }
        
        val rangeStart = rangeMatch.groupValues[1].toLong()
        val rangeEnd = if (rangeMatch.groupValues[2].isNotEmpty()) {
            rangeMatch.groupValues[2].toLong()
        } else {
            originalSize - 1
        }
        
        // 确保范围有效
        val actualStart = rangeStart.coerceIn(0, originalSize - 1)
        val actualEnd = rangeEnd.coerceIn(actualStart, originalSize - 1)
        val length = actualEnd - actualStart + 1
        
        Log.d(TAG, "Range request: $actualStart-$actualEnd / $originalSize (length: $length)")
        
        // 创建解密流
        val inputStream = createDecryptingStream(
            videoId = videoId,
            metadata = metadata,
            videoKey = videoKey,
            offset = actualStart,
            length = length
        )
        
        val response = newFixedLengthResponse(
            Response.Status.PARTIAL_CONTENT,
            mimeType,
            inputStream,
            length
        )
        
        response.addHeader("Content-Range", "bytes $actualStart-$actualEnd/$originalSize")
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Content-Length", length.toString())
        
        return response
    }
    
    /**
     * 处理完整请求
     */
    private fun serveFullRequest(
        videoId: String,
        metadata: StreamVideoMetadata,
        videoKey: String,
        originalSize: Long,
        mimeType: String
    ): Response {
        Log.d(TAG, "Full request for video: $videoId, size: $originalSize")
        
        val inputStream = createDecryptingStream(
            videoId = videoId,
            metadata = metadata,
            videoKey = videoKey,
            offset = 0,
            length = originalSize
        )
        
        val response = newFixedLengthResponse(
            Response.Status.OK,
            mimeType,
            inputStream,
            originalSize
        )
        
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Content-Length", originalSize.toString())
        
        return response
    }
    
    /**
     * 创建解密流
     * 
     * 利用AES-CTR的特性，可以从任意位置开始解密
     */
    private fun createDecryptingStream(
        videoId: String,
        metadata: StreamVideoMetadata,
        videoKey: String,
        offset: Long,
        length: Long
    ): InputStream {
        val pipedInput = PipedInputStream(65536)
        val pipedOutput = PipedOutputStream(pipedInput)
        
        thread(name = "DecryptStream-$videoId") {
            try {
                // 计算加密文件中的位置（加上IV偏移）
                val encryptedOffset = IV_LENGTH + offset
                val iv = Base64.decode(metadata.iv, Base64.NO_WRAP)
                
                // 使用Range请求下载加密数据的对应部分
                val videoPath = "${basePath}videos/$videoId/video.enc"
                
                // 分批下载和解密
                val bufferSize = 65536L  // 64KB
                var remaining = length
                var currentOffset = offset
                
                while (remaining > 0) {
                    val toRead = minOf(remaining, bufferSize)
                    val encFileOffset = IV_LENGTH + currentOffset
                    
                    // 下载加密数据片段
                    val encryptedChunk = runBlocking {
                        webDAVClient.downloadFileRange(
                            path = videoPath,
                            start = encFileOffset,
                            end = encFileOffset + toRead - 1
                        ).getOrNull()
                    }
                    
                    if (encryptedChunk == null || encryptedChunk.isEmpty()) {
                        Log.e(TAG, "Failed to download chunk at offset $encFileOffset")
                        break
                    }
                    
                    // 解密这个片段
                    val decrypted = StreamCryptoManager.decryptRange(
                        encryptedData = encryptedChunk,
                        keyBase64 = videoKey,
                        ivBase64 = metadata.iv,
                        offset = currentOffset,
                        length = encryptedChunk.size
                    )
                    
                    pipedOutput.write(decrypted)
                    pipedOutput.flush()
                    
                    currentOffset += decrypted.size
                    remaining -= decrypted.size
                }
                
                pipedOutput.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error in decrypt stream", e)
                try {
                    pipedOutput.close()
                } catch (ignored: Exception) {}
            }
        }
        
        return pipedInput
    }
    
    /**
     * 获取视频元数据（带缓存）
     */
    private fun getMetadata(videoId: String): StreamVideoMetadata {
        return metadataCache.getOrPut(videoId) {
            runBlocking {
                val metaPath = "${basePath}videos/$videoId/meta.bin"
                val metaData = webDAVClient.downloadFile(metaPath).getOrNull()
                    ?: throw IllegalStateException("Cannot download metadata for $videoId")
                StreamVideoMetadata.fromBinary(ByteArrayInputStream(metaData))
            }
        }
    }
    
    /**
     * 获取解密后的视频密钥（带缓存）
     */
    private fun getDecryptedKey(videoId: String, metadata: StreamVideoMetadata): String {
        return decryptedKeyCache.getOrPut(videoId) {
            val masterKey = CryptoManager.deriveKeyFromPassword(masterPassword)
            val encryptedKeyBytes = Base64.decode(metadata.encryptedKey, Base64.NO_WRAP)
            // 使用GCM解密视频密钥（密钥加密用GCM，视频加密用CTR）
            val iv = encryptedKeyBytes.copyOfRange(0, 12)
            val encrypted = encryptedKeyBytes.copyOfRange(12, encryptedKeyBytes.size)
            val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val decryptedKeyBytes = CryptoManager.decrypt(encrypted, masterKey, ivBase64)
            String(decryptedKeyBytes, Charsets.UTF_8)
        }
    }
    
    /**
     * 清除缓存
     */
    fun clearCache() {
        metadataCache.clear()
        decryptedKeyCache.clear()
    }
}
