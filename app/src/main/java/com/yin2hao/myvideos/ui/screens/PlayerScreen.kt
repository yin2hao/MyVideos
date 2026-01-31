package com.yin2hao.myvideos.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.yin2hao.myvideos.data.model.VideoItem
import com.yin2hao.myvideos.ui.viewmodel.PlayerViewModel
import com.yin2hao.myvideos.ui.viewmodel.PlayerViewModelFactory
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun PlayerScreen(
    video: VideoItem,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewModel: PlayerViewModel = viewModel(
        factory = PlayerViewModelFactory(context)
    )
    
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val proxyUrl by viewModel.proxyUrl.collectAsState()
    
    // 初始化播放
    LaunchedEffect(video) {
        viewModel.prepareVideo(video)
    }
    
    // 创建ExoPlayer
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
        }
    }
    
    // 播放速度状态
    var currentSpeed by remember { mutableFloatStateOf(1.0f) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var isLongPressing by remember { mutableStateOf(false) }
    var speedBeforeLongPress by remember { mutableFloatStateOf(1.0f) }
    
    // 双击提示状态
    var showSeekIndicator by remember { mutableStateOf<SeekDirection?>(null) }
    
    // 可用的播放速度选项
    val speedOptions = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
    
    // 更新播放速度
    fun updatePlaybackSpeed(speed: Float) {
        currentSpeed = speed
        exoPlayer.playbackParameters = PlaybackParameters(speed)
    }
    
    // 隐藏快进/快退提示
    LaunchedEffect(showSeekIndicator) {
        if (showSeekIndicator != null) {
            delay(800)
            showSeekIndicator = null
        }
    }
    
    // 当代理URL准备好时，设置媒体源
    LaunchedEffect(proxyUrl) {
        proxyUrl?.let { url ->
            val mediaItem = MediaItem.fromUri(url)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
        }
    }
    
    // 生命周期管理
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> exoPlayer.pause()
                Lifecycle.Event.ON_RESUME -> if (proxyUrl != null) exoPlayer.play()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
            viewModel.cleanup()
        }
    }
    
    // 外置播放器菜单状态
    var showExternalPlayerMenu by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(video.title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 外置播放器按钮
                    Box {
                        IconButton(
                            onClick = { showExternalPlayerMenu = true },
                            enabled = proxyUrl != null
                        ) {
                            Icon(
                                Icons.Default.OpenInNew,
                                contentDescription = "使用外部播放器"
                            )
                        }
                        
                        DropdownMenu(
                            expanded = showExternalPlayerMenu,
                            onDismissRequest = { showExternalPlayerMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("使用其他应用打开") },
                                onClick = {
                                    showExternalPlayerMenu = false
                                    proxyUrl?.let { url ->
                                        openWithExternalPlayer(context, url, video.title)
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.OpenInNew, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("选择视频播放器") },
                                onClick = {
                                    showExternalPlayerMenu = false
                                    proxyUrl?.let { url ->
                                        openWithVideoPlayer(context, url, video.title)
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.VideoLibrary, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("复制播放链接") },
                                onClick = {
                                    showExternalPlayerMenu = false
                                    proxyUrl?.let { url ->
                                        copyToClipboard(context, url)
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 播放器区域
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black)
            ) {
                when {
                    isLoading -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = Color.White)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "正在准备播放...",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    error != null -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = error ?: "播放错误",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    proxyUrl != null -> {
                        // 播放器视图
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    player = exoPlayer
                                    layoutParams = FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    useController = true
                                    setShowNextButton(false)
                                    setShowPreviousButton(false)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        
                        // 手势控制覆盖层 - 双击快进/快退，长按3倍速
                        Row(modifier = Modifier.fillMaxSize()) {
                            // 左侧区域 - 双击快退10秒
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .pointerInput(Unit) {
                                        detectTapGestures(
                                            onDoubleTap = {
                                                val newPosition = (exoPlayer.currentPosition - 10000).coerceAtLeast(0)
                                                exoPlayer.seekTo(newPosition)
                                                showSeekIndicator = SeekDirection.BACKWARD
                                            },
                                            onLongPress = {
                                                speedBeforeLongPress = currentSpeed
                                                updatePlaybackSpeed(3.0f)
                                                isLongPressing = true
                                            },
                                            onPress = {
                                                tryAwaitRelease()
                                                if (isLongPressing) {
                                                    updatePlaybackSpeed(speedBeforeLongPress)
                                                    isLongPressing = false
                                                }
                                            }
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                // 快退指示器
                                AnimatedVisibility(
                                    visible = showSeekIndicator == SeekDirection.BACKWARD,
                                    enter = fadeIn(),
                                    exit = fadeOut()
                                ) {
                                    SeekIndicator(isForward = false)
                                }
                            }
                            
                            // 右侧区域 - 双击快进10秒
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .pointerInput(Unit) {
                                        detectTapGestures(
                                            onDoubleTap = {
                                                val newPosition = (exoPlayer.currentPosition + 10000)
                                                    .coerceAtMost(exoPlayer.duration)
                                                exoPlayer.seekTo(newPosition)
                                                showSeekIndicator = SeekDirection.FORWARD
                                            },
                                            onLongPress = {
                                                speedBeforeLongPress = currentSpeed
                                                updatePlaybackSpeed(3.0f)
                                                isLongPressing = true
                                            },
                                            onPress = {
                                                tryAwaitRelease()
                                                if (isLongPressing) {
                                                    updatePlaybackSpeed(speedBeforeLongPress)
                                                    isLongPressing = false
                                                }
                                            }
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                // 快进指示器
                                AnimatedVisibility(
                                    visible = showSeekIndicator == SeekDirection.FORWARD,
                                    enter = fadeIn(),
                                    exit = fadeOut()
                                ) {
                                    SeekIndicator(isForward = true)
                                }
                            }
                        }
                        
                        // 长按3倍速提示
                        AnimatedVisibility(
                            visible = isLongPressing,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 16.dp),
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            Surface(
                                color = Color.Black.copy(alpha = 0.7f),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        Icons.Default.FastForward,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "3.0x 倍速播放中",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                        
                        // 当前倍速显示（非1.0x时）
                        if (currentSpeed != 1.0f && !isLongPressing) {
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 8.dp, end = 8.dp),
                                color = Color.Black.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "${currentSpeed}x",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
            
            // 倍速选择按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "播放速度",
                    style = MaterialTheme.typography.titleSmall
                )
                
                Box {
                    OutlinedButton(
                        onClick = { showSpeedMenu = true }
                    ) {
                        Icon(
                            Icons.Default.Speed,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("${currentSpeed}x")
                    }
                    
                    DropdownMenu(
                        expanded = showSpeedMenu,
                        onDismissRequest = { showSpeedMenu = false }
                    ) {
                        speedOptions.forEach { speed ->
                            DropdownMenuItem(
                                text = { 
                                    Text(
                                        text = "${speed}x",
                                        color = if (speed == currentSpeed) 
                                            MaterialTheme.colorScheme.primary 
                                        else 
                                            MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                onClick = {
                                    updatePlaybackSpeed(speed)
                                    showSpeedMenu = false
                                },
                                leadingIcon = {
                                    if (speed == currentSpeed) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
            
            // 视频信息
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.headlineSmall
                )
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.AccessTime,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = video.getDurationFormatted(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                if (video.description.isNotBlank()) {
                    HorizontalDivider()
                    
                    Text(
                        text = "简介",
                        style = MaterialTheme.typography.titleMedium
                    )
                    
                    Text(
                        text = video.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // 视频详情
                HorizontalDivider()
                
                Text(
                    text = "视频详情",
                    style = MaterialTheme.typography.titleMedium
                )
                
                video.metadata?.let { metadata ->
                    DetailRow("分块数", "${metadata.totalChunks} 块")
                    DetailRow("分块大小", formatSize(metadata.chunkSize.toLong()))
                    DetailRow("原始大小", formatSize(metadata.originalFileSize))
                    DetailRow("格式", metadata.mimeType)
                }
            }
        }
    }
}

/**
 * 快进/快退方向枚举
 */
private enum class SeekDirection {
    FORWARD, BACKWARD
}

/**
 * 快进/快退指示器组件
 */
@Composable
private fun SeekIndicator(isForward: Boolean) {
    Surface(
        color = Color.Black.copy(alpha = 0.6f),
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (isForward) {
                Icon(
                    Icons.Default.FastForward,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "10秒",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge
                )
            } else {
                Icon(
                    Icons.Default.FastRewind,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "10秒",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun formatSize(size: Long): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> String.format("%.1f KB", size / 1024.0)
        size < 1024 * 1024 * 1024 -> String.format("%.1f MB", size / (1024.0 * 1024))
        else -> String.format("%.2f GB", size / (1024.0 * 1024 * 1024))
    }
}

/**
 * 使用外部应用打开视频
 */
private fun openWithExternalPlayer(context: android.content.Context, url: String, title: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), "video/*")
            putExtra(Intent.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // 显示应用选择器
        val chooser = Intent.createChooser(intent, "选择播放器")
        context.startActivity(chooser)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "未找到可用的视频播放器", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "无法打开外部播放器: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

/**
 * 使用视频播放器打开（更精确的 MIME 类型）
 */
private fun openWithVideoPlayer(context: android.content.Context, url: String, title: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), "video/mp4")
            putExtra(Intent.EXTRA_TITLE, title)
            // 一些播放器支持的额外参数
            putExtra("title", title)
            putExtra("position", 0)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // 如果没有找到视频播放器，尝试使用通用方式
        openWithExternalPlayer(context, url, title)
    } catch (e: Exception) {
        Toast.makeText(context, "无法打开视频播放器: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

/**
 * 复制链接到剪贴板
 */
private fun copyToClipboard(context: android.content.Context, url: String) {
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val clip = android.content.ClipData.newPlainText("视频链接", url)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "链接已复制到剪贴板", Toast.LENGTH_SHORT).show()
}
