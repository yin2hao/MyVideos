package com.yin2hao.myvideos.network

import com.yin2hao.myvideos.data.model.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS S3 兼容存储客户端
 * 支持 AWS S3、阿里云OSS、MinIO 等 S3 兼容服务
 */
class S3Client(private val settings: Settings) : CloudStorageClient {
    
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
    
    private val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("GMT")
    }
    
    private val iso8601Format = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("GMT")
    }
    
    private val dateStampFormat = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("GMT")
    }
    
    private fun getEndpoint(): String {
        return settings.s3Endpoint.ifBlank {
            "https://s3.${settings.s3Region}.amazonaws.com"
        }.trimEnd('/')
    }
    
    private fun getObjectUrl(key: String): String {
        val endpoint = getEndpoint()
        return if (settings.s3UsePathStyle) {
            "$endpoint/${settings.s3Bucket}/$key"
        } else {
            val host = endpoint.replace("https://", "").replace("http://", "")
            val protocol = if (endpoint.startsWith("https")) "https" else "http"
            "$protocol://${settings.s3Bucket}.$host/$key"
        }
    }
    
    private fun getSignatureKey(key: String, dateStamp: String, regionName: String, serviceName: String): ByteArray {
        val kDate = hmacSHA256(("AWS4$key").toByteArray(), dateStamp)
        val kRegion = hmacSHA256(kDate, regionName)
        val kService = hmacSHA256(kRegion, serviceName)
        return hmacSHA256(kService, "aws4_request")
    }
    
    private fun hmacSHA256(key: ByteArray, data: String): ByteArray {
        val algorithm = "HmacSHA256"
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(key, algorithm))
        return mac.doFinal(data.toByteArray())
    }
    
    private fun sha256Hex(data: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(data)
            .joinToString("") { "%02x".format(it) }
    }
    
    private fun createAuthorizationHeader(
        method: String,
        url: String,
        date: Date,
        contentHash: String,
        contentType: String = ""
    ): String {
        val amzDate = iso8601Format.format(date)
        val dateStamp = dateStampFormat.format(date)
        
        val canonicalUri = url.substringAfter(getEndpoint())
        val canonicalQueryString = ""
        val canonicalHeaders = "host:${url.substringAfter("://").substringBefore("/")}\n" +
                               "x-amz-content-sha256:$contentHash\n" +
                               "x-amz-date:$amzDate\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
        
        val canonicalRequest = "$method\n$canonicalUri\n$canonicalQueryString\n$canonicalHeaders\n$signedHeaders\n$contentHash"
        val canonicalRequestHash = sha256Hex(canonicalRequest.toByteArray())
        
        val credentialScope = "$dateStamp/${settings.s3Region}/s3/aws4_request"
        val stringToSign = "AWS4-HMAC-SHA256\n$amzDate\n$credentialScope\n$canonicalRequestHash"
        
        val signingKey = getSignatureKey(settings.s3SecretKey, dateStamp, settings.s3Region, "s3")
        val signature = hmacSHA256(signingKey, stringToSign).joinToString("") { "%02x".format(it) }
        
        return "AWS4-HMAC-SHA256 Credential=${settings.s3AccessKey}/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"
    }
    
    override suspend fun testConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            // 列出存储桶来测试连接
            val date = Date()
            val url = getEndpoint() + "/${settings.s3Bucket}/"
            val contentHash = sha256Hex(ByteArray(0))
            
            val request = Request.Builder()
                .url(url)
                .get()
                .header("x-amz-date", iso8601Format.format(date))
                .header("x-amz-content-sha256", contentHash)
                .header("Authorization", createAuthorizationHeader("GET", url, date, contentHash))
                .build()
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    Result.failure(Exception("S3 连接失败: ${response.code} - ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun uploadFile(remotePath: String, data: ByteArray): Result<Unit> = 
        withContext(Dispatchers.IO) {
            try {
                val key = remotePath.trimStart('/')
                val date = Date()
                val url = getObjectUrl(key)
                val contentHash = sha256Hex(data)
                
                val requestBody = data.toRequestBody("application/octet-stream".toMediaType())
                
                val request = Request.Builder()
                    .url(url)
                    .put(requestBody)
                    .header("x-amz-date", iso8601Format.format(date))
                    .header("x-amz-content-sha256", contentHash)
                    .header("Authorization", createAuthorizationHeader("PUT", url, date, contentHash))
                    .build()
                
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Result.success(Unit)
                    } else {
                        Result.failure(Exception("上传失败: ${response.code} - ${response.body?.string()}"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    
    override suspend fun uploadStream(
        remotePath: String,
        inputStream: InputStream,
        contentLength: Long
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // 读取流到内存（S3 需要计算 hash）
            val data = inputStream.readBytes()
            uploadFile(remotePath, data)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun downloadFile(remotePath: String): Result<ByteArray> = 
        withContext(Dispatchers.IO) {
            try {
                val key = remotePath.trimStart('/')
                val date = Date()
                val url = getObjectUrl(key)
                val contentHash = sha256Hex(ByteArray(0))
                
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .header("x-amz-date", iso8601Format.format(date))
                    .header("x-amz-content-sha256", contentHash)
                    .header("Authorization", createAuthorizationHeader("GET", url, date, contentHash))
                    .build()
                
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val data = response.body?.bytes() ?: ByteArray(0)
                        Result.success(data)
                    } else {
                        Result.failure(Exception("下载失败: ${response.code} - ${response.message}"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    
    override suspend fun downloadStream(remotePath: String): Result<InputStream> = 
        withContext(Dispatchers.IO) {
            try {
                val data = downloadFile(remotePath).getOrThrow()
                Result.success(ByteArrayInputStream(data))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    
    override suspend fun deleteFile(remotePath: String): Result<Unit> = 
        withContext(Dispatchers.IO) {
            try {
                val key = remotePath.trimStart('/')
                val date = Date()
                val url = getObjectUrl(key)
                val contentHash = sha256Hex(ByteArray(0))
                
                val request = Request.Builder()
                    .url(url)
                    .delete()
                    .header("x-amz-date", iso8601Format.format(date))
                    .header("x-amz-content-sha256", contentHash)
                    .header("Authorization", createAuthorizationHeader("DELETE", url, date, contentHash))
                    .build()
                
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful || response.code == 204) {
                        Result.success(Unit)
                    } else {
                        Result.failure(Exception("删除失败: ${response.code} - ${response.message}"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    
    override suspend fun listFiles(remotePath: String): Result<List<RemoteFile>> = 
        withContext(Dispatchers.IO) {
            try {
                // S3 ListObjects API 实现
                // 这里简化实现，实际需要解析 XML 响应
                Result.success(emptyList())
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    
    override suspend fun fileExists(remotePath: String): Result<Boolean> = 
        withContext(Dispatchers.IO) {
            try {
                val key = remotePath.trimStart('/')
                val date = Date()
                val url = getObjectUrl(key)
                val contentHash = sha256Hex(ByteArray(0))
                
                val request = Request.Builder()
                    .url(url)
                    .head()
                    .header("x-amz-date", iso8601Format.format(date))
                    .header("x-amz-content-sha256", contentHash)
                    .header("Authorization", createAuthorizationHeader("HEAD", url, date, contentHash))
                    .build()
                
                client.newCall(request).execute().use { response ->
                    Result.success(response.isSuccessful)
                }
            } catch (e: Exception) {
                Result.success(false)
            }
        }
    
    override suspend fun createDirectory(remotePath: String): Result<Unit> {
        // S3 没有真正的目录概念，返回成功
        return Result.success(Unit)
    }
}
