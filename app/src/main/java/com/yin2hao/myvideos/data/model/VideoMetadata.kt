package com.yin2hao.myvideos.data.model

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * 视频元数据 - 使用二进制格式存储
 * 
 * 二进制格式:
 * [4 bytes] Magic Number (0x4D564944 = "MVID")
 * [4 bytes] Version
 * [4 bytes] Video ID length
 * [n bytes] Video ID (UTF-8)
 * [4 bytes] Title length
 * [n bytes] Title (UTF-8)
 * [4 bytes] Description length
 * [n bytes] Description (UTF-8)
 * [8 bytes] Duration (ms)
 * [8 bytes] Original file size
 * [4 bytes] Total chunks count
 * [4 bytes] Chunk size (bytes) 
 * [4 bytes] Encryption key length
 * [n bytes] Encryption key (Base64)
 * [4 bytes] IV length
 * [n bytes] IV (Base64)
 * [4 bytes] Cover encryption key length
 * [n bytes] Cover encryption key (Base64)
 * [4 bytes] Cover IV length
 * [n bytes] Cover IV (Base64)
 * [8 bytes] Created timestamp
 * [4 bytes] Mime type length
 * [n bytes] Mime type (UTF-8)
 * [4 bytes] Number of chunk infos
 * For each chunk:
 *   [4 bytes] Chunk index
 *   [8 bytes] Original size
 *   [8 bytes] Encrypted size
 *   [4 bytes] Chunk filename length
 *   [n bytes] Chunk filename
 */
data class VideoMetadata(
    val videoId: String,
    val title: String,
    val description: String,
    val durationMs: Long,
    val originalFileSize: Long,
    val totalChunks: Int,
    val chunkSize: Int,
    val encryptionKey: String,  // Base64 encoded
    val iv: String,             // Base64 encoded
    val coverEncryptionKey: String, // Base64 encoded
    val coverIv: String,        // Base64 encoded
    val createdAt: Long,
    val mimeType: String,
    val chunks: List<ChunkInfo>
) {
    companion object {
        private const val MAGIC_NUMBER = 0x4D564944 // "MVID"
        private const val VERSION = 1
        
        fun fromBytes(data: ByteArray): VideoMetadata {
            val input = DataInputStream(ByteArrayInputStream(data))
            
            val magic = input.readInt()
            if (magic != MAGIC_NUMBER) {
                throw IllegalArgumentException("Invalid magic number: $magic")
            }
            
            val version = input.readInt()
            if (version != VERSION) {
                throw IllegalArgumentException("Unsupported version: $version")
            }
            
            val videoId = readString(input)
            val title = readString(input)
            val description = readString(input)
            val durationMs = input.readLong()
            val originalFileSize = input.readLong()
            val totalChunks = input.readInt()
            val chunkSize = input.readInt()
            val encryptionKey = readString(input)
            val iv = readString(input)
            val coverEncryptionKey = readString(input)
            val coverIv = readString(input)
            val createdAt = input.readLong()
            val mimeType = readString(input)
            
            val chunkCount = input.readInt()
            val chunks = mutableListOf<ChunkInfo>()
            repeat(chunkCount) {
                val index = input.readInt()
                val originalSize = input.readLong()
                val encryptedSize = input.readLong()
                val filename = readString(input)
                chunks.add(ChunkInfo(index, originalSize, encryptedSize, filename))
            }
            
            input.close()
            
            return VideoMetadata(
                videoId = videoId,
                title = title,
                description = description,
                durationMs = durationMs,
                originalFileSize = originalFileSize,
                totalChunks = totalChunks,
                chunkSize = chunkSize,
                encryptionKey = encryptionKey,
                iv = iv,
                coverEncryptionKey = coverEncryptionKey,
                coverIv = coverIv,
                createdAt = createdAt,
                mimeType = mimeType,
                chunks = chunks
            )
        }
        
        private fun readString(input: DataInputStream): String {
            val length = input.readInt()
            if (length == 0) return ""
            val bytes = ByteArray(length)
            input.readFully(bytes)
            return String(bytes, Charsets.UTF_8)
        }
    }
    
    fun toBytes(): ByteArray {
        val output = ByteArrayOutputStream()
        val dataOutput = DataOutputStream(output)
        
        dataOutput.writeInt(MAGIC_NUMBER)
        dataOutput.writeInt(VERSION)
        writeString(dataOutput, videoId)
        writeString(dataOutput, title)
        writeString(dataOutput, description)
        dataOutput.writeLong(durationMs)
        dataOutput.writeLong(originalFileSize)
        dataOutput.writeInt(totalChunks)
        dataOutput.writeInt(chunkSize)
        writeString(dataOutput, encryptionKey)
        writeString(dataOutput, iv)
        writeString(dataOutput, coverEncryptionKey)
        writeString(dataOutput, coverIv)
        dataOutput.writeLong(createdAt)
        writeString(dataOutput, mimeType)
        
        dataOutput.writeInt(chunks.size)
        chunks.forEach { chunk ->
            dataOutput.writeInt(chunk.index)
            dataOutput.writeLong(chunk.originalSize)
            dataOutput.writeLong(chunk.encryptedSize)
            writeString(dataOutput, chunk.filename)
        }
        
        dataOutput.flush()
        dataOutput.close()
        
        return output.toByteArray()
    }
    
    private fun writeString(output: DataOutputStream, str: String) {
        val bytes = str.toByteArray(Charsets.UTF_8)
        output.writeInt(bytes.size)
        if (bytes.isNotEmpty()) {
            output.write(bytes)
        }
    }
}

/**
 * 视频分块信息
 */
data class ChunkInfo(
    val index: Int,
    val originalSize: Long,
    val encryptedSize: Long,
    val filename: String
)
