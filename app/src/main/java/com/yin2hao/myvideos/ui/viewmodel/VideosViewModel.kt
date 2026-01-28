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
    
    // 封面加载状态缓存
    private val coverLoadingState = mutableMapOf<String, Boolean>()
    
    private var currentSettings: Settings = Settings()
    private var videoRepository: VideoRepository? = null
    
    init {
        viewModelScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                currentSettings = settings
                _settingsValid.value = settings.isValid()
                if (settings.isValid()) {
                    videoRepository = VideoRepository(context, settings)
                }
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
                videoRepository = repository
                val result = repository.getVideoList()
                
                if (result.isSuccess) {
                    _videos.value = result.getOrDefault(emptyList())
                    // 开始懒加载封面
                    loadCoversLazily()
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
    
    /**
     * 懒加载封面 - 逐个加载，避免一次性请求太多
     */
    private fun loadCoversLazily() {
        viewModelScope.launch {
            val repository = videoRepository ?: return@launch
            val currentVideos = _videos.value.toMutableList()
            
            for (i in currentVideos.indices) {
                val video = currentVideos[i]
                
                // 如果没有封面且有封面可加载
                if (video.coverUrl == null && video.hasCover && coverLoadingState[video.videoId] != true) {
                    coverLoadingState[video.videoId] = true
                    
                    try {
                        val coverPath = repository.loadCover(video.videoId)
                        if (coverPath != null) {
                            // 更新视频封面
                            currentVideos[i] = video.copy(coverUrl = coverPath)
                            _videos.value = currentVideos.toList()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }
    
    /**
     * 加载单个视频的封面
     */
    fun loadCoverFor(videoId: String) {
        if (coverLoadingState[videoId] == true) return
        
        viewModelScope.launch {
            val repository = videoRepository ?: return@launch
            coverLoadingState[videoId] = true
            
            try {
                val coverPath = repository.loadCover(videoId)
                if (coverPath != null) {
                    val currentVideos = _videos.value.toMutableList()
                    val index = currentVideos.indexOfFirst { it.videoId == videoId }
                    if (index >= 0) {
                        currentVideos[index] = currentVideos[index].copy(coverUrl = coverPath)
                        _videos.value = currentVideos.toList()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
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
