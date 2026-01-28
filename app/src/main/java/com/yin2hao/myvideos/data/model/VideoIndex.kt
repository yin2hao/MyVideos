package com.yin2hao.myvideos.data.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * 视频索引 - 用于快速加载视频列表
 * 
 * 二进制格式:
 * [4 bytes] Magic Number (0x4D564958 = "MVIX")
 * [4 bytes] Version
 * [4 bytes] Video count
 * For each video:
 *   [4 bytes] Video ID length
 *   [n bytes] Video ID (UTF-8)
 *   [4 bytes] Title length
 *   [n bytes] Title (UTF-8)
 *   [4 bytes] Description length
 *   [n bytes] Description (UTF-8)
 *   [8 bytes] Duration (ms)
 *   [8 bytes] Original file size
 *   [8 bytes] Created timestamp
 *   [1 byte]  Has cover (0 or 1)
 *   [4 bytes] Mime type length
 *   [n bytes] Mime type (UTF-8)
 */
data class VideoIndex(
    val videos: List<VideoIndexEntry>
) {
    companion object {
        private const val MAGIC_NUMBER = 0x4D564958 // "MVIX"
        private const val VERSION = 1
        
        fun fromBytes(data: ByteArray): VideoIndex {
            val input = DataInputStream(ByteArrayInputStream(data))
            
            val magic = input.readInt()
            if (magic != MAGIC_NUMBER) {
                throw IllegalArgumentException("Invalid magic number: $magic")
            }
            
            val version = input.readInt()
            if (version != VERSION) {
                throw IllegalArgumentException("Unsupported version: $version")
            }
            
            val videoCount = input.readInt()
            val videos = mutableListOf<VideoIndexEntry>()
            
            repeat(videoCount) {
                val videoId = readString(input)
                val title = readString(input)
                val description = readString(input)
                val durationMs = input.readLong()
                val originalFileSize = input.readLong()
                val createdAt = input.readLong()
                val hasCover = input.readByte() == 1.toByte()
                val mimeType = readString(input)
                
                videos.add(VideoIndexEntry(
                    videoId = videoId,
                    title = title,
                    description = description,
                    durationMs = durationMs,
                    originalFileSize = originalFileSize,
                    createdAt = createdAt,
                    hasCover = hasCover,
                    mimeType = mimeType
                ))
            }
            
            input.close()
            return VideoIndex(videos)
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
        dataOutput.writeInt(videos.size)
        
        videos.forEach { entry ->
            writeString(dataOutput, entry.videoId)
            writeString(dataOutput, entry.title)
            writeString(dataOutput, entry.description)
            dataOutput.writeLong(entry.durationMs)
            dataOutput.writeLong(entry.originalFileSize)
            dataOutput.writeLong(entry.createdAt)
            dataOutput.writeByte(if (entry.hasCover) 1 else 0)
            writeString(dataOutput, entry.mimeType)
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
    
    /**
     * 添加视频到索引
     */
    fun addVideo(entry: VideoIndexEntry): VideoIndex {
        val newList = videos.toMutableList()
        // 如果已存在则更新，否则添加
        val existingIndex = newList.indexOfFirst { it.videoId == entry.videoId }
        if (existingIndex >= 0) {
            newList[existingIndex] = entry
        } else {
            newList.add(entry)
        }
        return VideoIndex(newList)
    }
    
    /**
     * 从索引中移除视频
     */
    fun removeVideo(videoId: String): VideoIndex {
        return VideoIndex(videos.filter { it.videoId != videoId })
    }
}

/**
 * 视频索引条目
 */
data class VideoIndexEntry(
    val videoId: String,
    val title: String,
    val description: String,
    val durationMs: Long,
    val originalFileSize: Long,
    val createdAt: Long,
    val hasCover: Boolean,
    val mimeType: String
) {
    fun getDurationFormatted(): String {
        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }
}
