package com.yin2hao.myvideos.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.yin2hao.myvideos.data.model.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 设置存储仓库
 */
class SettingsRepository(private val context: Context) {
    
    private val gson = Gson()
    
    companion object {
        private val WEBDAV_URL = stringPreferencesKey("webdav_url")
        private val WEBDAV_USERNAME = stringPreferencesKey("webdav_username")
        private val WEBDAV_PASSWORD = stringPreferencesKey("webdav_password")
        private val MASTER_PASSWORD = stringPreferencesKey("master_password")
        private val CHUNK_SIZE_MB = intPreferencesKey("chunk_size_mb")
        private val REMOTE_BASE_PATH = stringPreferencesKey("remote_base_path")
    }
    
    val settingsFlow: Flow<Settings> = context.dataStore.data.map { preferences ->
        Settings(
            webdavUrl = preferences[WEBDAV_URL] ?: "",
            webdavUsername = preferences[WEBDAV_USERNAME] ?: "",
            webdavPassword = preferences[WEBDAV_PASSWORD] ?: "",
            masterPassword = preferences[MASTER_PASSWORD] ?: "",
            chunkSizeMB = preferences[CHUNK_SIZE_MB] ?: 5,
            remoteBasePath = preferences[REMOTE_BASE_PATH] ?: "/MyVideos/"
        )
    }
    
    suspend fun saveSettings(settings: Settings) {
        context.dataStore.edit { preferences ->
            preferences[WEBDAV_URL] = settings.webdavUrl
            preferences[WEBDAV_USERNAME] = settings.webdavUsername
            preferences[WEBDAV_PASSWORD] = settings.webdavPassword
            preferences[MASTER_PASSWORD] = settings.masterPassword
            preferences[CHUNK_SIZE_MB] = settings.chunkSizeMB
            preferences[REMOTE_BASE_PATH] = settings.remoteBasePath
        }
    }
    
    suspend fun updateWebDAVUrl(url: String) {
        context.dataStore.edit { preferences ->
            preferences[WEBDAV_URL] = url
        }
    }
    
    suspend fun updateWebDAVUsername(username: String) {
        context.dataStore.edit { preferences ->
            preferences[WEBDAV_USERNAME] = username
        }
    }
    
    suspend fun updateWebDAVPassword(password: String) {
        context.dataStore.edit { preferences ->
            preferences[WEBDAV_PASSWORD] = password
        }
    }
    
    suspend fun updateMasterPassword(password: String) {
        context.dataStore.edit { preferences ->
            preferences[MASTER_PASSWORD] = password
        }
    }
    
    suspend fun updateChunkSize(sizeMB: Int) {
        context.dataStore.edit { preferences ->
            preferences[CHUNK_SIZE_MB] = sizeMB
        }
    }
    
    suspend fun updateRemoteBasePath(path: String) {
        context.dataStore.edit { preferences ->
            preferences[REMOTE_BASE_PATH] = path
        }
    }
    
    /**
     * 导出设置为JSON字符串
     */
    fun exportSettings(settings: Settings): String {
        return gson.toJson(settings)
    }
    
    /**
     * 从JSON字符串导入设置
     */
    fun importSettings(json: String): Settings? {
        return try {
            gson.fromJson(json, Settings::class.java)
        } catch (e: Exception) {
            null
        }
    }
}
