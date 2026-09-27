package com.vinstall.alwiz.backup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vinstall.alwiz.model.AppInfo
import com.vinstall.alwiz.apkv.ApkvExporter
import com.vinstall.alwiz.transfer.ExportEntry
import com.vinstall.alwiz.transfer.ExportRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

class BackupViewModel(app: Application) : AndroidViewModel(app) {

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps

    private val _backupState = MutableStateFlow<BackupState>(BackupState.Idle)
    val backupState: StateFlow<BackupState> = _backupState

    fun loadApps(includeSystem: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val list = BackupManager.listInstalledApps(getApplication(), includeSystem)
            _apps.value = list
        }
    }

    fun backup(app: AppInfo, password: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val repository = ExportRepository(getApplication())
            repository.cleanupExpired()
            val outputDir = File(getApplication<Application>().filesDir, "exports")
            val result = ApkvExporter.export(
                context = getApplication(),
                appInfo = app,
                outputDir = outputDir,
                password = password
            ) { step ->
                _backupState.value = BackupState.Running(step)
            }
            _backupState.value = if (result.isSuccess) {
                try {
                    val exportedFile = requireNotNull(result.getOrNull())
                    val entry = repository.register(exportedFile)
                    if (exportedFile.canonicalFile != entry.file.canonicalFile) exportedFile.delete()
                    BackupState.Done(entry)
                } catch (e: Exception) {
                    BackupState.Error(e.message ?: "Could not register export")
                }
            } else {
                BackupState.Error(result.exceptionOrNull()?.message ?: "Unknown error")
            }
        }
    }

    fun deleteExport(export: ExportEntry): Boolean {
        val deleted = ExportRepository(getApplication()).delete(export.id)
        if (deleted) _backupState.value = BackupState.Idle
        return deleted
    }
}
