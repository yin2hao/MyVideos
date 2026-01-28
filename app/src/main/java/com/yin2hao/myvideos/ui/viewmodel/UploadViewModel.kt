package com.yin2hao.myvideos.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yin2hao.myvideos.data.model.Settings
import com.yin2hao.myvideos.data.model.UploadState
import com.yin2hao.myvideos.data.repository.SettingsRepository
import com.yin2hao.myvideos.video.VideoInfo
import com.yin2hao.myvideos.video.VideoMetadataExtractor
import com.yin2hao.myvideos.video.VideoUploadManager
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
    
    private val _uploadState = MutableStateFlow<UploadState>(UploadState.Idle)
    val uploadState: StateFlow<UploadState> = _uploadState
    
    private val _settingsValid = MutableStateFlow(false)
    val settingsValid: StateFlow<Boolean> = _settingsValid
    
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
    
    suspend fun startUpload() {
        val uri = _selectedVideoUri.value ?: return
        val videoTitle = _title.value.takeIf { it.isNotBlank() } ?: return
        
        // 获取最新的设置
        val settings = settingsRepository.settingsFlow.first()
        if (!settings.isValid()) {
            _uploadState.value = UploadState.Error("请先完成设置配置")
            return
        }
        
        val uploadManager = VideoUploadManager(context, settings)
        
        // 监听上传状态
        viewModelScope.launch {
            uploadManager.uploadState.collect {
                _uploadState.value = it
            }
        }
        
        // 开始上传
        val videoId = uploadManager.uploadVideo(
            videoUri = uri,
            title = videoTitle,
            description = _description.value
        )
        
        if (videoId != null) {
            _uploadState.value = UploadState.Success
        }
    }
    
    fun reset() {
        _selectedVideoUri.value = null
        _videoInfo.value = null
        _title.value = ""
        _description.value = ""
        _uploadState.value = UploadState.Idle
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
