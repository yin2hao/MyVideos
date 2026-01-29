package com.yin2hao.myvideos.network

import com.yin2hao.myvideos.data.model.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPSClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * FTP/FTPS 客户端
 */
class FTPClientImpl(private val settings: Settings) : CloudStorageClient {
    
    private fun createFtpClient(): FTPClient {
        return if (settings.ftpUseFTPS) {
            FTPSClient()
        } else {
            FTPClient()
        }
    }
    
    private suspend fun <T> withFtpConnection(block: suspend (FTPClient) -> T): Result<T> = 
        withContext(Dispatchers.IO) {
            val ftp = createFtpClient()
            try {
                // 连接
                ftp.connect(settings.ftpHost, settings.ftpPort)
                
                // 登录
                if (!ftp.login(settings.ftpUsername, settings.ftpPassword)) {
                    throw Exception("FTP 登录失败")
                }
                
                // 设置为被动模式
                ftp.enterLocalPassiveMode()
                
                // 设置文件类型为二进制
                ftp.setFileType(FTP.BINARY_FILE_TYPE)
                
                // 执行操作
                val result = block(ftp)
                Result.success(result)
                
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                try {
                    if (ftp.isConnected) {
                        ftp.logout()
                        ftp.disconnect()
                    }
                } catch (e: Exception) {
                    // 忽略断开连接时的错误
                }
            }
        }
    
    override suspend fun testConnection(): Result<Boolean> = 
        withFtpConnection { ftp ->
            ftp.isConnected && ftp.isAvailable
        }
    
    override suspend fun uploadFile(remotePath: String, data: ByteArray): Result<Unit> = 
        withFtpConnection { ftp ->
            val cleanPath = remotePath.trimStart('/')
            
            // 确保目录存在
            val directory = cleanPath.substringBeforeLast('/', "")
            if (directory.isNotEmpty()) {
                createDirectories(ftp, directory)
            }
            
            // 上传文件
            ByteArrayInputStream(data).use { inputStream ->
                if (!ftp.storeFile(cleanPath, inputStream)) {
                    throw Exception("上传失败: ${ftp.replyString}")
                }
            }
        }
    
    override suspend fun uploadStream(
        remotePath: String,
        inputStream: InputStream,
        contentLength: Long
    ): Result<Unit> = withFtpConnection { ftp ->
        val cleanPath = remotePath.trimStart('/')
        
        // 确保目录存在
        val directory = cleanPath.substringBeforeLast('/', "")
        if (directory.isNotEmpty()) {
            createDirectories(ftp, directory)
        }
        
        // 上传文件
        if (!ftp.storeFile(cleanPath, inputStream)) {
            throw Exception("上传失败: ${ftp.replyString}")
        }
    }
    
    override suspend fun downloadFile(remotePath: String): Result<ByteArray> = 
        withFtpConnection { ftp ->
            val cleanPath = remotePath.trimStart('/')
            val outputStream = ByteArrayOutputStream()
            
            if (!ftp.retrieveFile(cleanPath, outputStream)) {
                throw Exception("下载失败: ${ftp.replyString}")
            }
            
            outputStream.toByteArray()
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
        withFtpConnection { ftp ->
            val cleanPath = remotePath.trimStart('/')
            if (!ftp.deleteFile(cleanPath)) {
                throw Exception("删除失败: ${ftp.replyString}")
            }
        }
    
    override suspend fun listFiles(remotePath: String): Result<List<RemoteFile>> = 
        withFtpConnection { ftp ->
            val cleanPath = remotePath.trimStart('/').ifEmpty { "/" }
            val files = ftp.listFiles(cleanPath)
            
            files.map { file ->
                RemoteFile(
                    name = file.name,
                    path = "$cleanPath/${file.name}",
                    isDirectory = file.isDirectory,
                    size = file.size,
                    lastModified = file.timestamp?.timeInMillis ?: 0
                )
            }
        }
    
    override suspend fun fileExists(remotePath: String): Result<Boolean> = 
        withFtpConnection { ftp ->
            val cleanPath = remotePath.trimStart('/')
            val directory = cleanPath.substringBeforeLast('/', "")
            val fileName = cleanPath.substringAfterLast('/')
            
            val dir = if (directory.isEmpty()) "/" else directory
            val files = ftp.listFiles(dir)
            files.any { it.name == fileName }
        }
    
    override suspend fun createDirectory(remotePath: String): Result<Unit> = 
        withFtpConnection { ftp ->
            val cleanPath = remotePath.trimStart('/')
            createDirectories(ftp, cleanPath)
        }
    
    /**
     * 递归创建目录
     */
    private fun createDirectories(ftp: FTPClient, path: String) {
        val parts = path.split('/').filter { it.isNotEmpty() }
        var currentPath = ""
        
        for (part in parts) {
            currentPath += "/$part"
            try {
                // 尝试切换到目录，如果失败则创建
                if (!ftp.changeWorkingDirectory(currentPath)) {
                    if (!ftp.makeDirectory(currentPath)) {
                        // 目录可能已存在，继续
                    }
                }
            } catch (e: Exception) {
                // 忽略错误，目录可能已存在
            }
        }
        
        // 回到根目录
        ftp.changeWorkingDirectory("/")
    }
}
