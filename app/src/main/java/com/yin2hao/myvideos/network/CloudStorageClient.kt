package com.yin2hao.myvideos.network

import java.io.InputStream

/**
 * 云存储客户端统一接口
 * 支持多种云存储方式（WebDAV、FTP、S3等）
 */
interface CloudStorageClient {
    
    /**
     * 测试连接
     */
    suspend fun testConnection(): Result<Boolean>
    
    /**
     * 上传文件
     * @param remotePath 远程路径
     * @param data 文件数据
     * @return 上传结果
     */
    suspend fun uploadFile(remotePath: String, data: ByteArray): Result<Unit>
    
    /**
     * 上传流式数据
     * @param remotePath 远程路径
     * @param inputStream 输入流
     * @param contentLength 内容长度
     * @return 上传结果
     */
    suspend fun uploadStream(
        remotePath: String, 
        inputStream: InputStream, 
        contentLength: Long
    ): Result<Unit>
    
    /**
     * 下载文件
     * @param remotePath 远程路径
     * @return 文件数据
     */
    suspend fun downloadFile(remotePath: String): Result<ByteArray>
    
    /**
     * 下载流式数据
     * @param remotePath 远程路径
     * @return 输入流
     */
    suspend fun downloadStream(remotePath: String): Result<InputStream>
    
    /**
     * 删除文件
     * @param remotePath 远程路径
     */
    suspend fun deleteFile(remotePath: String): Result<Unit>
    
    /**
     * 列出目录内容
     * @param remotePath 远程路径
     * @return 文件列表
     */
    suspend fun listFiles(remotePath: String): Result<List<RemoteFile>>
    
    /**
     * 检查文件是否存在
     * @param remotePath 远程路径
     */
    suspend fun fileExists(remotePath: String): Result<Boolean>
    
    /**
     * 创建目录
     * @param remotePath 远程路径
     */
    suspend fun createDirectory(remotePath: String): Result<Unit>
}

/**
 * 远程文件信息
 */
data class RemoteFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: Long = 0
)
