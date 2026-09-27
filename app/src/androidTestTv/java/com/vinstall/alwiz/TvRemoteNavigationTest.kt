package com.vinstall.alwiz

import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import com.google.android.material.card.MaterialCardView
import com.vinstall.alwiz.history.InstallHistoryActivity
import com.vinstall.alwiz.history.InstallHistoryManager
import com.vinstall.alwiz.model.HistoryStatus
import com.vinstall.alwiz.model.InstallHistoryEntry
import com.vinstall.alwiz.settings.SettingsActivity
import com.vinstall.alwiz.settings.AppSettings
import com.vinstall.alwiz.settings.DialogStyle

@RunWith(AndroidJUnit4::class)
class TvRemoteNavigationTest {
    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun dashboardDpadOpensInstallAndReceiveAndBackReturnsHome() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ActivityScenario.launch(TvHomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(activity.getString(R.string.tv_install_title), focusedTitle(activity.currentFocus))
            }

            device.pressDPadCenter()
            assertTrue(device.wait(Until.hasObject(By.text("Select File")), TIMEOUT_MS))
            assertTrue(device.findObject(By.res(context.packageName, "btn_select")).isFocused)
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.text("Install packages")), TIMEOUT_MS))

            device.pressDPadRight()
            device.pressDPadCenter()
            assertTrue(device.wait(Until.hasObject(By.text("Receive packages")), TIMEOUT_MS))
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.text("Install packages")), TIMEOUT_MS))
        }
    }

    @Test
    fun dashboardRestoresAfterConfigurationRecreation() {
        ActivityScenario.launch(TvHomeActivity::class.java).use { scenario ->
            scenario.recreate()
            assertTrue(device.wait(Until.hasObject(By.text("Install packages")), TIMEOUT_MS))
            scenario.onActivity { activity ->
                assertTrue(activity.currentFocus?.isFocusable == true)
            }
        }
    }

    @Test
    fun dashboardFocusIsNeutralAndNeverChangesBounds() {
        ActivityScenario.launch(TvHomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val focused = activity.currentFocus as MaterialCardView
                assertEquals(1f, focused.scaleX)
                assertEquals(1f, focused.scaleY)
                assertEquals(
                    ContextCompat.getColor(activity, R.color.tv_focus_surface),
                    focused.cardBackgroundColor.defaultColor,
                )
                assertContainedBy(focused, activity.window.decorView)
            }
            device.pressDPadRight()
            scenario.onActivity { activity ->
                val focused = activity.currentFocus as MaterialCardView
                assertEquals(1f, focused.scaleX)
                assertEquals(1f, focused.scaleY)
                assertContainedBy(focused, activity.window.decorView)
            }
        }
    }

    @Test
    fun settingsRowsAreRemoteFocusableAndToggleSwitches() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val originalDebug = AppSettings.isDebugWindowEnabled(context)
        try {
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val debugRow = activity.window.decorView.findViewWithTag<View>("settings_debug_row")
                    val clearCacheRow = activity.window.decorView.findViewWithTag<View>("settings_clear_cache_row")
                    val confirmRow = activity.window.decorView.findViewWithTag<View>("settings_confirm_row")
                    listOf(debugRow, clearCacheRow, confirmRow).forEach {
                        requireNotNull(it)
                        assertTrue(it.isFocusable)
                        assertEquals(1f, it.scaleX)
                        assertEquals(1f, it.scaleY)
                        assertContainedBy(it, activity.window.decorView)
                    }
                    val toggle = activity.findViewById<android.widget.CompoundButton>(R.id.switch_debug_window)
                    val before = toggle.isChecked
                    debugRow.performClick()
                    assertEquals(!before, toggle.isChecked)
                }
                scenario.recreate()
                scenario.onActivity { activity -> assertTrue(activity.currentFocus?.isFocusable == true) }
            }
        } finally {
            AppSettings.setDebugWindowEnabled(context, originalDebug)
        }
    }

    @Test
    fun emptyHistoryMovesFocusToBoundedNavigationTarget() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        InstallHistoryManager.clear(context)
        ActivityScenario.launch(InstallHistoryActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.text_empty).visibility == View.VISIBLE)
                val focused = activity.currentFocus
                requireNotNull(focused)
                assertTrue(focused.isFocusable)
                assertEquals(1f, focused.scaleX)
                assertEquals(1f, focused.scaleY)
                assertContainedBy(focused, activity.window.decorView)
            }
        }
    }

    @Test
    fun historyDetailsAndRemoveLastRemainRemoteOperable() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val originalStyle = AppSettings.getDialogStyle(context)
        InstallHistoryManager.clear(context)
        AppSettings.setDialogStyle(context, DialogStyle.ALERT_DIALOG)
        InstallHistoryManager.add(context, historyEntry("older", "Older app", 1L))
        InstallHistoryManager.add(context, historyEntry("newest", "Newest app", 2L))

        try {
            ActivityScenario.launch(InstallHistoryActivity::class.java).use { scenario ->
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    val recycler = activity.findViewById<RecyclerView>(R.id.recycler_history)
                    requireNotNull(recycler.findViewHolderForAdapterPosition(0)).itemView.performClick()
                }

                val close = device.wait(
                    Until.findObject(By.text(context.getString(R.string.crash_log_close))),
                    TIMEOUT_MS,
                )
                requireNotNull(close)
                assertTrue(close.isFocused)
                device.pressDPadCenter()
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()

                scenario.onActivity { activity -> deleteHistoryRow(activity, 0) }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    val recycler = activity.findViewById<RecyclerView>(R.id.recycler_history)
                    assertEquals(1, recycler.adapter?.itemCount)
                    val focusedHolder = activity.currentFocus?.let(recycler::findContainingViewHolder)
                    assertEquals(0, focusedHolder?.bindingAdapterPosition)
                    deleteHistoryRow(activity, 0)
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    assertTrue(activity.findViewById<View>(R.id.text_empty).visibility == View.VISIBLE)
                    assertTrue(activity.findViewById<View>(R.id.toolbar).hasFocus())
                }
            }
        } finally {
            InstallHistoryManager.clear(context)
            AppSettings.setDialogStyle(context, originalStyle)
        }
    }

    @Test
    fun pickerSystemFolderControlIsBoundedAndSessionOnly() {
        ActivityScenario.launch(TvFilePickerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val toggle = activity.findViewById<com.google.android.material.button.MaterialButton>(
                    R.id.show_system_button
                )
                toggle.requestFocus()
                assertEquals(activity.getString(R.string.tv_picker_show_system), toggle.text.toString())
                toggle.performClick()
                assertEquals(activity.getString(R.string.tv_picker_hide_system), toggle.text.toString())
                assertEquals(1f, toggle.scaleX)
                assertEquals(1f, toggle.scaleY)
                assertContainedBy(toggle, activity.window.decorView)
            }
        }
    }

    @Test
    fun queueRemoteMoveDownChangesInstallationOrder() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val testDirectory = File(context.filesDir, "tv_queue_test_${System.nanoTime()}").apply { mkdirs() }
        val first = File(testDirectory, "first.apk").apply { writeBytes(byteArrayOf(1)) }
        val second = File(testDirectory, "second.apk").apply { writeBytes(byteArrayOf(2)) }
        val uris = arrayListOf(uriFor(context, first), uriFor(context, second))
        val intent = Intent(context, MainActivity::class.java).apply {
            putParcelableArrayListExtra(MainActivity.EXTRA_PACKAGE_URIS, uris)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    val recycler = activity.findViewById<RecyclerView>(R.id.recycler_queue)
                    recycler.measure(
                        View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                    )
                    recycler.layout(0, 0, 1280, 720)
                    recycler.scrollToPosition(0)
                    val adapter = recycler.adapter as QueueFileAdapter
                    assertEquals(uris, ArrayList(adapter.getOrderedUris()))

                    val moveDown = recycler.findViewHolderForAdapterPosition(0)
                        ?.itemView?.findViewWithTag<View>("queue_move_down")
                    requireNotNull(moveDown).performClick()

                    assertEquals(listOf(uris[1], uris[0]), adapter.getOrderedUris())
                    assertTrue(moveDown.isFocusable)
                }
                Thread.sleep(500)
                scenario.onActivity { activity ->
                    val adapter = activity.findViewById<RecyclerView>(R.id.recycler_queue).adapter as QueueFileAdapter
                    assertEquals(listOf(uris[1], uris[0]), adapter.getOrderedUris())
                }
            }
        } finally {
            testDirectory.deleteRecursively()
        }
    }

    @Test
    fun removingFinalQueueItemDisablesInstallAndFocusesSelect() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val testDirectory = File(context.filesDir, "tv_queue_remove_${System.nanoTime()}").apply { mkdirs() }
        val firstPackage = File(testDirectory, "first.apk").apply { writeBytes(byteArrayOf(1)) }
        val finalPackage = File(testDirectory, "final.apk").apply { writeBytes(byteArrayOf(2)) }
        val intent = Intent(context, MainActivity::class.java).apply {
            putParcelableArrayListExtra(
                MainActivity.EXTRA_PACKAGE_URIS,
                arrayListOf(uriFor(context, firstPackage), uriFor(context, finalPackage)),
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                val removeDescription = context.getString(R.string.queue_remove)
                val firstRemove = device.wait(Until.findObject(By.desc(removeDescription)), TIMEOUT_MS)
                requireNotNull(firstRemove).click()
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                val finalRemove = device.wait(Until.findObject(By.desc(removeDescription)), TIMEOUT_MS)
                requireNotNull(finalRemove).click()
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    assertTrue(!activity.findViewById<View>(R.id.btn_install).isEnabled)
                    assertTrue(activity.findViewById<View>(R.id.btn_select).hasFocus())
                    assertEquals(0, (activity.findViewById<RecyclerView>(R.id.recycler_queue).adapter as QueueFileAdapter).itemCount)
                }
            }
        } finally {
            testDirectory.deleteRecursively()
        }
    }

    private fun uriFor(context: android.content.Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.provider", file)

    private fun historyEntry(id: String, label: String, timestamp: Long) = InstallHistoryEntry(
        id = id,
        timestamp = timestamp,
        appLabel = label,
        packageName = "com.example.$id",
        versionName = "1.0",
        format = "APK",
        fileSize = 1_024L,
        status = HistoryStatus.SUCCESS,
        detail = "",
        installMode = "Normal",
        durationMs = 100L,
    )

    private fun deleteHistoryRow(activity: InstallHistoryActivity, position: Int) {
        val recycler = activity.findViewById<RecyclerView>(R.id.recycler_history)
        val row = requireNotNull(recycler.findViewHolderForAdapterPosition(position)).itemView
        row.findViewById<View>(R.id.btn_delete).performClick()
    }

    private fun focusedTitle(view: View?): String? {
        if (view is TextView && view.text.isNotBlank()) return view.text.toString()
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                focusedTitle(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun assertContainedBy(view: View, parent: View) {
        val viewLocation = IntArray(2)
        val parentLocation = IntArray(2)
        view.getLocationOnScreen(viewLocation)
        parent.getLocationOnScreen(parentLocation)
        assertTrue(viewLocation[0] >= parentLocation[0])
        assertTrue(viewLocation[1] >= parentLocation[1])
        assertTrue(viewLocation[0] + view.width <= parentLocation[0] + parent.width)
        assertTrue(viewLocation[1] + view.height <= parentLocation[1] + parent.height)
    }

    companion object {
        private const val TIMEOUT_MS = 5_000L
    }
}
