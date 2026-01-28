package com.yin2hao.myvideos.data.model

/**
 * 上传状态
 */
sealed class UploadState {
    object Idle : UploadState()
    object Preparing : UploadState()
    data class Chunking(val progress: Float, val currentChunk: Int, val totalChunks: Int) : UploadState()
    data class Encrypting(val progress: Float, val currentChunk: Int, val totalChunks: Int) : UploadState()
    data class Uploading(val progress: Float, val currentChunk: Int, val totalChunks: Int) : UploadState()
    object UploadingMetadata : UploadState()
    object UploadingCover : UploadState()
    object Success : UploadState()
    data class Error(val message: String) : UploadState()
}

/**
 * 连接测试状态
 */
sealed class ConnectionState {
    object Unknown : ConnectionState()
    object Testing : ConnectionState()
    object Success : ConnectionState()
    data class Failed(val message: String) : ConnectionState()
}
