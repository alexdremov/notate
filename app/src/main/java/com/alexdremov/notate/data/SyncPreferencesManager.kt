package com.alexdremov.notate.data

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.protobuf.ProtoBuf

object SyncPreferencesManager {
    private const val PREFS_NAME = "sync_prefs"
    private const val ENCRYPTED_PREFS_NAME = "secure_sync_prefs"
    private const val KEY_REMOTE_STORAGES = "remote_storages"
    private const val KEY_PROJECT_SYNC_CONFIGS = "project_sync_configs"

    private val protoBuf = ProtoBuf

    private fun getEncryptedPrefs(context: Context) =
        try {
            val masterKey =
                MasterKey
                    .Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

            EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            // Fallback or log error
            context.getSharedPreferences(ENCRYPTED_PREFS_NAME, Context.MODE_PRIVATE)
        }

    private fun getPrefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getRemoteStorages(context: Context): List<RemoteStorageConfig> {
        val data = getPrefs(context).getString(KEY_REMOTE_STORAGES, null) ?: return emptyList()
        return try {
            val bytes = Base64.decode(data, Base64.DEFAULT)
            protoBuf.decodeFromByteArray(ListSerializer(RemoteStorageConfig.serializer()), bytes)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveRemoteStorages(
        context: Context,
        storages: List<RemoteStorageConfig>,
    ) {
        val bytes = protoBuf.encodeToByteArray(ListSerializer(RemoteStorageConfig.serializer()), storages)
        val string = Base64.encodeToString(bytes, Base64.DEFAULT)
        getPrefs(context).edit().putString(KEY_REMOTE_STORAGES, string).apply()
    }

    fun getPassword(
        context: Context,
        storageId: String,
    ): String? = getEncryptedPrefs(context).getString(storageId, null)

    fun savePassword(
        context: Context,
        storageId: String,
        password: String,
    ) {
        getEncryptedPrefs(context).edit().putString(storageId, password).apply()
    }

    fun deletePassword(
        context: Context,
        storageId: String,
    ) {
        getEncryptedPrefs(context).edit().remove(storageId).apply()
    }

    fun getProjectSyncConfigs(context: Context): List<ProjectSyncConfig> {
        val data = getPrefs(context).getString(KEY_PROJECT_SYNC_CONFIGS, null) ?: return emptyList()
        return try {
            val bytes = Base64.decode(data, Base64.DEFAULT)
            protoBuf.decodeFromByteArray(ListSerializer(ProjectSyncConfig.serializer()), bytes)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getProjectSyncConfig(
        context: Context,
        projectId: String,
    ): ProjectSyncConfig? =
        getProjectSyncConfigs(context).find {
            it.projectId == projectId
        }

    fun saveProjectSyncConfigs(
        context: Context,
        configs: List<ProjectSyncConfig>,
    ) {
        val bytes = protoBuf.encodeToByteArray(ListSerializer(ProjectSyncConfig.serializer()), configs)
        val string = Base64.encodeToString(bytes, Base64.DEFAULT)
        getPrefs(context).edit().putString(KEY_PROJECT_SYNC_CONFIGS, string).apply()
    }

    fun updateProjectSyncConfig(
        context: Context,
        config: ProjectSyncConfig,
    ) {
        val configs = getProjectSyncConfigs(context).toMutableList()
        val index = configs.indexOfFirst { it.projectId == config.projectId }
        if (index != -1) {
            configs[index] = config
        } else {
            configs.add(config)
        }
        saveProjectSyncConfigs(context, configs)
    }
}
