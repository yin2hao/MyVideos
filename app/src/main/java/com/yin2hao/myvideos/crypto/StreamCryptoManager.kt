package com.yin2hao.myvideos.crypto

import android.util.Base64
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-CTR 流式加密管理器
 * 
 * 特点：
 * 1. 支持随机访问解密（可以从任意字节位置开始解密）
 * 2. 加密后文件大小与原文件相同
 * 3. 适合大文件流式传输
 * 
 * 文件格式：
 * [16 bytes] IV (Nonce + Counter初始值)
 * [n bytes]  加密数据（与原数据等长）
 */
object StreamCryptoManager {
    
    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/CTR/NoPadding"
    private const val IV_LENGTH = 16      // bytes (128 bits for CTR)
    private const val KEY_LENGTH = 32     // bytes (256 bits)
    private const val COUNTER_OFFSET = 12 // 前12字节是Nonce，后4字节是Counter
    
    private val secureRandom = SecureRandom()
    
    /**
     * 生成随机AES-256密钥
     * @return Base64编码的密钥
     */
    fun generateRandomKey(): String {
        val key = ByteArray(KEY_LENGTH)
        secureRandom.nextBytes(key)
        return Base64.encodeToString(key, Base64.NO_WRAP)
    }
    
    /**
     * 生成随机IV (Nonce)
     * @return Base64编码的IV
     */
    fun generateIV(): String {
        val iv = ByteArray(IV_LENGTH)
        secureRandom.nextBytes(iv)
        // 将后4字节（Counter）设为0
        iv[12] = 0
        iv[13] = 0
        iv[14] = 0
        iv[15] = 0
        return Base64.encodeToString(iv, Base64.NO_WRAP)
    }
    
    /**
     * 从密码派生密钥
     */
    fun deriveKeyFromPassword(password: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(password.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }
    
    /**
     * 加密整个数据
     * @return IV + 加密数据
     */
    fun encrypt(data: ByteArray, keyBase64: String): ByteArray {
        val iv = ByteArray(IV_LENGTH)
        secureRandom.nextBytes(iv)
        iv[12] = 0; iv[13] = 0; iv[14] = 0; iv[15] = 0
        
        val cipher = getCipher(Cipher.ENCRYPT_MODE, keyBase64, iv)
        val encrypted = cipher.doFinal(data)
        
        // IV + encrypted data
        val result = ByteArray(IV_LENGTH + encrypted.size)
        System.arraycopy(iv, 0, result, 0, IV_LENGTH)
        System.arraycopy(encrypted, 0, result, IV_LENGTH, encrypted.size)
        
        return result
    }
    
    /**
     * 解密整个数据
     * @param encryptedData IV + 加密数据
     */
    fun decrypt(encryptedData: ByteArray, keyBase64: String): ByteArray {
        if (encryptedData.size < IV_LENGTH) {
            throw IllegalArgumentException("Data too short")
        }
        
        val iv = encryptedData.copyOfRange(0, IV_LENGTH)
        val encrypted = encryptedData.copyOfRange(IV_LENGTH, encryptedData.size)
        
        val cipher = getCipher(Cipher.DECRYPT_MODE, keyBase64, iv)
        return cipher.doFinal(encrypted)
    }
    
    /**
     * 解密指定范围的数据（支持随机访问）
     * 
     * @param encryptedData 完整的加密数据（含IV头）或仅加密数据
     * @param keyBase64 密钥
     * @param ivBase64 IV（如果encryptedData不含IV头）
     * @param offset 在原始数据中的起始位置（不含IV）
     * @param length 要解密的长度
     */
    fun decryptRange(
        encryptedData: ByteArray,
        keyBase64: String,
        ivBase64: String,
        offset: Long,
        length: Int
    ): ByteArray {
        val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
        
        // 计算新的Counter值
        // CTR模式：每16字节（一个AES块）Counter加1
        val blockIndex = offset / 16
        val blockOffset = (offset % 16).toInt()
        
        // 创建调整后的IV（增加Counter）
        val adjustedIv = iv.copyOf()
        addToCounter(adjustedIv, blockIndex)
        
        // 解密时需要从块边界开始
        val startOffset = (blockIndex * 16).toInt()
        val endOffset = startOffset + blockOffset + length
        val dataToDecrypt = if (endOffset <= encryptedData.size) {
            encryptedData.copyOfRange(startOffset, endOffset)
        } else {
            encryptedData.copyOfRange(startOffset, encryptedData.size)
        }
        
        val cipher = getCipher(Cipher.DECRYPT_MODE, keyBase64, adjustedIv)
        val decrypted = cipher.doFinal(dataToDecrypt)
        
        // 返回请求的范围
        return if (blockOffset == 0 && decrypted.size >= length) {
            decrypted.copyOfRange(0, length)
        } else if (blockOffset + length <= decrypted.size) {
            decrypted.copyOfRange(blockOffset, blockOffset + length)
        } else {
            decrypted.copyOfRange(blockOffset, decrypted.size)
        }
    }
    
    /**
     * 流式加密
     * @param input 原始数据输入流
     * @param output 加密数据输出流（会先写入IV）
     * @param keyBase64 密钥
     * @return IV的Base64编码
     */
    fun encryptStream(
        input: InputStream,
        output: OutputStream,
        keyBase64: String,
        bufferSize: Int = 8192
    ): String {
        val iv = ByteArray(IV_LENGTH)
        secureRandom.nextBytes(iv)
        iv[12] = 0; iv[13] = 0; iv[14] = 0; iv[15] = 0
        
        // 先写入IV
        output.write(iv)
        
        val cipher = getCipher(Cipher.ENCRYPT_MODE, keyBase64, iv)
        val buffer = ByteArray(bufferSize)
        
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
            val encrypted = cipher.update(buffer, 0, bytesRead)
            if (encrypted != null) {
                output.write(encrypted)
            }
        }
        
        val finalBlock = cipher.doFinal()
        if (finalBlock != null && finalBlock.isNotEmpty()) {
            output.write(finalBlock)
        }
        
        return Base64.encodeToString(iv, Base64.NO_WRAP)
    }
    
    /**
     * 流式解密
     */
    fun decryptStream(
        input: InputStream,
        output: OutputStream,
        keyBase64: String,
        bufferSize: Int = 8192
    ) {
        // 先读取IV
        val iv = ByteArray(IV_LENGTH)
        var totalRead = 0
        while (totalRead < IV_LENGTH) {
            val read = input.read(iv, totalRead, IV_LENGTH - totalRead)
            if (read == -1) throw IllegalArgumentException("Unexpected end of stream")
            totalRead += read
        }
        
        val cipher = getCipher(Cipher.DECRYPT_MODE, keyBase64, iv)
        val buffer = ByteArray(bufferSize)
        
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
            val decrypted = cipher.update(buffer, 0, bytesRead)
            if (decrypted != null) {
                output.write(decrypted)
            }
        }
        
        val finalBlock = cipher.doFinal()
        if (finalBlock != null && finalBlock.isNotEmpty()) {
            output.write(finalBlock)
        }
    }
    
    /**
     * 获取加密后的文件大小
     * CTR模式加密后大小 = IV大小 + 原始大小
     */
    fun getEncryptedSize(originalSize: Long): Long {
        return IV_LENGTH + originalSize
    }
    
    /**
     * 获取原始文件大小
     */
    fun getOriginalSize(encryptedSize: Long): Long {
        return encryptedSize - IV_LENGTH
    }
    
    private fun getCipher(mode: Int, keyBase64: String, iv: ByteArray): Cipher {
        val keyBytes = Base64.decode(keyBase64, Base64.NO_WRAP)
        val secretKey = SecretKeySpec(keyBytes, ALGORITHM)
        val ivSpec = IvParameterSpec(iv)
        
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(mode, secretKey, ivSpec)
        
        return cipher
    }
    
    /**
     * 将块索引添加到Counter（IV的后4字节）
     */
    private fun addToCounter(iv: ByteArray, blockIndex: Long) {
        var carry = blockIndex
        for (i in 15 downTo 12) {
            val sum = (iv[i].toLong() and 0xFF) + (carry and 0xFF)
            iv[i] = sum.toByte()
            carry = (carry shr 8) + (sum shr 8)
        }
    }
}
