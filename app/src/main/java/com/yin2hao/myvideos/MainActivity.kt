package com.yin2hao.myvideos

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.yin2hao.myvideos.data.model.Settings
import com.yin2hao.myvideos.data.repository.SettingsRepository
import com.yin2hao.myvideos.data.model.VideoItem
import com.yin2hao.myvideos.ui.screens.CachedVideoPlayerScreen
import com.yin2hao.myvideos.ui.screens.CachedVideosScreen
import com.yin2hao.myvideos.ui.screens.PlayerScreen
import com.yin2hao.myvideos.ui.screens.SettingsScreen
import com.yin2hao.myvideos.ui.screens.UploadScreen
import com.yin2hao.myvideos.ui.screens.VideosScreen
import com.yin2hao.myvideos.ui.theme.MyVideosTheme

class MainActivity : ComponentActivity() {
    
    // PiP 状态
    private var isInPipMode = mutableStateOf(false)
    private var isPlayingVideo = mutableStateOf(false)
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settingsRepository = SettingsRepository(this)
        enableEdgeToEdge()
        setContent {
            val settings by settingsRepository.settingsFlow.collectAsState(initial = Settings())
            val inPipMode by isInPipMode
            
            MyVideosTheme(dynamicColor = settings.dynamicColor) {
                MyVideosApp(
                    isInPipMode = inPipMode,
                    onEnterPip = { enterPipMode() },
                    onVideoPlayingChanged = { isPlaying -> isPlayingVideo.value = isPlaying }
                )
            }
        }
    }
    
    /**
     * 进入画中画模式
     */
    fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }
    
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 当用户按下 Home 键时，如果正在播放视频，自动进入 PiP 模式
        if (isPlayingVideo.value && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPipMode()
        }
    }
    
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode.value = isInPictureInPictureMode
    }
}

@Composable
fun MyVideosApp(
    isInPipMode: Boolean = false,
    onEnterPip: () -> Unit = {},
    onVideoPlayingChanged: (Boolean) -> Unit = {}
) {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.VIDEOS) }
    var selectedVideo by remember { mutableStateOf<VideoItem?>(null) }
    var showCachedVideos by remember { mutableStateOf(false) }
    
    // 用于播放缓存视频的本地路径和标题
    var cachedVideoPath by remember { mutableStateOf<String?>(null) }
    var cachedVideoTitle by remember { mutableStateOf<String?>(null) }
    
    // 通知是否在播放视频
    LaunchedEffect(selectedVideo, cachedVideoPath) {
        onVideoPlayingChanged(selectedVideo != null || cachedVideoPath != null)
    }
    
    // 如果有选中的视频，显示播放页面
    when {
        selectedVideo != null -> {
            PlayerScreen(
                video = selectedVideo!!,
                onNavigateBack = { selectedVideo = null },
                isInPipMode = isInPipMode,
                onEnterPip = onEnterPip
            )
        }
        cachedVideoPath != null -> {
            // 播放缓存视频（直接使用本地文件）
            CachedVideoPlayerScreen(
                localPath = cachedVideoPath!!,
                title = cachedVideoTitle ?: "缓存视频",
                onNavigateBack = {
                    cachedVideoPath = null
                    cachedVideoTitle = null
                },
                isInPipMode = isInPipMode,
                onEnterPip = onEnterPip
            )
        }
        showCachedVideos -> {
            CachedVideosScreen(
                onNavigateBack = { showCachedVideos = false },
                onPlayCachedVideo = { path, title ->
                    cachedVideoPath = path
                    cachedVideoTitle = title
                }
            )
        }
        else -> {
            // 否则显示主界面
            NavigationSuiteScaffold(
                navigationSuiteItems = {
                    AppDestinations.entries.forEach {
                        item(
                            icon = {
                                Icon(
                                    it.icon,
                                    contentDescription = it.label
                                )
                            },
                            label = { Text(it.label) },
                            selected = it == currentDestination,
                            onClick = { currentDestination = it }
                        )
                    }
                }
            ) {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    when (currentDestination) {
                        AppDestinations.VIDEOS -> {
                            VideosScreen(
                                onVideoClick = { video ->
                                    selectedVideo = video
                                },
                                onNavigateToCachedVideos = {
                                    showCachedVideos = true
                                },
                                modifier = Modifier.padding(innerPadding)
                            )
                        }
                        AppDestinations.UPLOAD -> {
                            UploadScreen(
                                modifier = Modifier.padding(innerPadding)
                            )
                        }
                        AppDestinations.SETTINGS -> {
                            SettingsScreen(
                                modifier = Modifier.padding(innerPadding)
                            )
                        }
                    }
                }
            }
        }
    }
}

enum class AppDestinations(
    val label: String,
    val icon: ImageVector,
) {
    VIDEOS("视频", Icons.Default.VideoLibrary),
    UPLOAD("上传", Icons.Default.CloudUpload),
    SETTINGS("设置", Icons.Default.Settings),
}