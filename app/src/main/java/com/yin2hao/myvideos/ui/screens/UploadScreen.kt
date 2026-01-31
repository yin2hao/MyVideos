package com.yin2hao.myvideos.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.yin2hao.myvideos.data.model.UploadState
import com.yin2hao.myvideos.ui.viewmodel.UploadViewModel
import com.yin2hao.myvideos.ui.viewmodel.UploadViewModelFactory
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UploadScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewModel: UploadViewModel = viewModel(
        factory = UploadViewModelFactory(context)
    )
    
    val selectedVideoUri by viewModel.selectedVideoUri.collectAsState()
    val videoInfo by viewModel.videoInfo.collectAsState()
    val title by viewModel.title.collectAsState()
    val description by viewModel.description.collectAsState()
    val tags by viewModel.tags.collectAsState()
    val tagInput by viewModel.tagInput.collectAsState()
    val uploadState by viewModel.uploadState.collectAsState()
    val settingsValid by viewModel.settingsValid.collectAsState()
    val resultConsumed by viewModel.resultConsumed.collectAsState()
    val scope = rememberCoroutineScope()
    
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.selectVideo(it)
        }
    }
    
    LaunchedEffect(uploadState, resultConsumed) {
        if (!resultConsumed) {
            when (uploadState) {
                is UploadState.Success -> {
                    Toast.makeText(context, "上传成功！", Toast.LENGTH_SHORT).show()
                    viewModel.consumeResult()
                }
                is UploadState.Error -> {
                    Toast.makeText(context, (uploadState as UploadState.Error).message, Toast.LENGTH_LONG).show()
                    viewModel.consumeResult()
                }
                else -> {}
            }
        }
    }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 设置检查提示
        if (!settingsValid) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = "请先在设置中配置WebDAV服务器和主密码",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        
        // 视频选择区域
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.VideoFile, contentDescription = null)
                    Text(
                        text = "选择视频",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                
                // 视频选择/预览区域
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(enabled = uploadState is UploadState.Idle || uploadState is UploadState.Success || uploadState is UploadState.Error) {
                            videoPickerLauncher.launch("video/*")
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (selectedVideoUri != null && videoInfo != null) {
                        // 显示视频封面（如果有）
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Default.Movie,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "已选择视频",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = formatFileSize(videoInfo!!.fileSize),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = formatDuration(videoInfo!!.duration),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "点击选择视频",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        
        // 视频信息输入
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Text(
                        text = "视频信息",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                
                OutlinedTextField(
                    value = title,
                    onValueChange = { viewModel.updateTitle(it) },
                    label = { Text("视频标题") },
                    placeholder = { Text("输入视频标题") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = uploadState is UploadState.Idle || uploadState is UploadState.Success || uploadState is UploadState.Error
                )
                
                OutlinedTextField(
                    value = description,
                    onValueChange = { viewModel.updateDescription(it) },
                    label = { Text("视频描述") },
                    placeholder = { Text("输入视频描述（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 5,
                    enabled = uploadState is UploadState.Idle || uploadState is UploadState.Success || uploadState is UploadState.Error
                )
                
                // 标签输入
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = tagInput,
                            onValueChange = { viewModel.updateTagInput(it) },
                            label = { Text("添加标签") },
                            placeholder = { Text("输入标签后按回车") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            enabled = uploadState is UploadState.Idle || uploadState is UploadState.Success || uploadState is UploadState.Error,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    if (tagInput.isNotBlank()) {
                                        viewModel.addTag(tagInput)
                                    }
                                }
                            ),
                            trailingIcon = {
                                if (tagInput.isNotBlank()) {
                                    IconButton(onClick = { viewModel.addTag(tagInput) }) {
                                        Icon(Icons.Default.Add, contentDescription = "添加标签")
                                    }
                                }
                            }
                        )
                    }
                    
                    // 已添加的标签列表
                    if (tags.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            tags.forEach { tag ->
                                InputChip(
                                    onClick = { },
                                    label = { Text(tag) },
                                    selected = false,
                                    trailingIcon = {
                                        IconButton(
                                            onClick = { viewModel.removeTag(tag) },
                                            modifier = Modifier.size(18.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "移除标签",
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
        
        // 上传进度
        if (uploadState !is UploadState.Idle && uploadState !is UploadState.Success && uploadState !is UploadState.Error) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = getUploadStateText(uploadState),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    
                    when (uploadState) {
                        is UploadState.Encrypting -> {
                            val state = uploadState as UploadState.Encrypting
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = "加密进度: ${(state.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        is UploadState.Uploading -> {
                            val state = uploadState as UploadState.Uploading
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = "上传进度: ${(state.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        else -> {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
        
        // 上传按钮
        Button(
            onClick = {
                scope.launch {
                    viewModel.startUpload()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = selectedVideoUri != null && 
                     title.isNotBlank() && 
                     settingsValid &&
                     (uploadState is UploadState.Idle || uploadState is UploadState.Success || uploadState is UploadState.Error)
        ) {
            Icon(Icons.Default.CloudUpload, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("确认上传")
        }
        
        // 重置按钮（上传成功或失败后显示）
        if (uploadState is UploadState.Success || uploadState is UploadState.Error) {
            OutlinedButton(
                onClick = { viewModel.reset() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("重新上传")
            }
        }
    }
}

private fun getUploadStateText(state: UploadState): String {
    return when (state) {
        is UploadState.Idle -> "准备就绪"
        is UploadState.Preparing -> "准备中..."
        is UploadState.Encrypting -> "正在加密..."
        is UploadState.Uploading -> "正在上传..."
        is UploadState.UploadingMetadata -> "正在上传元数据..."
        is UploadState.UploadingCover -> "正在上传封面..."
        is UploadState.Success -> "上传成功"
        is UploadState.Error -> "上传失败: ${state.message}"
    }
}

private fun formatFileSize(size: Long): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> String.format("%.1f KB", size / 1024.0)
        size < 1024 * 1024 * 1024 -> String.format("%.1f MB", size / (1024.0 * 1024))
        else -> String.format("%.2f GB", size / (1024.0 * 1024 * 1024))
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
