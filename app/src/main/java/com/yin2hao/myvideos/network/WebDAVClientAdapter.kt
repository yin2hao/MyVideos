package com.yin2hao.myvideos.network

import com.yin2hao.myvideos.data.model.Settings
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * WebDAV 客户端适配器，实现 CloudStorageClient 接口
 */
class WebDAVClientAdapter(private val settings: Settings) : CloudStorageClient {
    
    private val webdavClient = WebDAVClient(settings)
    
    override suspend fun testConnection(): Result<Boolean> {
        return webdavClient.testConnection()
    }
    
    override suspend fun uploadFile(remotePath: String, data: ByteArray): Result<Unit> {
        return webdavClient.uploadFile(remotePath, data).map { }
    }
    
    override suspend fun uploadStream(
        remotePath: String,
        inputStream: InputStream,
        contentLength: Long
    ): Result<Unit> {
        return try {
            val data = inputStream.readBytes()
            uploadFile(remotePath, data)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun downloadFile(remotePath: String): Result<ByteArray> {
        return webdavClient.downloadFile(remotePath)
    }
    
    override suspend fun downloadStream(remotePath: String): Result<InputStream> {
        return webdavClient.downloadFileStream(remotePath)
    }
    
    override suspend fun deleteFile(remotePath: String): Result<Unit> {
        return webdavClient.delete(remotePath).map { }
    }
    
    override suspend fun listFiles(remotePath: String): Result<List<RemoteFile>> {
        return webdavClient.listDirectory(remotePath).map { files ->
            files.map { file ->
                RemoteFile(
                    name = file.name,
                    path = file.path,
                    isDirectory = file.isDirectory,
                    size = file.size,
                    lastModified = 0
                )
            }
        }
    }
    
    override suspend fun fileExists(remotePath: String): Result<Boolean> {
        return Result.success(webdavClient.exists(remotePath))
    }
    
    override suspend fun createDirectory(remotePath: String): Result<Unit> {
        return webdavClient.createDirectory(remotePath).map { }
    }
}
