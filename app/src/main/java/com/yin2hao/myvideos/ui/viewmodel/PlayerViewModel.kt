package com.yin2hao.myvideos.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yin2hao.myvideos.data.model.Settings
import com.yin2hao.myvideos.data.model.VideoItem
import com.yin2hao.myvideos.data.repository.SettingsRepository
import com.yin2hao.myvideos.data.repository.VideoRepository
import com.yin2hao.myvideos.network.WebDAVClient
import com.yin2hao.myvideos.player.LocalProxyServer
import com.yin2hao.myvideos.video.StreamLocalProxyServer
import fi.iki.elonen.NanoHTTPD
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
    private var streamProxyServer: StreamLocalProxyServer? = null
    private var currentVideoId: String? = null
    private var isStreamVideo: Boolean = false
    
    companion object {
        private const val CHUNKED_PROXY_PORT = 8765
        private const val STREAM_PROXY_PORT = 8766
    }
    
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
                
                currentVideoId = video.videoId
                isStreamVideo = video.isStream
                
                if (video.isStream) {
                    // 流式加密视频 - 使用StreamLocalProxyServer
                    prepareStreamVideo(video, settings)
                } else {
                    // 分块加密视频 - 使用LocalProxyServer
                    prepareChunkedVideo(video, settings)
                }
                
            } catch (e: Exception) {
                e.printStackTrace()
                _error.value = e.message ?: "准备播放失败"
                _isLoading.value = false
            }
        }
    }
    
    private suspend fun prepareStreamVideo(video: VideoItem, settings: Settings) {
        // 启动流式代理服务器
        if (streamProxyServer == null) {
            val webDavClient = WebDAVClient(settings)
            streamProxyServer = StreamLocalProxyServer(
                port = STREAM_PROXY_PORT,
                webDAVClient = webDavClient,
                masterPassword = settings.masterPassword,
                basePath = settings.remoteBasePath
            )
        }
        
        if (!streamProxyServer!!.isAlive) {
            streamProxyServer!!.start()
        }
        
        // 流式代理URL
        val url = "http://127.0.0.1:$STREAM_PROXY_PORT/stream/${video.videoId}"
        _proxyUrl.value = url
        _isLoading.value = false
    }
    
    private suspend fun prepareChunkedVideo(video: VideoItem, settings: Settings) {
        // 获取完整的元数据（如果没有的话）
        val metadata = video.metadata ?: run {
            val repository = VideoRepository(context, settings)
            repository.loadVideoMetadata(video.videoId)
        }
        
        if (metadata == null) {
            _error.value = "无法加载视频信息"
            _isLoading.value = false
            return
        }
        
        // 启动本地代理服务器
        proxyServer = LocalProxyServer.getInstance(settings)
        if (!proxyServer!!.isAlive) {
            proxyServer!!.start()
        }
        
        // 注册视频
        proxyServer!!.registerVideo(video.videoId, metadata)
        
        // 获取代理URL
        val url = proxyServer!!.getProxyUrl(video.videoId)
        _proxyUrl.value = url
        _isLoading.value = false
    }
    
    fun cleanup() {
        currentVideoId?.let {
            if (!isStreamVideo) {
                proxyServer?.unregisterVideo(it)
            }
        }
        proxyServer?.clearCache()
        streamProxyServer?.clearCache()
    }
    
    override fun onCleared() {
        super.onCleared()
        cleanup()
        try {
            streamProxyServer?.stop()
        } catch (e: Exception) {
            // ignore
        }
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
