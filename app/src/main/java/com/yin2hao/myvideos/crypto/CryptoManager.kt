package com.yin2hao.myvideos.crypto

import android.util.Base64
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM 加密管理器
 */
object CryptoManager {
    
    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128 // bits
    private const val GCM_IV_LENGTH = 12   // bytes (96 bits, recommended for GCM)
    private const val KEY_LENGTH = 32      // bytes (256 bits)
    
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
     * 生成随机IV
     * @return Base64编码的IV
     */
    fun generateIV(): String {
        val iv = ByteArray(GCM_IV_LENGTH)
        secureRandom.nextBytes(iv)
        return Base64.encodeToString(iv, Base64.NO_WRAP)
    }
    
    /**
     * 从密码派生密钥 (简单实现，使用SHA-256)
     */
    fun deriveKeyFromPassword(password: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(password.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }
    
    /**
     * 加密数据
     * @param data 要加密的数据
     * @param keyBase64 Base64编码的密钥
     * @param ivBase64 Base64编码的IV
     * @return 加密后的数据
     */
    fun encrypt(data: ByteArray, keyBase64: String, ivBase64: String): ByteArray {
        val cipher = getCipher(Cipher.ENCRYPT_MODE, keyBase64, ivBase64)
        return cipher.doFinal(data)
    }
    
    /**
     * 解密数据
     * @param encryptedData 加密的数据
     * @param keyBase64 Base64编码的密钥
     * @param ivBase64 Base64编码的IV
     * @return 解密后的数据
     */
    fun decrypt(encryptedData: ByteArray, keyBase64: String, ivBase64: String): ByteArray {
        val cipher = getCipher(Cipher.DECRYPT_MODE, keyBase64, ivBase64)
        return cipher.doFinal(encryptedData)
    }
    
    /**
     * 创建加密输出流
     */
    fun createEncryptOutputStream(
        outputStream: OutputStream,
        keyBase64: String,
        ivBase64: String
    ): CipherOutputStream {
        val cipher = getCipher(Cipher.ENCRYPT_MODE, keyBase64, ivBase64)
        return CipherOutputStream(outputStream, cipher)
    }
    
    /**
     * 创建解密输入流
     */
    fun createDecryptInputStream(
        inputStream: InputStream,
        keyBase64: String,
        ivBase64: String
    ): CipherInputStream {
        val cipher = getCipher(Cipher.DECRYPT_MODE, keyBase64, ivBase64)
        return CipherInputStream(inputStream, cipher)
    }
    
    /**
     * 加密数据块（用于分块加密）
     * 每个块使用独立的IV，IV会被前置到加密数据中
     */
    fun encryptChunk(data: ByteArray, keyBase64: String): ByteArray {
        val iv = ByteArray(GCM_IV_LENGTH)
        secureRandom.nextBytes(iv)
        
        val key = getSecretKey(keyBase64)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec)
        
        val encrypted = cipher.doFinal(data)
        
        // 将IV前置到加密数据中
        val result = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, result, 0, iv.size)
        System.arraycopy(encrypted, 0, result, iv.size, encrypted.size)
        
        return result
    }
    
    /**
     * 解密数据块
     * 从数据中提取IV然后解密
     */
    fun decryptChunk(encryptedData: ByteArray, keyBase64: String): ByteArray {
        if (encryptedData.size < GCM_IV_LENGTH) {
            throw IllegalArgumentException("Encrypted data too short")
        }
        
        val iv = encryptedData.copyOfRange(0, GCM_IV_LENGTH)
        val encrypted = encryptedData.copyOfRange(GCM_IV_LENGTH, encryptedData.size)
        
        val key = getSecretKey(keyBase64)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)
        
        return cipher.doFinal(encrypted)
    }
    
    /**
     * 计算加密后的大小估算（GCM会增加tag）
     */
    fun estimateEncryptedSize(originalSize: Long): Long {
        // IV (12 bytes) + original data + GCM tag (16 bytes)
        return GCM_IV_LENGTH + originalSize + (GCM_TAG_LENGTH / 8)
    }
    
    private fun getCipher(mode: Int, keyBase64: String, ivBase64: String): Cipher {
        val key = getSecretKey(keyBase64)
        val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
        
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(mode, key, gcmSpec)
        
        return cipher
    }
    
    private fun getSecretKey(keyBase64: String): SecretKey {
        val keyBytes = Base64.decode(keyBase64, Base64.NO_WRAP)
        return SecretKeySpec(keyBytes, ALGORITHM)
    }
}
