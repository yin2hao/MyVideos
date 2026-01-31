package com.yin2hao.myvideos.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yin2hao.myvideos.data.model.Settings
import com.yin2hao.myvideos.data.model.UploadState
import com.yin2hao.myvideos.data.repository.SettingsRepository
import com.yin2hao.myvideos.network.WebDAVClient
import com.yin2hao.myvideos.video.StreamVideoUploadManager
import com.yin2hao.myvideos.video.VideoInfo
import com.yin2hao.myvideos.video.VideoMetadataExtractor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class UploadViewModel(
    private val context: Context,
    private val settingsRepository: SettingsRepository
) : ViewModel() {
    
    private val metadataExtractor = VideoMetadataExtractor(context)
    
    private val _selectedVideoUri = MutableStateFlow<Uri?>(null)
    val selectedVideoUri: StateFlow<Uri?> = _selectedVideoUri
    
    private val _videoInfo = MutableStateFlow<VideoInfo?>(null)
    val videoInfo: StateFlow<VideoInfo?> = _videoInfo
    
    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title
    
    private val _description = MutableStateFlow("")
    val description: StateFlow<String> = _description
    
    private val _tags = MutableStateFlow<List<String>>(emptyList())
    val tags: StateFlow<List<String>> = _tags
    
    private val _tagInput = MutableStateFlow("")
    val tagInput: StateFlow<String> = _tagInput
    
    private val _uploadState = MutableStateFlow<UploadState>(UploadState.Idle)
    val uploadState: StateFlow<UploadState> = _uploadState
    
    private val _settingsValid = MutableStateFlow(false)
    val settingsValid: StateFlow<Boolean> = _settingsValid
    
    // 标记是否已经消费过成功/错误状态（用于避免 Toast 重复显示）
    private val _resultConsumed = MutableStateFlow(false)
    val resultConsumed: StateFlow<Boolean> = _resultConsumed
    
    private var currentSettings: Settings = Settings()
    
    init {
        viewModelScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                currentSettings = settings
                _settingsValid.value = settings.isValid()
            }
        }
    }
    
    fun selectVideo(uri: Uri) {
        _selectedVideoUri.value = uri
        
        viewModelScope.launch {
            try {
                val info = metadataExtractor.extractMetadata(uri)
                _videoInfo.value = info
            } catch (e: Exception) {
                e.printStackTrace()
                _videoInfo.value = null
            }
        }
    }
    
    fun updateTitle(title: String) {
        _title.value = title
    }
    
    fun updateDescription(description: String) {
        _description.value = description
    }
    
    fun updateTagInput(input: String) {
        _tagInput.value = input
    }
    
    fun addTag(tag: String) {
        val trimmedTag = tag.trim()
        if (trimmedTag.isNotBlank() && !_tags.value.contains(trimmedTag)) {
            _tags.value = _tags.value + trimmedTag
        }
        _tagInput.value = ""
    }
    
    fun removeTag(tag: String) {
        _tags.value = _tags.value.filter { it != tag }
    }
    
    /**
     * 标记结果已被消费（Toast 已显示）
     */
    fun consumeResult() {
        _resultConsumed.value = true
    }
    
    suspend fun startUpload() {
        val uri = _selectedVideoUri.value ?: return
        val videoTitle = _title.value.takeIf { it.isNotBlank() } ?: return
        
        // 重置结果消费标志
        _resultConsumed.value = false
        
        // 获取最新的设置
        val settings = settingsRepository.settingsFlow.first()
        if (!settings.isValid()) {
            _uploadState.value = UploadState.Error("请先完成设置配置")
            return
        }
        
        startStreamUpload(uri, videoTitle, settings)
    }
    
    private suspend fun startStreamUpload(uri: Uri, videoTitle: String, settings: Settings) {
        val webDavClient = WebDAVClient(settings)
        val uploadManager = StreamVideoUploadManager(
            webDavClient, 
            context.contentResolver,
            settings.masterPassword
        )
        
        // 监听上传进度
        viewModelScope.launch {
            uploadManager.uploadProgress.collect { progress ->
                if (progress != null) {
                    when (progress.phase) {
                        "encrypting" -> _uploadState.value = UploadState.Encrypting(progress.progress)
                        "uploading" -> _uploadState.value = UploadState.Uploading(progress.progress)
                        "finishing" -> _uploadState.value = UploadState.UploadingMetadata
                    }
                }
            }
        }
        
        // 开始上传
        val result = uploadManager.uploadVideo(
            videoUri = uri,
            title = videoTitle,
            description = _description.value,
            tags = _tags.value,
            masterPassword = settings.masterPassword,
            basePath = settings.remoteBasePath,
            onExtractCover = { videoUri ->
                try {
                    metadataExtractor.extractCover(videoUri)
                } catch (e: Exception) {
                    null
                }
            }
        )
        
        result.fold(
            onSuccess = {
                _uploadState.value = UploadState.Success
            },
            onFailure = { e ->
                _uploadState.value = UploadState.Error(e.message ?: "上传失败")
            }
        )
    }
    
    fun reset() {
        _selectedVideoUri.value = null
        _videoInfo.value = null
        _title.value = ""
        _description.value = ""
        _tags.value = emptyList()
        _tagInput.value = ""
        _uploadState.value = UploadState.Idle
        _resultConsumed.value = false
    }
}

class UploadViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(UploadViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return UploadViewModel(context, SettingsRepository(context)) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
