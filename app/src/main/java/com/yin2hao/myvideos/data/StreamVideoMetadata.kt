package com.yin2hao.myvideos.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 流式加密的视频元数据（不分块版本）
 * 
 * 文件格式：
 * [4 bytes]  Magic: "MVST" (MyVideos STream)
 * [1 byte]   Version
 * [8 bytes]  原始文件大小
 * [8 bytes]  加密后文件大小
 * [2 bytes]  IV长度
 * [n bytes]  IV (Base64)
 * [2 bytes]  密钥长度
 * [n bytes]  视频加密密钥 (Base64, 用主密码加密)
 * [2 bytes]  MIME类型长度
 * [n bytes]  MIME类型
 */
data class StreamVideoMetadata(
    val originalSize: Long,
    val encryptedSize: Long,
    val iv: String,               // Base64编码的IV
    val encryptedKey: String,     // Base64编码的加密后密钥
    val mimeType: String
) {
    companion object {
        const val MAGIC = 0x4D565354 // "MVST"
        const val VERSION: Byte = 1
        
        fun fromBinary(input: InputStream): StreamVideoMetadata {
            val buffer = ByteArray(4)
            input.read(buffer)
            val magic = ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN).int
            if (magic != MAGIC) {
                throw IllegalArgumentException("Invalid magic number: $magic, expected: $MAGIC")
            }
            
            val version = input.read().toByte()
            if (version != VERSION) {
                throw IllegalArgumentException("Unsupported version: $version")
            }
            
            val longBuffer = ByteArray(8)
            input.read(longBuffer)
            val originalSize = ByteBuffer.wrap(longBuffer).order(ByteOrder.BIG_ENDIAN).long
            
            input.read(longBuffer)
            val encryptedSize = ByteBuffer.wrap(longBuffer).order(ByteOrder.BIG_ENDIAN).long
            
            val shortBuffer = ByteArray(2)
            input.read(shortBuffer)
            val ivLen = ByteBuffer.wrap(shortBuffer).order(ByteOrder.BIG_ENDIAN).short.toInt()
            val ivBytes = ByteArray(ivLen)
            input.read(ivBytes)
            val iv = String(ivBytes, Charsets.UTF_8)
            
            input.read(shortBuffer)
            val keyLen = ByteBuffer.wrap(shortBuffer).order(ByteOrder.BIG_ENDIAN).short.toInt()
            val keyBytes = ByteArray(keyLen)
            input.read(keyBytes)
            val encryptedKey = String(keyBytes, Charsets.UTF_8)
            
            input.read(shortBuffer)
            val mimeLen = ByteBuffer.wrap(shortBuffer).order(ByteOrder.BIG_ENDIAN).short.toInt()
            val mimeBytes = ByteArray(mimeLen)
            input.read(mimeBytes)
            val mimeType = String(mimeBytes, Charsets.UTF_8)
            
            return StreamVideoMetadata(
                originalSize = originalSize,
                encryptedSize = encryptedSize,
                iv = iv,
                encryptedKey = encryptedKey,
                mimeType = mimeType
            )
        }
    }
    
    fun toBinary(): ByteArray {
        val output = ByteArrayOutputStream()
        
        // Magic
        output.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(MAGIC).array())
        
        // Version
        output.write(VERSION.toInt())
        
        // Original size
        output.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(originalSize).array())
        
        // Encrypted size
        output.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(encryptedSize).array())
        
        // IV
        val ivBytes = iv.toByteArray(Charsets.UTF_8)
        output.write(ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(ivBytes.size.toShort()).array())
        output.write(ivBytes)
        
        // Encrypted Key
        val keyBytes = encryptedKey.toByteArray(Charsets.UTF_8)
        output.write(ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(keyBytes.size.toShort()).array())
        output.write(keyBytes)
        
        // MIME type
        val mimeBytes = mimeType.toByteArray(Charsets.UTF_8)
        output.write(ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(mimeBytes.size.toShort()).array())
        output.write(mimeBytes)
        
        return output.toByteArray()
    }
}
