package com.yin2hao.myvideos.player

import android.util.Log
import com.yin2hao.myvideos.crypto.CryptoManager
import com.yin2hao.myvideos.data.model.Settings
import com.yin2hao.myvideos.data.model.VideoMetadata
import com.yin2hao.myvideos.network.WebDAVClient
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 本地代理服务器，用于边下边播
 * 
 * 工作原理：
 * 1. ExoPlayer请求视频数据
 * 2. 代理服务器根据请求的Range头，计算需要哪些分块
 * 3. 从WebDAV下载对应的加密分块
 * 4. 解密后返回给ExoPlayer
 */
class LocalProxyServer(
    private val settings: Settings,
    port: Int = 8888
) : NanoHTTPD(port) {
    
    companion object {
        private const val TAG = "LocalProxyServer"
        private var instance: LocalProxyServer? = null
        
        fun getInstance(settings: Settings, port: Int = 8888): LocalProxyServer {
            return instance ?: synchronized(this) {
                instance ?: LocalProxyServer(settings, port).also { instance = it }
            }
        }
        
        fun stopInstance() {
            instance?.stop()
            instance = null
        }
    }
    
    private val webdavClient = WebDAVClient(settings)
    private val metadataCache = ConcurrentHashMap<String, VideoMetadata>()
    private val chunkCache = ConcurrentHashMap<String, ByteArray>() // 简单的分块缓存
    
    private val maxCacheSize = 10 // 最多缓存10个分块
    
    fun registerVideo(videoId: String, metadata: VideoMetadata) {
        metadataCache[videoId] = metadata
    }
    
    fun unregisterVideo(videoId: String) {
        metadataCache.remove(videoId)
        // 清除相关缓存
        chunkCache.keys.filter { it.startsWith(videoId) }.forEach {
            chunkCache.remove(it)
        }
    }
    
    /**
     * 获取视频的代理URL
     */
    fun getProxyUrl(videoId: String): String {
        return "http://127.0.0.1:$listeningPort/video/$videoId"
    }
    
    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Log.d(TAG, "Request: $uri")
        
        // 解析请求路径 /video/{videoId}
        val pathParts = uri.split("/").filter { it.isNotEmpty() }
        if (pathParts.size < 2 || pathParts[0] != "video") {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
        
        val videoId = pathParts[1]
        val metadata = metadataCache[videoId]
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Video not found")
        
        // 解析Range头
        val rangeHeader = session.headers["range"]
        val totalSize = metadata.originalFileSize
        
        return if (rangeHeader != null) {
            handleRangeRequest(videoId, metadata, rangeHeader, totalSize)
        } else {
            // 没有Range头，返回整个视频（但实际上ExoPlayer会使用Range请求）
            handleFullRequest(videoId, metadata, totalSize)
        }
    }
    
    private fun handleRangeRequest(
        videoId: String,
        metadata: VideoMetadata,
        rangeHeader: String,
        totalSize: Long
    ): Response {
        try {
            // 解析 Range: bytes=start-end
            val rangeValue = rangeHeader.replace("bytes=", "")
            val rangeParts = rangeValue.split("-")
            
            val start = rangeParts[0].toLongOrNull() ?: 0L
            val end = if (rangeParts.size > 1 && rangeParts[1].isNotEmpty()) {
                rangeParts[1].toLongOrNull() ?: (totalSize - 1)
            } else {
                // 如果没有指定end，返回一个合理的范围（比如一个分块的大小）
                minOf(start + metadata.chunkSize - 1, totalSize - 1)
            }
            
            val requestedLength = end - start + 1
            
            Log.d(TAG, "Range request: $start-$end (length: $requestedLength)")
            
            // 获取数据
            val data = getVideoData(videoId, metadata, start, end)
            
            if (data != null) {
                val inputStream = ByteArrayInputStream(data)
                val response = newFixedLengthResponse(
                    Response.Status.PARTIAL_CONTENT,
                    metadata.mimeType,
                    inputStream,
                    data.size.toLong()
                )
                response.addHeader("Content-Range", "bytes $start-${start + data.size - 1}/$totalSize")
                response.addHeader("Accept-Ranges", "bytes")
                response.addHeader("Content-Length", data.size.toString())
                return response
            } else {
                return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT,
                    "Failed to fetch video data"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling range request", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "Error: ${e.message}"
            )
        }
    }
    
    private fun handleFullRequest(
        videoId: String,
        metadata: VideoMetadata,
        totalSize: Long
    ): Response {
        // 返回一个响应，告诉客户端支持Range请求
        val response = newFixedLengthResponse(
            Response.Status.OK,
            metadata.mimeType,
            null as InputStream?,
            totalSize
        )
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Content-Length", totalSize.toString())
        return response
    }
    
    /**
     * 获取指定范围的视频数据
     */
    private fun getVideoData(
        videoId: String,
        metadata: VideoMetadata,
        start: Long,
        end: Long
    ): ByteArray? = runBlocking {
        try {
            val chunkSize = metadata.chunkSize.toLong()
            
            // 计算需要哪些分块
            val startChunkIndex = (start / chunkSize).toInt()
            val endChunkIndex = (end / chunkSize).toInt()
            
            // 收集所有需要的数据
            val resultBuffer = mutableListOf<Byte>()
            
            for (chunkIndex in startChunkIndex..endChunkIndex) {
                if (chunkIndex >= metadata.chunks.size) break
                
                val chunkInfo = metadata.chunks[chunkIndex]
                val chunkKey = "$videoId-$chunkIndex"
                
                // 获取分块数据（优先从缓存）
                val decryptedChunk = chunkCache[chunkKey] ?: run {
                    // 从WebDAV下载
                    val remotePath = "${settings.remoteBasePath}$videoId/${chunkInfo.filename}"
                    Log.d(TAG, "Downloading chunk: $remotePath")
                    
                    val encryptedData = webdavClient.downloadFile(remotePath).getOrNull()
                    if (encryptedData == null) {
                        Log.e(TAG, "Failed to download chunk $chunkIndex")
                        return@runBlocking null
                    }
                    
                    // 解密
                    val decrypted = CryptoManager.decryptChunk(encryptedData, metadata.encryptionKey)
                    
                    // 缓存（简单的LRU策略）
                    if (chunkCache.size >= maxCacheSize) {
                        val oldestKey = chunkCache.keys.firstOrNull()
                        if (oldestKey != null) {
                            chunkCache.remove(oldestKey)
                        }
                    }
                    chunkCache[chunkKey] = decrypted
                    
                    decrypted
                }
                
                // 计算这个分块中需要的数据范围
                val chunkStart = chunkIndex * chunkSize
                val chunkEnd = chunkStart + decryptedChunk.size - 1
                
                val dataStart = if (chunkIndex == startChunkIndex) {
                    (start - chunkStart).toInt()
                } else {
                    0
                }
                
                val dataEnd = if (chunkIndex == endChunkIndex) {
                    minOf((end - chunkStart).toInt(), decryptedChunk.size - 1)
                } else {
                    decryptedChunk.size - 1
                }
                
                // 添加数据
                for (i in dataStart..dataEnd) {
                    if (i < decryptedChunk.size) {
                        resultBuffer.add(decryptedChunk[i])
                    }
                }
            }
            
            resultBuffer.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting video data", e)
            null
        }
    }
    
    fun clearCache() {
        chunkCache.clear()
    }
}
