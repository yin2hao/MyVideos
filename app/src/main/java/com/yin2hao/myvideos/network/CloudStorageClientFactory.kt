package com.yin2hao.myvideos.network

import com.yin2hao.myvideos.data.model.Settings
import com.yin2hao.myvideos.data.model.StorageType

/**
 * 云存储客户端工厂
 * 根据配置创建对应的存储客户端
 */
object CloudStorageClientFactory {
    
    /**
     * 创建云存储客户端
     */
    fun createClient(settings: Settings): CloudStorageClient {
        return when (settings.storageType) {
            StorageType.WEBDAV -> WebDAVClientAdapter(settings)
            StorageType.FTP -> FTPClientImpl(settings)
            StorageType.S3 -> S3Client(settings)
        }
    }
}
