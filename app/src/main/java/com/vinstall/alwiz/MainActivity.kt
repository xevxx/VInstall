package com.vinstall.alwiz

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.vinstall.alwiz.appmanager.AppManagerActivity
import com.vinstall.alwiz.backup.BackupActivity
import com.vinstall.alwiz.databinding.ActivityMainBinding
import com.vinstall.alwiz.model.InstallState
import com.vinstall.alwiz.model.PackageFormat
import com.vinstall.alwiz.settings.AppSettings
import com.vinstall.alwiz.settings.DialogHelper
import com.vinstall.alwiz.settings.InstallMode
import com.vinstall.alwiz.settings.SettingsActivity
import com.vinstall.alwiz.history.InstallHistoryActivity
import com.vinstall.alwiz.shizuku.ShizukuHelper
import com.vinstall.alwiz.ui.ConfirmationBottomSheet
import com.vinstall.alwiz.util.CrashHandler
import com.vinstall.alwiz.util.DebugLog
import com.vinstall.alwiz.util.FileUtil
import com.vinstall.alwiz.util.NotificationHelper
import com.vinstall.alwiz.util.DeviceProfile
import com.vinstall.alwiz.util.TvFocus
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    companion object {
        /** Read-only FileProvider URIs accepted from the TV receive flow. */
        const val EXTRA_PACKAGE_URIS = "com.vinstall.alwiz.EXTRA_PACKAGE_URIS"
    }

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private var shizukuPermissionPending = false
    private var isActivityResumed = false
    private var isQueueMode = false

    private lateinit var queueAdapter: QueueFileAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        shizukuPermissionPending = false
        val granted = result == PackageManager.PERMISSION_GRANTED
        AppSettings.setShizukuPermissionGranted(this, granted)
        val msg = if (granted) getString(R.string.shizuku_granted) else getString(R.string.shizuku_denied)
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        updateInstallModeStatus()
    }

    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        updateInstallModeStatus()
        if (isActivityResumed) checkAndRequestShizukuPermission()
    }

    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        updateInstallModeStatus()
    }

    private val documentsPicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> handlePickedUris(uris) }

    private val tvFilePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        handlePickedUris(packageUrisFrom(result.data))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        NotificationHelper.createChannel(this)
        requestNotificationPermissionIfNeeded()
        CrashHandler.showCrashDialogIfNeeded(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        setSupportActionBar(binding.toolbar)
        if (DeviceProfile.isTv(this)) {
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
        }
        setupQueueRecycler()
        if (DeviceProfile.isTv(this)) TvFocus.installFocusableChildren(binding.layoutButtons)

        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
        Shizuku.addBinderDeadListener(shizukuBinderDeadListener)

        binding.btnSelect.setOnClickListener { openPackagePicker() }

        lifecycleScope.launch {
            viewModel.queueItems.collect { newItems ->
                if (newItems.isEmpty()) return@collect
                queueAdapter.setItems(newItems)
                if (isQueueMode) updateQueueControls()
            }
        }

        binding.btnInstall.setOnClickListener {
            if (isQueueMode) {
                val ordered = queueAdapter.getOrderedItems()
                if (ordered.isEmpty()) return@setOnClickListener
                viewModel.enqueueFiles(ordered)
                exitQueueMode()
            } else {
                handleSingleInstallClick()
            }
        }

        binding.btnCancel.setOnClickListener { viewModel.cancelInstall() }

        binding.btnSelectSplits.setOnClickListener {
            viewModel.loadSplitsIfNeeded()
            showSplitPicker()
        }

        binding.btnAppManager.setOnClickListener {
            startActivity(Intent(this, AppManagerActivity::class.java))
        }

        binding.btnBackup.setOnClickListener {
            startActivity(Intent(this, BackupActivity::class.java))
        }

        binding.btnDebug.setOnClickListener {
            startActivity(Intent(this, DebugWindowActivity::class.java))
        }

        lifecycleScope.launch { viewModel.state.collect { renderState(it) } }

        lifecycleScope.launch {
            viewModel.availableSplits.collect { splits ->
                val hasSplits = (viewModel.state.value as? InstallState.FileSelected)?.hasSplits ?: false
                binding.btnSelectSplits.isVisible = splits.isNotEmpty() || hasSplits
            }
        }

        lifecycleScope.launch {
            viewModel.batchProgress.collect { batch ->
                if (batch != null) {
                    binding.textBatchProgress.isVisible = true
                    val label = if (batch.label.isNotEmpty()) " · ${batch.label}" else ""
                    binding.textBatchProgress.text = getString(
                        R.string.batch_progress, batch.current, batch.total
                    ) + label
                } else {
                    binding.textBatchProgress.isVisible = false
                }
            }
        }

        updateInstallModeStatus()
        DebugLog.i("MainActivity", "Application started")

        if (savedInstanceState == null) {
            val hasPackageIntent = packageUrisFrom(intent).isNotEmpty() || intent.data != null
            handlePackageIntent(intent)
            if (DeviceProfile.isTv(this) && !hasPackageIntent) {
                binding.btnSelect.post { binding.btnSelect.requestFocus() }
            }
        }
    }

    private fun setupQueueRecycler() {
        queueAdapter = QueueFileAdapter(
            items = mutableListOf(),
            onStartDrag = { holder -> itemTouchHelper.startDrag(holder) },
            onItemClick = { item -> showBatchApkvPasswordDialog(item) },
            onQueueChanged = { updateQueueControls() },
            onItemRemoved = { uri -> viewModel.removeQueueItem(uri) },
        )
        itemTouchHelper = ItemTouchHelper(QueueDragCallback(queueAdapter))
        itemTouchHelper.attachToRecyclerView(binding.recyclerQueue)
        binding.recyclerQueue.layoutManager = LinearLayoutManager(this)
        binding.recyclerQueue.adapter = queueAdapter
        binding.recyclerQueue.isNestedScrollingEnabled = true
    }

    private fun openPackagePicker() {
        if (DeviceProfile.isTv(this)) {
            tvFilePicker.launch(Intent().setClassName(this, "com.vinstall.alwiz.TvFilePickerActivity"))
        } else {
            documentsPicker.launch(arrayOf("*/*"))
        }
    }

    private fun handlePickedUris(uris: List<Uri>) {
        when {
            uris.isEmpty() -> return
            uris.size == 1 -> {
                exitQueueMode()
                viewModel.onFileSelected(uris.first())
            }
            else -> enterQueueMode(uris)
        }
    }

    private fun packageUrisFrom(sourceIntent: Intent?): List<Uri> {
        if (sourceIntent == null) return emptyList()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            sourceIntent.getParcelableArrayListExtra(EXTRA_PACKAGE_URIS, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            sourceIntent.getParcelableArrayListExtra(EXTRA_PACKAGE_URIS)
        }.orEmpty()
    }

    private fun enterQueueMode(uris: List<Uri>) {
        isQueueMode = true
        queueAdapter.resetForNewQueue()
        binding.textQueueLabel.text = getString(R.string.queue_label, uris.size)
        viewModel.buildQueueItems(uris)
        binding.layoutEmptyState.isVisible = false
        binding.layoutFileInfo.isVisible = false
        binding.layoutQueueInfo.isVisible = true
        binding.btnInstall.isEnabled = true
        binding.btnSelect.isEnabled = true
        binding.btnCancel.isVisible = false
        binding.btnSelectSplits.isVisible = false
        updateQueueControls()
    }

    private fun updateQueueControls() {
        if (!isQueueMode) return
        val count = queueAdapter.itemCount
        binding.textQueueLabel.text = getString(R.string.queue_label, count)
        binding.btnInstall.isEnabled = count > 0
        if (count == 0 && DeviceProfile.isTv(this)) binding.btnSelect.post { binding.btnSelect.requestFocus() }
    }

    private fun exitQueueMode() {
        isQueueMode = false
        binding.layoutQueueInfo.isVisible = false
    }

    private fun handleSingleInstallClick() {
        if (!DeviceProfile.isTv(this) && needsStoragePermission()) {
            showStoragePermissionDialog()
            return
        }
        if (AppSettings.isConfirmInstall(this)) {
            val fileState = viewModel.state.value as? InstallState.FileSelected
            val installInfo = fileState?.let { s ->
                if (s.packageName.isNotEmpty()) {
                    val installed = getInstalledVersionInfo(s.packageName)
                    ConfirmationBottomSheet.AppInstallInfo(
                        icon = s.appIcon,
                        appLabel = s.appLabel,
                        packageName = s.packageName,
                        versionName = s.versionName,
                        versionCode = s.versionCode,
                        installedVersionName = installed?.first,
                        installedVersionCode = installed?.second,
                        minSdk = s.minSdk,
                        targetSdk = s.targetSdk
                    )
                } else null
            }
            DialogHelper.showConfirmation(
                activity = this,
                title = getString(R.string.confirm_install_title),
                message = getString(R.string.confirm_install_message),
                positiveLabel = getString(R.string.install),
                negativeLabel = getString(R.string.cancel),
                appInstallInfo = installInfo,
                onConfirm = { viewModel.install() }
            )
        } else {
            viewModel.install()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (DeviceProfile.isTv(this)) return false
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val debugEnabled = AppSettings.isDebugWindowEnabled(this)
        menu.findItem(R.id.action_debug)?.isVisible = debugEnabled
        val isShizukuMode = AppSettings.getInstallMode(this) == InstallMode.SHIZUKU
        menu.findItem(R.id.action_request_shizuku)?.isVisible = isShizukuMode
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                if (DeviceProfile.isTv(this)) finish()
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_history -> {
                startActivity(Intent(this, InstallHistoryActivity::class.java))
                true
            }
            R.id.action_debug -> {
                startActivity(Intent(this, DebugWindowActivity::class.java))
                true
            }
            R.id.action_request_shizuku -> {
                requestShizukuPermissionManually()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun checkAndRequestShizukuPermission() {
        val mode = AppSettings.getInstallMode(this)
        if (mode != InstallMode.SHIZUKU) return
        if (!ShizukuHelper.isAvailable()) return
        if (ShizukuHelper.isGranted()) return
        if (shizukuPermissionPending) return
        if (ShizukuHelper.shouldShowRationale()) return

        shizukuPermissionPending = true
        if (!ShizukuHelper.requestPermission(shizukuPermissionListener)) {
            shizukuPermissionPending = false
        }
    }

    private fun requestShizukuPermissionManually() {
        if (!ShizukuHelper.isAvailable()) {
            Toast.makeText(this, getString(R.string.shizuku_not_available_toast), Toast.LENGTH_SHORT).show()
            return
        }
        if (ShizukuHelper.isGranted()) {
            Toast.makeText(this, getString(R.string.shizuku_already_granted_toast), Toast.LENGTH_SHORT).show()
            updateInstallModeStatus()
            return
        }
        shizukuPermissionPending = true
        if (!ShizukuHelper.requestPermission(shizukuPermissionListener)) {
            shizukuPermissionPending = false
            Toast.makeText(this, getString(R.string.shizuku_not_available_toast), Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderState(state: InstallState) {
        DebugLog.d("MainActivity", "renderState: ${state::class.simpleName}")
        when (state) {
            is InstallState.Idle -> {
                if (!isQueueMode) {
                    binding.layoutEmptyState.isVisible = true
                    binding.layoutFileInfo.isVisible = false
                }
                binding.progressBar.isVisible = false
                binding.textStatus.isVisible = false
                binding.btnInstall.isEnabled = isQueueMode && queueAdapter.itemCount > 0
                binding.btnSelectSplits.isVisible = false
                binding.btnCancel.isVisible = false
                binding.btnSelect.isEnabled = true
            }
            is InstallState.FileLoading -> {
                exitQueueMode()
                binding.layoutEmptyState.isVisible = false
                binding.layoutFileInfo.isVisible = false
                binding.progressBar.isVisible = true
                binding.textStatus.isVisible = true
                binding.textStatus.text = getString(R.string.analyzing)
                binding.btnInstall.isEnabled = false
                binding.btnSelect.isEnabled = false
                binding.btnSelectSplits.isVisible = false
                binding.btnCancel.isVisible = false
            }
            is InstallState.FileSelected -> {
                binding.layoutEmptyState.isVisible = false
                binding.layoutFileInfo.isVisible = true
                binding.textFileName.text = if (state.appLabel.isNotEmpty()) state.appLabel else state.name

                if (state.appIcon != null) {
                    binding.imageAppIcon.setImageBitmap(state.appIcon)
                    binding.imageAppIcon.isVisible = true
                } else {
                    binding.imageAppIcon.isVisible = false
                }

                if (state.packageName.isNotEmpty()) {
                    binding.textPackageMeta.text = getString(R.string.package_meta, state.packageName, state.versionName)
                    binding.textPackageMeta.isVisible = true
                } else {
                    binding.textPackageMeta.isVisible = false
                }

                if (state.appLabel.isNotEmpty()) {
                    binding.textFilePath.text = state.name
                    binding.textFilePath.isVisible = true
                } else {
                    binding.textFilePath.isVisible = false
                }

                binding.textFormat.text = if (state.format == PackageFormat.UNKNOWN)
                    getString(R.string.unknown) else state.format.label
                binding.textSize.text = FileUtil.formatSize(state.size)

                if (state.hash.isNotEmpty()) {
                    binding.textHash.text = state.hash
                    binding.layoutHash.isVisible = true
                } else {
                    binding.layoutHash.isVisible = false
                }

                binding.progressBar.isVisible = false
                binding.textStatus.isVisible = false
                binding.btnInstall.isEnabled = state.format != PackageFormat.UNKNOWN
                binding.btnSelect.isEnabled = true
                binding.btnSelectSplits.isVisible = state.hasSplits
                binding.btnCancel.isVisible = false
            }
            is InstallState.Analyzing -> {
                binding.progressBar.isVisible = true
                binding.progressBar.isIndeterminate = true
                binding.textStatus.isVisible = true
                binding.textStatus.text = getString(R.string.analyzing)
                binding.btnInstall.isEnabled = false
                binding.btnSelect.isEnabled = false
                binding.btnSelectSplits.isEnabled = false
                binding.btnCancel.isVisible = true
            }
            is InstallState.Installing -> {
                binding.progressBar.isVisible = true
                binding.textStatus.isVisible = true
                binding.textStatus.text = if (state.progress >= 0f) {
                    val pct = (state.progress * 100).toInt()
                    "${state.step} ($pct%)"
                } else {
                    state.step
                }
                if (state.progress >= 0f) {
                    binding.progressBar.isIndeterminate = false
                    binding.progressBar.setProgressCompat((state.progress * 100).toInt(), true)
                } else {
                    binding.progressBar.isIndeterminate = true
                }
                binding.btnInstall.isEnabled = false
                binding.btnSelect.isEnabled = false
                binding.btnCancel.isVisible = true
            }
            is InstallState.Success -> {
                binding.progressBar.isVisible = false
                binding.textStatus.isVisible = true
                binding.textStatus.text = getString(R.string.install_success)
                binding.btnInstall.isEnabled = false
                binding.btnSelect.isEnabled = true
                binding.btnSelectSplits.isEnabled = true
                binding.btnCancel.isVisible = false
                if (viewModel.batchProgress.value == null) {
                    showInstallSuccessSnackbar(state.packageName)
                }
            }
            is InstallState.Error -> {
                binding.progressBar.isVisible = false
                binding.textStatus.isVisible = true
                binding.textStatus.text = getString(R.string.install_failed, state.message)
                binding.btnInstall.isEnabled = true
                binding.btnSelect.isEnabled = true
                binding.btnSelectSplits.isEnabled = true
                binding.btnCancel.isVisible = false
            }
            is InstallState.Cancelled -> {
                binding.progressBar.isVisible = false
                binding.textStatus.isVisible = true
                binding.textStatus.text = state.reason
                binding.btnInstall.isEnabled = true
                binding.btnSelect.isEnabled = true
                binding.btnSelectSplits.isEnabled = true
                binding.btnCancel.isVisible = false
            }
            is InstallState.PasswordRequired -> {
                binding.progressBar.isVisible = false
                binding.textStatus.isVisible = false
                binding.btnInstall.isEnabled = false
                binding.btnSelect.isEnabled = true
                binding.btnSelectSplits.isEnabled = false
                binding.btnCancel.isVisible = false
                showPasswordDialog(state)
            }
        }
        restoreTvFocus(state)
    }

    private fun restoreTvFocus(state: InstallState) {
        if (!DeviceProfile.isTv(this)) return
        binding.root.post {
            val focused = currentFocus
            if (focused != null && focused.isShown && focused.isEnabled && focused.isFocusable) return@post
            val target = when (state) {
                is InstallState.FileSelected,
                is InstallState.Error,
                is InstallState.Cancelled -> binding.btnInstall.takeIf { it.isShown && it.isEnabled }
                    ?: binding.btnSelect
                is InstallState.Installing,
                is InstallState.Analyzing -> binding.btnCancel.takeIf { it.isShown && it.isEnabled }
                else -> binding.btnSelect.takeIf { it.isShown && it.isEnabled }
            }
            target?.requestFocus()
        }
    }

    private fun handlePackageIntent(sourceIntent: Intent?) {
        if (sourceIntent == null) return
        val received = packageUrisFrom(sourceIntent)

        when {
            received.size == 1 -> {
                exitQueueMode()
                viewModel.onFileSelected(received.first())
            }
            received.size > 1 -> enterQueueMode(received)
            sourceIntent.data != null -> viewModel.onFileSelected(sourceIntent.data!!)
        }
        sourceIntent.removeExtra(EXTRA_PACKAGE_URIS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePackageIntent(intent)
    }

    private fun showPasswordDialog(state: InstallState.PasswordRequired) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_apkv_password, null)
        val layoutPassword = dialogView.findViewById<TextInputLayout>(R.id.layout_password)
        val editPassword = dialogView.findViewById<TextInputEditText>(R.id.edit_password)

        val title = if (state.label.isNotEmpty()) state.label else state.fileName
        val subtitle = if (state.packageName.isNotEmpty() && state.versionName.isNotEmpty())
            "${state.packageName} · v${state.versionName}" else state.packageName
        dialogView.findViewById<android.widget.TextView>(R.id.text_apkv_info).text = subtitle

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(dialogView)
            .setPositiveButton(getString(R.string.apkv_unlock), null)
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> viewModel.reset() }
            .setCancelable(false)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val password = editPassword.text?.toString()?.trim() ?: ""
                if (password.isBlank()) {
                    layoutPassword.error = getString(R.string.apkv_password_empty)
                    return@setOnClickListener
                }
                layoutPassword.error = null
                dialog.dismiss()
                viewModel.submitApkvPassword(password)
            }
        }

        dialog.show()
    }

    private fun showBatchApkvPasswordDialog(item: QueueItem) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_apkv_password, null)
        val layoutPassword = dialogView.findViewById<TextInputLayout>(R.id.layout_password)
        val editPassword = dialogView.findViewById<TextInputEditText>(R.id.edit_password)
        val title = item.appLabel.ifEmpty { item.displayName }
        val subtitle = if (item.packageName.isNotEmpty() && item.versionName.isNotEmpty())
            "${item.packageName} · v${item.versionName}" else item.packageName
        dialogView.findViewById<android.widget.TextView>(R.id.text_apkv_info).text = subtitle

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(dialogView)
            .setPositiveButton(getString(R.string.apkv_unlock), null)
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val password = editPassword.text?.toString()?.trim() ?: ""
                if (password.isBlank()) {
                    layoutPassword.error = getString(R.string.apkv_password_empty)
                    return@setOnClickListener
                }
                layoutPassword.error = null
                viewModel.submitBatchApkvPassword(item.uri, password) { valid ->
                    if (valid) {
                        dialog.dismiss()
                    } else {
                        layoutPassword.error = getString(R.string.apkv_wrong_password)
                    }
                }
            }
        }

        dialog.show()
    }

    private fun showSplitPicker() {
        val splits = viewModel.availableSplits.value
        val selected = viewModel.selectedSplits.value.toMutableList()
        val checkedItems = splits.map { selected.contains(it) }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.select_splits))
            .setMultiChoiceItems(splits.toTypedArray(), checkedItems) { _, which, isChecked ->
                viewModel.toggleSplit(splits[which], isChecked)
            }
            .setPositiveButton(getString(R.string.ok), null)
            .setNeutralButton(getString(R.string.select_all)) { _, _ -> viewModel.selectAllSplits() }
            .setNegativeButton(getString(R.string.deselect_all)) { _, _ -> viewModel.deselectAllSplits() }
            .show()
    }

    private fun updateInstallModeStatus() {
        val mode = AppSettings.getInstallMode(this)
        val shizukuAvail = ShizukuHelper.isAvailable()
        val shizukuGranted = ShizukuHelper.isGranted()

        val modeLabel = when (mode) {
            InstallMode.NORMAL -> getString(R.string.mode_normal)
            InstallMode.ROOT -> getString(R.string.mode_root)
            InstallMode.SHIZUKU -> getString(R.string.mode_shizuku)
        }

        binding.textInstallModeStatus.text = when (mode) {
            InstallMode.SHIZUKU -> when {
                !shizukuAvail -> "$modeLabel — ${getString(R.string.shizuku_inactive)}"
                !shizukuGranted -> "$modeLabel — ${getString(R.string.shizuku_needs_grant)}"
                else -> "$modeLabel — ${getString(R.string.shizuku_active)}"
            }
            else -> modeLabel
        }

        binding.installModeDot.setBackgroundResource(
            when (mode) {
                InstallMode.NORMAL, InstallMode.ROOT -> R.drawable.dot_active
                InstallMode.SHIZUKU -> if (shizukuAvail && shizukuGranted) R.drawable.dot_active else R.drawable.dot_pending
            }
        )
    }

    private fun getInstalledVersionInfo(packageName: String): Pair<String, Long>? {
        return try {
            val pm = packageManager
            val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pi.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pi.versionCode.toLong()
            }
            Pair(pi.versionName ?: "", code)
        } catch (_: Exception) {
            null
        }
    }

    private fun showInstallSuccessSnackbar(packageName: String) {
        val snackbar = Snackbar.make(binding.root, getString(R.string.install_success), Snackbar.LENGTH_LONG)
        if (packageName.isNotEmpty() && canLaunchPackage(packageName)) {
            snackbar.setAction(getString(R.string.open_app)) {
                val launch = packageManager.getLaunchIntentForPackage(packageName)
                if (launch != null) startActivity(launch)
            }
        }
        snackbar.show()
    }

    private fun canLaunchPackage(packageName: String): Boolean =
        packageManager.getLaunchIntentForPackage(packageName) != null

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
            }
        }
    }

    private fun needsStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val state = viewModel.state.value
        if (state is InstallState.FileSelected && state.format == PackageFormat.XAPK) {
            return !Environment.isExternalStorageManager()
        }
        return false
    }

    private fun showStoragePermissionDialog() {
        DialogHelper.showConfirmation(
            activity = this,
            title = getString(R.string.permission_required),
            message = getString(R.string.storage_permission_rationale),
            positiveLabel = getString(R.string.grant_permission),
            negativeLabel = getString(R.string.cancel),
            onConfirm = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        updateInstallModeStatus()
        checkAndRequestShizukuPermission()
        binding.btnDebug.isVisible = AppSettings.isDebugWindowEnabled(this)
        invalidateOptionsMenu()
    }

    override fun onPause() {
        super.onPause()
        isActivityResumed = false
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
        Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
        ShizukuHelper.removePermissionListener(shizukuPermissionListener)
    }
}
