package com.vinstall.alwiz.backup

import com.vinstall.alwiz.transfer.ExportEntry

sealed class BackupState {
    object Idle : BackupState()
    data class Running(val step: String) : BackupState()
    data class Done(val export: ExportEntry) : BackupState()
    data class Error(val message: String) : BackupState()
}
