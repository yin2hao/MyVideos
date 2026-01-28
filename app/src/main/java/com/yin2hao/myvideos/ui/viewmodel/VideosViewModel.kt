package com.yin2hao.myvideos.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yin2hao.myvideos.data.model.Settings
import com.yin2hao.myvideos.data.model.VideoItem
import com.yin2hao.myvideos.data.repository.SettingsRepository
import com.yin2hao.myvideos.data.repository.VideoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class VideosViewModel(
    private val context: Context,
    private val settingsRepository: SettingsRepository
) : ViewModel() {
    
    private val _videos = MutableStateFlow<List<VideoItem>>(emptyList())
    val videos: StateFlow<List<VideoItem>> = _videos
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    
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
    
    fun loadVideos() {
        viewModelScope.launch {
            val settings = settingsRepository.settingsFlow.first()
            if (!settings.isValid()) {
                _settingsValid.value = false
                return@launch
            }
            
            _settingsValid.value = true
            _isLoading.value = true
            _error.value = null
            
            try {
                val repository = VideoRepository(context, settings)
                val result = repository.getVideoList()
                
                if (result.isSuccess) {
                    _videos.value = result.getOrDefault(emptyList())
                } else {
                    _error.value = result.exceptionOrNull()?.message ?: "加载失败"
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "未知错误"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    fun deleteVideo(videoId: String) {
        viewModelScope.launch {
            val settings = settingsRepository.settingsFlow.first()
            if (!settings.isValid()) return@launch
            
            try {
                val repository = VideoRepository(context, settings)
                val result = repository.deleteVideo(videoId)
                
                if (result.isSuccess) {
                    _videos.value = _videos.value.filter { it.videoId != videoId }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

class VideosViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(VideosViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return VideosViewModel(context, SettingsRepository(context)) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
