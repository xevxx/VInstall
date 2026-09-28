package com.vinstall.alwiz.transfer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.card.MaterialCardView
import com.vinstall.alwiz.MainActivity
import com.vinstall.alwiz.R
import com.vinstall.alwiz.databinding.ActivityReceiveBinding
import kotlinx.coroutines.launch
import java.util.Locale

class ReceiveActivity : AppCompatActivity() {
    private lateinit var binding: ActivityReceiveBinding
    private lateinit var incomingRepository: IncomingPackageRepository
    private var server: TransferServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReceiveBinding.inflate(layoutInflater)
        setContentView(binding.root)
        incomingRepository = IncomingPackageRepository(this)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.openPackagesButton.setOnClickListener { openPackages() }
        binding.deletePackagesButton.setOnClickListener {
            incomingRepository.list().forEach { incomingRepository.delete(it.id) }
            renderPackages()
        }
        renderPackages()
        binding.openPackagesButton.requestFocus()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                server?.state?.collect(::renderState)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val transferServer = TransferServer(this, incomingRepository)
        server = transferServer
        transferServer.start()
    }

    override fun onStop() {
        server?.stop()
        server = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    private fun renderState(state: TransferSessionState) {
        val activeServer = server
        binding.addressText.text = activeServer?.url.orEmpty()
        binding.codeText.text = activeServer?.currentPairingCode().orEmpty()
        binding.statusText.text = when (state) {
            TransferSessionState.Stopped -> getString(R.string.transfer_status_stopped)
            is TransferSessionState.AwaitingPairing -> getString(R.string.transfer_status_waiting)
            is TransferSessionState.Paired -> getString(R.string.transfer_status_paired)
            is TransferSessionState.Uploading -> getString(
                R.string.transfer_status_uploading,
                state.fileName,
                formatBytes(state.receivedBytes),
            )
            is TransferSessionState.Completed -> getString(R.string.transfer_status_complete, state.packages.size)
            is TransferSessionState.Error -> getString(R.string.transfer_status_error, state.message)
        }
        if (state is TransferSessionState.Completed) renderPackages()
    }

    private fun renderPackages() {
        val packages = incomingRepository.list()
        binding.packageContainer.removeAllViews()
        binding.emptyPackagesText.visibility = if (packages.isEmpty()) View.VISIBLE else View.GONE
        packages.forEach { entry ->
            binding.packageContainer.addView(MaterialCardView(this).apply {
                radius = resources.getDimension(R.dimen.transfer_card_radius)
                strokeWidth = resources.getDimensionPixelSize(R.dimen.transfer_card_stroke)
                setStrokeColor(ContextCompat.getColor(this@ReceiveActivity, R.color.tv_card_stroke_idle))
                isFocusable = true
                isClickable = true
                setOnClickListener { openPackages(listOf(entry)) }
                setOnFocusChangeListener { view, focused ->
                    (view as MaterialCardView).setStrokeColor(
                        ContextCompat.getColor(
                            this@ReceiveActivity,
                            if (focused) R.color.primary else R.color.tv_card_stroke_idle,
                        ),
                    )
                }
                addView(TextView(this@ReceiveActivity).apply {
                    text = getString(R.string.transfer_package_item, entry.displayName, formatBytes(entry.size))
                    textSize = 18f
                    gravity = Gravity.CENTER_VERTICAL
                    setTextColor(ContextCompat.getColor(this@ReceiveActivity, R.color.on_surface))
                    setPadding(resources.getDimensionPixelSize(R.dimen.transfer_card_padding))
                })
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = resources.getDimensionPixelSize(R.dimen.transfer_item_spacing) }
            })
        }
        binding.openPackagesButton.isEnabled = packages.isNotEmpty()
        binding.deletePackagesButton.isEnabled = packages.isNotEmpty()
        binding.packageCountText.text = resources.getQuantityString(
            R.plurals.transfer_package_count,
            packages.size,
            packages.size,
        )
    }

    private fun openPackages(entries: List<IncomingPackageEntry> = incomingRepository.list().reversed()) {
        if (entries.isEmpty()) return
        val uris = ArrayList<Uri>(entries.map(incomingRepository::uriFor))
        startActivity(Intent(this, MainActivity::class.java).apply {
            putParcelableArrayListExtra(MainActivity.EXTRA_PACKAGE_URIS, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
