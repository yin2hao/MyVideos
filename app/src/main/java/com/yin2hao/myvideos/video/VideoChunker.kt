package com.yin2hao.myvideos.video

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * 视频分块处理器
 */
class VideoChunker(private val context: Context) {
    
    /**
     * 分块信息
     */
    data class ChunkResult(
        val index: Int,
        val data: ByteArray,
        val originalSize: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ChunkResult

            if (index != other.index) return false
            if (!data.contentEquals(other.data)) return false
            if (originalSize != other.originalSize) return false

            return true
        }

        override fun hashCode(): Int {
            var result = index
            result = 31 * result + data.contentHashCode()
            result = 31 * result + originalSize
            return result
        }
    }
    
    /**
     * 分块回调
     */
    interface ChunkCallback {
        suspend fun onChunk(chunk: ChunkResult)
        fun onProgress(current: Int, total: Int)
    }
    
    /**
     * 将视频分块处理
     * @param uri 视频URI
     * @param chunkSize 每块大小（字节）
     * @param callback 分块回调
     * @return 总块数
     */
    suspend fun chunkVideo(
        uri: Uri,
        chunkSize: Long,
        callback: ChunkCallback
    ): Int = withContext(Dispatchers.IO) {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("无法打开视频文件")
        
        val fileSize = getFileSize(uri)
        val totalChunks = ((fileSize + chunkSize - 1) / chunkSize).toInt()
        
        inputStream.use { stream ->
            var chunkIndex = 0
            val buffer = ByteArray(chunkSize.toInt())
            
            while (true) {
                val bytesRead = readFully(stream, buffer)
                if (bytesRead <= 0) break
                
                val chunkData = if (bytesRead < buffer.size) {
                    buffer.copyOfRange(0, bytesRead)
                } else {
                    buffer.clone()
                }
                
                callback.onChunk(ChunkResult(chunkIndex, chunkData, bytesRead))
                callback.onProgress(chunkIndex + 1, totalChunks)
                
                chunkIndex++
            }
            
            chunkIndex
        }
    }
    
    /**
     * 将视频分块保存到临时文件
     * @return 分块文件列表
     */
    suspend fun chunkVideoToFiles(
        uri: Uri,
        chunkSize: Long,
        outputDir: File,
        onProgress: (current: Int, total: Int) -> Unit
    ): List<File> = withContext(Dispatchers.IO) {
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }
        
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("无法打开视频文件")
        
        val fileSize = getFileSize(uri)
        val totalChunks = ((fileSize + chunkSize - 1) / chunkSize).toInt()
        val chunkFiles = mutableListOf<File>()
        
        inputStream.use { stream ->
            var chunkIndex = 0
            val buffer = ByteArray(chunkSize.toInt())
            
            while (true) {
                val bytesRead = readFully(stream, buffer)
                if (bytesRead <= 0) break
                
                val chunkFile = File(outputDir, "chunk_${String.format("%04d", chunkIndex)}.tmp")
                FileOutputStream(chunkFile).use { out ->
                    out.write(buffer, 0, bytesRead)
                }
                
                chunkFiles.add(chunkFile)
                onProgress(chunkIndex + 1, totalChunks)
                
                chunkIndex++
            }
        }
        
        chunkFiles
    }
    
    /**
     * 读取指定长度的数据
     */
    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var totalRead = 0
        while (totalRead < buffer.size) {
            val read = input.read(buffer, totalRead, buffer.size - totalRead)
            if (read == -1) break
            totalRead += read
        }
        return totalRead
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
