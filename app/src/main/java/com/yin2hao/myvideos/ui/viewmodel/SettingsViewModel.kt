package com.yin2hao.myvideos.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yin2hao.myvideos.data.model.ConnectionState
import com.yin2hao.myvideos.data.model.Settings
import com.yin2hao.myvideos.data.repository.SettingsRepository
import com.yin2hao.myvideos.network.WebDAVClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repository: SettingsRepository
) : ViewModel() {
    
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings
    
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Unknown)
    val connectionState: StateFlow<ConnectionState> = _connectionState
    
    init {
        viewModelScope.launch {
            repository.settingsFlow.collect {
                _settings.value = it
            }
        }
    }
    
    fun updateWebDAVUrl(url: String) {
        _settings.value = _settings.value.copy(webdavUrl = url)
        _connectionState.value = ConnectionState.Unknown
    }
    
    fun updateWebDAVUsername(username: String) {
        _settings.value = _settings.value.copy(webdavUsername = username)
        _connectionState.value = ConnectionState.Unknown
    }
    
    fun updateWebDAVPassword(password: String) {
        _settings.value = _settings.value.copy(webdavPassword = password)
        _connectionState.value = ConnectionState.Unknown
    }
    
    fun updateMasterPassword(password: String) {
        _settings.value = _settings.value.copy(masterPassword = password)
    }
    
    fun updateChunkSize(sizeMB: Int) {
        _settings.value = _settings.value.copy(chunkSizeMB = sizeMB)
    }
    
    fun updateRemoteBasePath(path: String) {
        _settings.value = _settings.value.copy(remoteBasePath = path)
    }

    fun updateDynamicColor(enabled: Boolean) {
        _settings.value = _settings.value.copy(dynamicColor = enabled)
    }
    
    fun testConnection() {
        viewModelScope.launch {
            _connectionState.value = ConnectionState.Testing
            
            val currentSettings = _settings.value
            if (currentSettings.webdavUrl.isBlank() ||
                currentSettings.webdavUsername.isBlank() ||
                currentSettings.webdavPassword.isBlank()) {
                _connectionState.value = ConnectionState.Failed("请填写完整的WebDAV信息")
                return@launch
            }
            
            val client = WebDAVClient(currentSettings)
            val result = client.testConnection()
            
            _connectionState.value = if (result.isSuccess) {
                ConnectionState.Success
            } else {
                ConnectionState.Failed(result.exceptionOrNull()?.message ?: "连接失败")
            }
        }
    }
    
    suspend fun saveSettings() {
        repository.saveSettings(_settings.value)
    }
    
    suspend fun importSettingsFromFile(context: Context, uri: Uri) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val json = inputStream?.bufferedReader()?.use { it.readText() } ?: return
            inputStream.close()
            
            val importedSettings = repository.importSettings(json)
            if (importedSettings != null) {
                _settings.value = importedSettings
                repository.saveSettings(importedSettings)
                _connectionState.value = ConnectionState.Unknown
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    suspend fun exportSettingsToFile(context: Context, uri: Uri) {
        try {
            val json = repository.exportSettings(_settings.value)
            val outputStream = context.contentResolver.openOutputStream(uri)
            outputStream?.bufferedWriter()?.use { it.write(json) }
            outputStream?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

class SettingsViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel(SettingsRepository(context)) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
