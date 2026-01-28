package com.yin2hao.myvideos.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yin2hao.myvideos.data.model.Settings
import com.yin2hao.myvideos.data.model.VideoItem
import com.yin2hao.myvideos.data.repository.SettingsRepository
import com.yin2hao.myvideos.data.repository.VideoRepository
import com.yin2hao.myvideos.player.LocalProxyServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PlayerViewModel(
    private val context: Context,
    private val settingsRepository: SettingsRepository
) : ViewModel() {
    
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    
    private val _proxyUrl = MutableStateFlow<String?>(null)
    val proxyUrl: StateFlow<String?> = _proxyUrl
    
    private var proxyServer: LocalProxyServer? = null
    private var currentVideoId: String? = null
    
    fun prepareVideo(video: VideoItem) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            _proxyUrl.value = null
            
            try {
                val settings = settingsRepository.settingsFlow.first()
                if (!settings.isValid()) {
                    _error.value = "设置无效，请先完成配置"
                    _isLoading.value = false
                    return@launch
                }
                
                // 获取完整的元数据（如果没有的话）
                val metadata = video.metadata ?: run {
                    val repository = VideoRepository(context, settings)
                    repository.loadVideoMetadata(video.videoId)
                }
                
                if (metadata == null) {
                    _error.value = "无法加载视频信息"
                    _isLoading.value = false
                    return@launch
                }
                
                // 启动本地代理服务器
                proxyServer = LocalProxyServer.getInstance(settings)
                if (!proxyServer!!.isAlive) {
                    proxyServer!!.start()
                }
                
                // 注册视频
                currentVideoId = video.videoId
                proxyServer!!.registerVideo(video.videoId, metadata)
                
                // 获取代理URL
                val url = proxyServer!!.getProxyUrl(video.videoId)
                _proxyUrl.value = url
                _isLoading.value = false
                
            } catch (e: Exception) {
                e.printStackTrace()
                _error.value = e.message ?: "准备播放失败"
                _isLoading.value = false
            }
        }
    }
    
    fun cleanup() {
        currentVideoId?.let {
            proxyServer?.unregisterVideo(it)
        }
        proxyServer?.clearCache()
    }
    
    override fun onCleared() {
        super.onCleared()
        cleanup()
    }
}

class PlayerViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PlayerViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return PlayerViewModel(context, SettingsRepository(context)) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
