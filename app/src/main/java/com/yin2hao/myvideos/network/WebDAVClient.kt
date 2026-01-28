package com.yin2hao.myvideos.network

import com.yin2hao.myvideos.data.model.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/**
 * WebDAV 客户端
 */
class WebDAVClient(private val settings: Settings) {
    
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
    
    private val credentials: String by lazy {
        Credentials.basic(settings.webdavUsername, settings.webdavPassword)
    }
    
    private fun getBaseUrl(): String {
        return settings.webdavUrl.trimEnd('/')
    }
    
    private fun getFullUrl(path: String): String {
        val cleanPath = path.trimStart('/')
        return "${getBaseUrl()}/$cleanPath"
    }
    
    /**
     * 测试WebDAV连接
     */
    suspend fun testConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(getBaseUrl())
                .header("Authorization", credentials)
                .method("PROPFIND", null)
                .header("Depth", "0")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 207) {
                    Result.success(true)
                } else {
                    Result.failure(Exception("连接失败: ${response.code} ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 创建目录
     */
    suspend fun createDirectory(path: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(getFullUrl(path))
                .header("Authorization", credentials)
                .method("MKCOL", null)
                .build()
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 201 || response.code == 405) {
                    // 405 可能表示目录已存在
                    Result.success(true)
                } else {
                    Result.failure(Exception("创建目录失败: ${response.code} ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 上传文件
     */
    suspend fun uploadFile(
        path: String, 
        data: ByteArray,
        contentType: String = "application/octet-stream"
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val requestBody = data.toRequestBody(contentType.toMediaType())
            
            val request = Request.Builder()
                .url(getFullUrl(path))
                .header("Authorization", credentials)
                .put(requestBody)
                .build()
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 201 || response.code == 204) {
                    Result.success(true)
                } else {
                    Result.failure(Exception("上传失败: ${response.code} ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 下载文件
     */
    suspend fun downloadFile(path: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(getFullUrl(path))
                .header("Authorization", credentials)
                .get()
                .build()
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(response.body?.bytes() ?: ByteArray(0))
                } else {
                    Result.failure(Exception("下载失败: ${response.code} ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 获取文件流（用于大文件）
     */
    suspend fun downloadFileStream(path: String): Result<InputStream> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(getFullUrl(path))
                .header("Authorization", credentials)
                .get()
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Result.success(response.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0)))
            } else {
                response.close()
                Result.failure(Exception("下载失败: ${response.code} ${response.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 获取部分文件内容 (Range请求)
     */
    suspend fun downloadFileRange(path: String, start: Long, end: Long): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(getFullUrl(path))
                .header("Authorization", credentials)
                .header("Range", "bytes=$start-$end")
                .get()
                .build()
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 206) {
                    Result.success(response.body?.bytes() ?: ByteArray(0))
                } else {
                    Result.failure(Exception("下载失败: ${response.code} ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 列出目录内容
     */
    suspend fun listDirectory(path: String): Result<List<WebDAVFile>> = withContext(Dispatchers.IO) {
        try {
            val propfindBody = """<?xml version="1.0" encoding="utf-8"?>
                <D:propfind xmlns:D="DAV:">
                    <D:prop>
                        <D:displayname/>
                        <D:resourcetype/>
                        <D:getcontentlength/>
                        <D:getlastmodified/>
                    </D:prop>
                </D:propfind>
            """.trimIndent()
            
            val request = Request.Builder()
                .url(getFullUrl(path))
                .header("Authorization", credentials)
                .header("Depth", "1")
                .method("PROPFIND", propfindBody.toRequestBody("application/xml".toMediaType()))
                .build()
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 207) {
                    val body = response.body?.string() ?: ""
                    val files = parseWebDAVResponse(body, path)
                    Result.success(files)
                } else {
                    Result.failure(Exception("列表失败: ${response.code} ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 检查文件是否存在
     */
    suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(getFullUrl(path))
                .header("Authorization", credentials)
                .head()
                .build()
            
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 删除文件或目录
     */
    suspend fun delete(path: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(getFullUrl(path))
                .header("Authorization", credentials)
                .delete()
                .build()
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 204 || response.code == 404) {
                    Result.success(true)
                } else {
                    Result.failure(Exception("删除失败: ${response.code} ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun parseWebDAVResponse(xml: String, basePath: String): List<WebDAVFile> {
        val files = mutableListOf<WebDAVFile>()
        
        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(InputSource(xml.reader()))
            
            val responses = doc.getElementsByTagNameNS("DAV:", "response")
            
            for (i in 0 until responses.length) {
                val response = responses.item(i) as Element
                
                val hrefElements = response.getElementsByTagNameNS("DAV:", "href")
                if (hrefElements.length == 0) continue
                
                val href = hrefElements.item(0).textContent.trim()
                val decodedHref = java.net.URLDecoder.decode(href, "UTF-8")
                
                // 跳过当前目录本身
                val cleanBasePath = basePath.trimEnd('/')
                val cleanHref = decodedHref.trimEnd('/')
                if (cleanHref.endsWith(cleanBasePath) || cleanHref == cleanBasePath) {
                    continue
                }
                
                val propstat = response.getElementsByTagNameNS("DAV:", "propstat")
                if (propstat.length == 0) continue
                
                val prop = (propstat.item(0) as Element).getElementsByTagNameNS("DAV:", "prop")
                if (prop.length == 0) continue
                
                val propElement = prop.item(0) as Element
                
                // 获取显示名
                val displayNameElements = propElement.getElementsByTagNameNS("DAV:", "displayname")
                val displayName = if (displayNameElements.length > 0) {
                    displayNameElements.item(0).textContent.trim()
                } else {
                    decodedHref.split("/").lastOrNull { it.isNotEmpty() } ?: ""
                }
                
                // 检查是否是目录
                val resourceType = propElement.getElementsByTagNameNS("DAV:", "resourcetype")
                val isDirectory = if (resourceType.length > 0) {
                    val rtElement = resourceType.item(0) as Element
                    rtElement.getElementsByTagNameNS("DAV:", "collection").length > 0
                } else {
                    decodedHref.endsWith("/")
                }
                
                // 获取文件大小
                val contentLengthElements = propElement.getElementsByTagNameNS("DAV:", "getcontentlength")
                val contentLength = if (contentLengthElements.length > 0) {
                    contentLengthElements.item(0).textContent.trim().toLongOrNull() ?: 0L
                } else {
                    0L
                }
                
                if (displayName.isNotEmpty()) {
                    files.add(WebDAVFile(
                        name = displayName,
                        path = decodedHref,
                        isDirectory = isDirectory,
                        size = contentLength
                    ))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return files
    }
}

/**
 * WebDAV 文件信息
 */
data class WebDAVFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0
)
