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
    
    // 原始视频列表（未筛选）
    private val _allVideos = MutableStateFlow<List<VideoItem>>(emptyList())
    
    // 筛选后显示的视频列表
    private val _videos = MutableStateFlow<List<VideoItem>>(emptyList())
    val videos: StateFlow<List<VideoItem>> = _videos
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    
    private val _settingsValid = MutableStateFlow(false)
    val settingsValid: StateFlow<Boolean> = _settingsValid
    
    // 搜索查询
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery
    
    // 选中的标签筛选
    private val _selectedTag = MutableStateFlow<String?>(null)
    val selectedTag: StateFlow<String?> = _selectedTag
    
    // 所有可用的标签
    private val _allTags = MutableStateFlow<List<String>>(emptyList())
    val allTags: StateFlow<List<String>> = _allTags
    
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
                    val videoList = result.getOrDefault(emptyList())
                    _allVideos.value = videoList
                    
                    // 提取所有标签
                    val tags = videoList
                        .flatMap { it.tags }
                        .distinct()
                        .sorted()
                    _allTags.value = tags
                    
                    // 应用筛选
                    applyFilters()
                    
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
     * 更新搜索查询
     */
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        applyFilters()
    }
    
    /**
     * 选择标签筛选
     */
    fun selectTag(tag: String?) {
        _selectedTag.value = tag
        applyFilters()
    }
    
    /**
     * 应用搜索和标签筛选
     */
    private fun applyFilters() {
        val query = _searchQuery.value.lowercase().trim()
        val tag = _selectedTag.value
        
        var filteredVideos = _allVideos.value
        
        // 按标签筛选
        if (tag != null) {
            filteredVideos = filteredVideos.filter { it.tags.contains(tag) }
        }
        
        // 按搜索词筛选（标题和描述）
        if (query.isNotBlank()) {
            filteredVideos = filteredVideos.filter { video ->
                video.title.lowercase().contains(query) ||
                video.description.lowercase().contains(query)
            }
        }
        
        _videos.value = filteredVideos
    }
    
    /**
     * 懒加载封面 - 逐个加载，避免一次性请求太多
     */
    private fun loadCoversLazily() {
        viewModelScope.launch {
            val repository = videoRepository ?: return@launch
            val allVideosList = _allVideos.value.toMutableList()
            
            for (i in allVideosList.indices) {
                val video = allVideosList[i]
                
                // 如果没有封面且有封面可加载
                if (video.coverUrl == null && video.hasCover && coverLoadingState[video.videoId] != true) {
                    coverLoadingState[video.videoId] = true
                    
                    try {
                        val coverPath = repository.loadCover(video.videoId)
                        if (coverPath != null) {
                            // 更新视频封面
                            allVideosList[i] = video.copy(coverUrl = coverPath)
                            _allVideos.value = allVideosList.toList()
                            applyFilters()  // 重新应用筛选以更新显示
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
                    val allVideosList = _allVideos.value.toMutableList()
                    val index = allVideosList.indexOfFirst { it.videoId == videoId }
                    if (index >= 0) {
                        allVideosList[index] = allVideosList[index].copy(coverUrl = coverPath)
                        _allVideos.value = allVideosList.toList()
                        applyFilters()  // 重新应用筛选以更新显示
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
                    _allVideos.value = _allVideos.value.filter { it.videoId != videoId }
                    // 更新标签列表
                    _allTags.value = _allVideos.value
                        .flatMap { it.tags }
                        .distinct()
                        .sorted()
                    applyFilters()
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
