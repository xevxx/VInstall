package com.vinstall.alwiz.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.vinstall.alwiz.App
import com.vinstall.alwiz.R
import com.vinstall.alwiz.databinding.ActivitySettingsBinding
import com.vinstall.alwiz.shizuku.ShizukuHelper
import com.vinstall.alwiz.util.CrashHandler
import com.vinstall.alwiz.util.DebugLog
import com.vinstall.alwiz.util.DeviceProfile
import com.vinstall.alwiz.util.TvFocus
import com.vinstall.alwiz.settings.DialogHelper
import com.vinstall.alwiz.root.RootHelper
import com.vinstall.alwiz.history.InstallHistoryActivity
import com.vinstall.alwiz.history.InstallHistoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        val granted = result == PackageManager.PERMISSION_GRANTED
        val msg = if (granted) getString(R.string.shizuku_granted) else getString(R.string.shizuku_denied)
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        refreshStatusLabels()
    }

    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshStatusLabels()
    }

    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        refreshStatusLabels()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings)

        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
        Shizuku.addBinderDeadListener(shizukuBinderDeadListener)

        loadCurrentSettings()
        setupListeners()
        setupTvRemoteUi(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        refreshCrashLogStatus()
        refreshHistoryStatus()
    }

    private fun loadCurrentSettings() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) {
            binding.radioBtnShizuku.isEnabled = false
            binding.radioBtnShizuku.alpha = 0.4f
            binding.textShizukuStatus.text = getString(R.string.shizuku_not_supported)
            if (AppSettings.getInstallMode(this) == InstallMode.SHIZUKU) {
                AppSettings.setInstallMode(this, InstallMode.NORMAL)
            }
        }

        val mode = AppSettings.getInstallMode(this)
        updateModeUI(mode)

        binding.switchDebugWindow.isChecked = AppSettings.isDebugWindowEnabled(this)
        binding.switchClearCache.isChecked = AppSettings.isClearCacheAfterInstall(this)
        binding.switchConfirmInstall.isChecked = AppSettings.isConfirmInstall(this)

        val dialogStyle = AppSettings.getDialogStyle(this)
        binding.textCurrentDialogStyle.text = when (dialogStyle) {
            com.vinstall.alwiz.settings.DialogStyle.BOTTOM_SHEET -> getString(R.string.dialog_style_bottom_sheet)
            com.vinstall.alwiz.settings.DialogStyle.ALERT_DIALOG -> getString(R.string.dialog_style_alert_dialog)
        }

        val theme = AppSettings.getTheme(this)
        binding.textCurrentTheme.text = when (theme) {
            "light" -> getString(R.string.theme_light)
            "dark" -> getString(R.string.theme_dark)
            else -> getString(R.string.theme_system)
        }

        refreshStatusLabels()
        refreshCrashLogStatus()
        refreshHistoryStatus()
    }

    private fun updateModeUI(mode: InstallMode) {
        binding.radioBtnNormal.isChecked = mode == InstallMode.NORMAL
        binding.radioBtnRoot.isChecked = mode == InstallMode.ROOT
        binding.radioBtnShizuku.isChecked = mode == InstallMode.SHIZUKU
        DebugLog.d("Settings", "Mode UI updated: $mode")
    }

    private fun refreshStatusLabels() {
        val currentMode = AppSettings.getInstallMode(this)

        val shizukuAvail = ShizukuHelper.isAvailable()
        val shizukuGranted = ShizukuHelper.isGranted()

        binding.textShizukuStatus.text = when {
            !shizukuAvail -> getString(R.string.shizuku_inactive)
            shizukuGranted -> getString(R.string.shizuku_active)
            else -> getString(R.string.shizuku_needs_grant)
        }

        if (currentMode == InstallMode.SHIZUKU) {
            binding.btnRequestShizuku.isVisible = true
            binding.btnRequestShizuku.isEnabled = shizukuAvail && !shizukuGranted
        } else {
            binding.btnRequestShizuku.isVisible = false
        }

        if (currentMode == InstallMode.ROOT) {
            binding.textRootStatus.text = getString(R.string.root_checking)
            lifecycleScope.launch {
                val rooted = withContext(Dispatchers.IO) { RootHelper.isRooted() }
                binding.textRootStatus.text = if (rooted)
                    getString(R.string.root_available)
                else
                    getString(R.string.root_not_available)
            }
        } else {
            binding.textRootStatus.text = ""
        }
    }

    private fun refreshHistoryStatus() {
        val count = InstallHistoryManager.count(this)
        binding.textHistoryStatus.text = if (count > 0)
            getString(R.string.history_entry_count, count)
        else
            getString(R.string.history_empty)
        binding.btnViewHistory.isEnabled = count > 0
        binding.btnClearHistory.isEnabled = count > 0
    }

    private fun refreshCrashLogStatus() {
        val count = CrashHandler.crashLogEntryCount(this)
        val hasLog = count > 0
        binding.textCrashLogStatus.text = if (hasLog)
            getString(R.string.crash_log_has_entries, count)
        else
            getString(R.string.crash_log_empty)
        binding.btnViewCrashLog.isEnabled = hasLog
        binding.btnClearCrashLog.isEnabled = hasLog
    }

    private fun setupListeners() {
        binding.radioGroupMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.radio_btn_root -> InstallMode.ROOT
                R.id.radio_btn_shizuku -> InstallMode.SHIZUKU
                else -> InstallMode.NORMAL
            }
            AppSettings.setInstallMode(this, mode)
            DebugLog.i("Settings", "Install mode changed to: $mode")
            refreshStatusLabels()
        }

        binding.btnRequestShizuku.setOnClickListener {
            if (!ShizukuHelper.isAvailable()) {
                Toast.makeText(this, getString(R.string.shizuku_not_available_toast), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (ShizukuHelper.isGranted()) {
                Toast.makeText(this, getString(R.string.shizuku_already_granted_toast), Toast.LENGTH_SHORT).show()
                refreshStatusLabels()
                return@setOnClickListener
            }
            if (!ShizukuHelper.requestPermission(shizukuPermissionListener)) {
                Toast.makeText(this, getString(R.string.shizuku_not_available_toast), Toast.LENGTH_SHORT).show()
            }
        }

        binding.switchDebugWindow.setOnCheckedChangeListener { _, checked ->
            AppSettings.setDebugWindowEnabled(this, checked)
            DebugLog.i("Settings", "Debug window: $checked")
        }

        binding.switchClearCache.setOnCheckedChangeListener { _, checked ->
            AppSettings.setClearCacheAfterInstall(this, checked)
        }

        binding.switchConfirmInstall.setOnCheckedChangeListener { _, checked ->
            AppSettings.setConfirmInstall(this, checked)
        }

        binding.layoutTheme.setOnClickListener {
            showThemeDialog()
        }

        binding.layoutDialogStyle.setOnClickListener {
            showDialogStyleDialog()
        }

        binding.btnViewHistory.setOnClickListener {
            startActivity(android.content.Intent(this, InstallHistoryActivity::class.java))
        }

        binding.btnClearHistory.setOnClickListener {
            DialogHelper.showConfirmation(
                activity = this,
                title = getString(R.string.history_clear_title),
                message = getString(R.string.history_clear_confirm),
                positiveLabel = getString(R.string.history_clear_yes),
                negativeLabel = getString(R.string.cancel),
                isDangerous = true,
                onConfirm = {
                    InstallHistoryManager.clear(this)
                    refreshHistoryStatus()
                    Toast.makeText(this, getString(R.string.history_cleared), Toast.LENGTH_SHORT).show()
                }
            )
        }

        binding.btnViewCrashLog.setOnClickListener {
            val log = CrashHandler.readCrashLogTail(this)
            if (log.isNullOrBlank()) {
                Toast.makeText(this, getString(R.string.crash_log_empty), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showCrashLogDialog(log)
        }

        binding.btnClearCrashLog.setOnClickListener {
            DialogHelper.showConfirmation(
                activity = this,
                title = getString(R.string.crash_log_clear_title),
                message = getString(R.string.crash_log_clear_confirm),
                positiveLabel = getString(R.string.crash_log_clear_yes),
                negativeLabel = getString(R.string.cancel),
                isDangerous = true,
                onConfirm = {
                    CrashHandler.clearCrashLog(this)
                    refreshCrashLogStatus()
                    Toast.makeText(this, getString(R.string.crash_log_cleared), Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    private fun setupTvRemoteUi(savedInstanceState: Bundle?) {
        if (!DeviceProfile.isTv(this)) return
        TvFocus.installFocusableChildren(binding.root)

        binding.root.findViewWithTag<View>("settings_debug_row")?.setOnClickListener {
            binding.switchDebugWindow.isChecked = !binding.switchDebugWindow.isChecked
        }
        binding.root.findViewWithTag<View>("settings_clear_cache_row")?.setOnClickListener {
            binding.switchClearCache.isChecked = !binding.switchClearCache.isChecked
        }
        binding.root.findViewWithTag<View>("settings_confirm_row")?.setOnClickListener {
            binding.switchConfirmInstall.isChecked = !binding.switchConfirmInstall.isChecked
        }

        val restoredId = savedInstanceState?.getInt(STATE_FOCUSED_VIEW, View.NO_ID) ?: View.NO_ID
        binding.root.post {
            val restored = restoredId.takeIf { it != View.NO_ID }?.let { findViewById<View>(it) }
            val checkedMode = findViewById<View>(binding.radioGroupMode.checkedRadioButtonId)
            (restored?.takeIf { it.isShown && it.isEnabled } ?: checkedMode)?.requestFocus()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (DeviceProfile.isTv(this)) outState.putInt(STATE_FOCUSED_VIEW, currentFocus?.id ?: View.NO_ID)
    }

    private fun showCrashLogDialog(log: String) {
        val tv = TextView(this).apply {
            text = log
            textSize = 10.5f
            setPadding(32, 24, 32, 24)
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val scroll = ScrollView(this).apply { addView(tv) }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.crash_log_title))
            .setView(scroll)
            .setPositiveButton(getString(R.string.crash_log_copy)) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("VInstall Crash Log", log))
                Toast.makeText(this, getString(R.string.log_copied), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.crash_log_close), null)
            .show()
    }

    private fun showDialogStyleDialog() {
        val styles = arrayOf(
            getString(R.string.dialog_style_bottom_sheet),
            getString(R.string.dialog_style_alert_dialog)
        )
        val keys = arrayOf(DialogStyle.BOTTOM_SHEET, DialogStyle.ALERT_DIALOG)
        val current = AppSettings.getDialogStyle(this)
        val idx = keys.indexOf(current).coerceAtLeast(0)

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.choose_dialog_style))
            .setSingleChoiceItems(styles, idx) { dialog, which ->
                AppSettings.setDialogStyle(this, keys[which])
                binding.textCurrentDialogStyle.text = styles[which]
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()
        focusChoiceDialog(dialog, idx)
    }

    private fun showThemeDialog() {
        val themes = arrayOf(
            getString(R.string.theme_system),
            getString(R.string.theme_light),
            getString(R.string.theme_dark)
        )
        val keys = arrayOf("system", "light", "dark")
        val current = AppSettings.getTheme(this)
        val idx = keys.indexOf(current).coerceAtLeast(0)

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.choose_theme))
            .setSingleChoiceItems(themes, idx) { dialog, which ->
                AppSettings.setTheme(this, keys[which])
                binding.textCurrentTheme.text = themes[which]
                applyTheme(keys[which])
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()
        focusChoiceDialog(dialog, idx)
    }

    private fun focusChoiceDialog(dialog: AlertDialog, checkedIndex: Int) {
        dialog.setOnShowListener {
            dialog.listView?.apply {
                setItemChecked(checkedIndex, true)
                setSelection(checkedIndex)
                requestFocus()
            }
        }
        dialog.show()
    }

    private fun applyTheme(theme: String) {
        App.applyTheme(theme)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
        Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
        ShizukuHelper.removePermissionListener(shizukuPermissionListener)
    }

    companion object {
        private const val STATE_FOCUSED_VIEW = "settings_focused_view"
    }
}
