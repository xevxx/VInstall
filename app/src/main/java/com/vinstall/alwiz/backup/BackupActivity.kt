package com.vinstall.alwiz.backup

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.vinstall.alwiz.R
import com.vinstall.alwiz.appmanager.AppListAdapter
import com.vinstall.alwiz.databinding.ActivityBackupBinding
import com.vinstall.alwiz.model.AppInfo
import com.vinstall.alwiz.transfer.ExportEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BackupActivity : AppCompatActivity() {

    companion object {
        const val ACTION_OPEN_RECEIVE = "com.vinstall.alwiz.action.OPEN_RECEIVE"
        const val EXTRA_EXPORT_ID = "com.vinstall.alwiz.extra.EXPORT_ID"
    }

    private lateinit var binding: ActivityBackupBinding
    private val viewModel: BackupViewModel by viewModels()
    private val adapter = AppListAdapter { app -> showExportDialog(app) }
    private var pendingSave: ExportEntry? = null
    private var shownExportId: String? = null

    private val saveExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { destination ->
        val export = pendingSave
        pendingSave = null
        if (destination == null || export == null) return@registerForActivityResult
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(destination, "w")?.use { output ->
                        export.file.inputStream().use { input -> input.copyTo(output) }
                    } ?: error("Could not open the selected destination")
                }.exceptionOrNull()
            }
            Toast.makeText(
                this@BackupActivity,
                error?.message ?: getString(R.string.export_saved),
                if (error == null) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBackupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.switchIncludeSystem.setOnCheckedChangeListener { _, checked ->
            viewModel.loadApps(checked)
        }

        lifecycleScope.launch {
            viewModel.apps.collect { list ->
                binding.progressBar.visibility = View.GONE
                adapter.submitList(list)
            }
        }

        lifecycleScope.launch {
            viewModel.backupState.collect { state ->
                when (state) {
                    is BackupState.Idle -> {
                        binding.progressBackup.visibility = View.GONE
                        binding.textBackupStatus.visibility = View.GONE
                    }
                    is BackupState.Running -> {
                        binding.progressBackup.visibility = View.VISIBLE
                        binding.textBackupStatus.visibility = View.VISIBLE
                        binding.textBackupStatus.text = state.step
                    }
                    is BackupState.Done -> {
                        binding.progressBackup.visibility = View.GONE
                        binding.textBackupStatus.visibility = View.VISIBLE
                        binding.textBackupStatus.text = getString(R.string.backup_success, state.export.displayName)
                        if (shownExportId != state.export.id) {
                            shownExportId = state.export.id
                            showExportResult(state.export)
                        }
                    }
                    is BackupState.Error -> {
                        binding.progressBackup.visibility = View.GONE
                        binding.textBackupStatus.visibility = View.VISIBLE
                        binding.textBackupStatus.text = getString(R.string.backup_failed, state.message)
                        Toast.makeText(this@BackupActivity, state.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        viewModel.loadApps(false)
    }

    private fun showExportDialog(app: AppInfo) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_export, null)
        val textInfo = dialogView.findViewById<android.widget.TextView>(R.id.text_export_info)
        val checkboxEncrypt = dialogView.findViewById<MaterialCheckBox>(R.id.checkbox_encrypt)
        val layoutPassword = dialogView.findViewById<TextInputLayout>(R.id.layout_password)
        val editPassword = dialogView.findViewById<TextInputEditText>(R.id.edit_password)

        textInfo.text = getString(R.string.export_info, app.label, app.versionName)

        checkboxEncrypt.setOnCheckedChangeListener { _, checked ->
            layoutPassword.visibility = if (checked) View.VISIBLE else View.GONE
            if (!checked) editPassword.text?.clear()
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.export_dialog_title))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.export_button)) { _, _ ->
                val password = if (checkboxEncrypt.isChecked) {
                    editPassword.text?.toString()?.takeIf { it.isNotBlank() }
                } else null
                startExport(app, password)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun startExport(app: AppInfo, password: String?) {
        viewModel.backup(app, password)
    }

    private fun showExportResult(export: ExportEntry) {
        val receiveIntent = Intent(ACTION_OPEN_RECEIVE).apply {
            setPackage(packageName)
            putExtra(EXTRA_EXPORT_ID, export.id)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.export_ready)
            .setMessage(getString(R.string.export_ready_message, export.displayName))
            .setPositiveButton(R.string.save_as) { _, _ ->
                pendingSave = export
                saveExportLauncher.launch(export.displayName)
            }
            .setNegativeButton(R.string.delete_export) { _, _ ->
                if (viewModel.deleteExport(export)) {
                    Toast.makeText(this, R.string.export_deleted, Toast.LENGTH_SHORT).show()
                }
            }
        if (receiveIntent.resolveActivity(packageManager) != null) {
            builder.setNeutralButton(R.string.download_from_browser) { _, _ ->
                startActivity(receiveIntent)
            }
        }
        builder.show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
