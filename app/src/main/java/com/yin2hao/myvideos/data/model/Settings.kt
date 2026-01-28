package com.yin2hao.myvideos.data.model

import com.google.gson.annotations.SerializedName

/**
 * 应用设置数据类
 */
data class Settings(
    @SerializedName("webdav_url")
    val webdavUrl: String = "",
    
    @SerializedName("webdav_username")
    val webdavUsername: String = "",
    
    @SerializedName("webdav_password")
    val webdavPassword: String = "",
    
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
        return webdavUrl.isNotBlank() && 
               webdavUsername.isNotBlank() && 
               webdavPassword.isNotBlank() &&
               masterPassword.isNotBlank()
    }
    
    fun getChunkSizeBytes(): Long = chunkSizeMB.toLong() * 1024 * 1024
}
