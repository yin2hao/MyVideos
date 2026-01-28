package com.yin2hao.myvideos

import android.os.Bundle
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
import com.yin2hao.myvideos.data.model.VideoItem
import com.yin2hao.myvideos.ui.screens.PlayerScreen
import com.yin2hao.myvideos.ui.screens.SettingsScreen
import com.yin2hao.myvideos.ui.screens.UploadScreen
import com.yin2hao.myvideos.ui.screens.VideosScreen
import com.yin2hao.myvideos.ui.theme.MyVideosTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyVideosTheme {
                MyVideosApp()
            }
        }
    }
}

@Composable
fun MyVideosApp() {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.VIDEOS) }
    var selectedVideo by remember { mutableStateOf<VideoItem?>(null) }
    
    // 如果有选中的视频，显示播放页面
    if (selectedVideo != null) {
        PlayerScreen(
            video = selectedVideo!!,
            onNavigateBack = { selectedVideo = null }
        )
    } else {
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

enum class AppDestinations(
    val label: String,
    val icon: ImageVector,
) {
    VIDEOS("视频", Icons.Default.VideoLibrary),
    UPLOAD("上传", Icons.Default.CloudUpload),
    SETTINGS("设置", Icons.Default.Settings),
}