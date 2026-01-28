package com.yin2hao.myvideos.ui.screens

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yin2hao.myvideos.data.model.ConnectionState
import com.yin2hao.myvideos.ui.viewmodel.SettingsViewModel
import com.yin2hao.myvideos.ui.viewmodel.SettingsViewModelFactory
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModelFactory(context)
    )
    
    val settings by viewModel.settings.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val scope = rememberCoroutineScope()
    
    // 文件选择器
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                viewModel.importSettingsFromFile(context, it)
            }
        }
    }
    
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                viewModel.exportSettingsToFile(context, it)
            }
        }
    }
    
    var showPassword by remember { mutableStateOf(false) }
    var showMasterPassword by remember { mutableStateOf(false) }
    var showWebDAVPassword by remember { mutableStateOf(false) }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 界面设置卡片
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
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
                        Icon(Icons.Default.Palette, contentDescription = null)
                        Text(
                            text = "界面设置",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "莫奈取色 (Dynamic Color)")
                            Text(
                                text = "使用壁纸颜色作为应用主题色",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.dynamicColor,
                            onCheckedChange = { viewModel.updateDynamicColor(it) }
                        )
                    }
                }
            }
        }

        // WebDAV 设置卡片
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
                    Icon(Icons.Default.Cloud, contentDescription = null)
                    Text(
                        text = "WebDAV 服务器设置",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    // 连接状态指示器
                    ConnectionIndicator(state = connectionState)
                }
                
                OutlinedTextField(
                    value = settings.webdavUrl,
                    onValueChange = { viewModel.updateWebDAVUrl(it) },
                    label = { Text("服务器地址") },
                    placeholder = { Text("https://example.com/dav") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) }
                )
                
                OutlinedTextField(
                    value = settings.webdavUsername,
                    onValueChange = { viewModel.updateWebDAVUsername(it) },
                    label = { Text("用户名") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) }
                )
                
                OutlinedTextField(
                    value = settings.webdavPassword,
                    onValueChange = { viewModel.updateWebDAVPassword(it) },
                    label = { Text("密码") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (showWebDAVPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { showWebDAVPassword = !showWebDAVPassword }) {
                            Icon(
                                if (showWebDAVPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showWebDAVPassword) "隐藏密码" else "显示密码"
                            )
                        }
                    }
                )
                
                OutlinedTextField(
                    value = settings.remoteBasePath,
                    onValueChange = { viewModel.updateRemoteBasePath(it) },
                    label = { Text("远程存储路径") },
                    placeholder = { Text("/MyVideos/") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) }
                )
                
                Button(
                    onClick = { viewModel.testConnection() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = connectionState !is ConnectionState.Testing
                ) {
                    if (connectionState is ConnectionState.Testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(if (connectionState is ConnectionState.Testing) "测试中..." else "测试连接")
                }
                
                // 显示连接错误信息
                if (connectionState is ConnectionState.Failed) {
                    Text(
                        text = (connectionState as ConnectionState.Failed).message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        
        // 加密设置卡片
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
                    Icon(Icons.Default.Security, contentDescription = null)
                    Text(
                        text = "加密设置",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                
                OutlinedTextField(
                    value = settings.masterPassword,
                    onValueChange = { viewModel.updateMasterPassword(it) },
                    label = { Text("主密码") },
                    supportingText = { Text("用于加密视频目录文件，请牢记") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (showMasterPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { showMasterPassword = !showMasterPassword }) {
                            Icon(
                                if (showMasterPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showMasterPassword) "隐藏密码" else "显示密码"
                            )
                        }
                    }
                )
            }
        }
        
        // 上传设置卡片
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
                    Icon(Icons.Default.Upload, contentDescription = null)
                    Text(
                        text = "上传设置",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                
                OutlinedTextField(
                    value = settings.chunkSizeMB.toString(),
                    onValueChange = { 
                        it.toIntOrNull()?.let { size ->
                            if (size in 1..100) {
                                viewModel.updateChunkSize(size)
                            }
                        }
                    },
                    label = { Text("分块大小 (MB)") },
                    supportingText = { Text("建议 1-100 MB，默认 5 MB") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    leadingIcon = { Icon(Icons.Default.Storage, contentDescription = null) }
                )
            }
        }
        
        // 配置导入导出卡片
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
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Text(
                        text = "配置管理",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { importLauncher.launch("application/json") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.FileUpload, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("导入配置")
                    }
                    
                    OutlinedButton(
                        onClick = { exportLauncher.launch("myvideos_settings.json") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("导出配置")
                    }
                }
            }
        }
        
        // 保存按钮
        Button(
            onClick = {
                scope.launch {
                    viewModel.saveSettings()
                    Toast.makeText(context, "设置已保存", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Save, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("保存设置")
        }
    }
}

@Composable
private fun ConnectionIndicator(state: ConnectionState) {
    val color = when (state) {
        is ConnectionState.Success -> Color.Green
        is ConnectionState.Failed -> Color.Red
        is ConnectionState.Testing -> Color.Yellow
        is ConnectionState.Unknown -> Color.Gray
    }
    
    Box(
        modifier = Modifier
            .size(16.dp)
            .clip(CircleShape)
            .background(color)
    )
}
