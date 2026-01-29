package com.yin2hao.myvideos.data.model

import com.google.gson.annotations.SerializedName

/**
 * 云存储类型
 */
enum class StorageType {
    WEBDAV,
    FTP,
    S3
}

/**
 * 应用设置数据类
 */
data class Settings(
    // 存储类型选择
    @SerializedName("storage_type")
    val storageType: StorageType = StorageType.WEBDAV,
    
    // WebDAV 配置
    @SerializedName("webdav_url")
    val webdavUrl: String = "",
    
    @SerializedName("webdav_username")
    val webdavUsername: String = "",
    
    @SerializedName("webdav_password")
    val webdavPassword: String = "",
    
    // FTP 配置
    @SerializedName("ftp_host")
    val ftpHost: String = "",
    
    @SerializedName("ftp_port")
    val ftpPort: Int = 21,
    
    @SerializedName("ftp_username")
    val ftpUsername: String = "",
    
    @SerializedName("ftp_password")
    val ftpPassword: String = "",
    
    @SerializedName("ftp_use_ftps")
    val ftpUseFTPS: Boolean = false,
    
    // S3 配置
    @SerializedName("s3_endpoint")
    val s3Endpoint: String = "",
    
    @SerializedName("s3_region")
    val s3Region: String = "us-east-1",
    
    @SerializedName("s3_bucket")
    val s3Bucket: String = "",
    
    @SerializedName("s3_access_key")
    val s3AccessKey: String = "",
    
    @SerializedName("s3_secret_key")
    val s3SecretKey: String = "",
    
    @SerializedName("s3_use_path_style")
    val s3UsePathStyle: Boolean = false,
    
    // 通用配置
    @SerializedName("master_password")
    val masterPassword: String = "",
    
    @SerializedName("chunk_size_mb")
    val chunkSizeMB: Int = 5, // 默认5MB
    
    @SerializedName("remote_base_path")
    val remoteBasePath: String = "/MyVideos/",

    @SerializedName("dynamic_color")
    val dynamicColor: Boolean = true
) {
    fun isValid(): Boolean {
        if (masterPassword.isBlank()) return false
        
        return when (storageType) {
            StorageType.WEBDAV -> {
                webdavUrl.isNotBlank() && 
                webdavUsername.isNotBlank() && 
                webdavPassword.isNotBlank()
            }
            StorageType.FTP -> {
                ftpHost.isNotBlank() && 
                ftpUsername.isNotBlank() && 
                ftpPassword.isNotBlank()
            }
            StorageType.S3 -> {
                s3Bucket.isNotBlank() && 
                s3AccessKey.isNotBlank() && 
                s3SecretKey.isNotBlank()
            }
        }
    }
    
    fun getChunkSizeBytes(): Long = chunkSizeMB.toLong() * 1024 * 1024
}
