package com.vinstall.alwiz

import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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

@RunWith(AndroidJUnit4::class)
class TvRemoteNavigationTest {
    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun dashboardDpadOpensInstallAndReceiveAndBackReturnsHome() {
        ActivityScenario.launch(TvHomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(activity.getString(R.string.tv_install_title), focusedTitle(activity.currentFocus))
            }

            device.pressDPadCenter()
            assertTrue(device.wait(Until.hasObject(By.text("Select File")), TIMEOUT_MS))
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
            }
        } finally {
            testDirectory.deleteRecursively()
        }
    }

    private fun uriFor(context: android.content.Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.provider", file)

    private fun focusedTitle(view: View?): String? {
        if (view is TextView && view.text.isNotBlank()) return view.text.toString()
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                focusedTitle(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    companion object {
        private const val TIMEOUT_MS = 5_000L
    }
}
