package com.vinstall.alwiz

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QueueAdapterStateTest {
    private fun item(id: String, label: String = id) = QueueItem(Uri.parse("content://queue/$id"), label, "APK")

    @Test
    fun metadataRefreshPreservesCurrentOrder() {
        val adapter = QueueFileAdapter(mutableListOf(item("a"), item("b")), {})
        adapter.onItemMoved(1, 0)
        adapter.setItems(listOf(item("a", "A refreshed"), item("b", "B refreshed")))

        assertEquals(listOf("B refreshed", "A refreshed"), adapter.getOrderedItems().map { it.displayName })
    }

    @Test
    fun removeLastNotifiesAndCannotBeReaddedByRefresh() {
        val removed = mutableListOf<Uri>()
        var latest: List<QueueItem> = listOf(item("placeholder"))
        val adapter = QueueFileAdapter(
            mutableListOf(item("a")),
            {},
            onQueueChanged = { latest = it },
            onItemRemoved = { removed += it },
        )

        adapter.removeAt(0)
        adapter.setItems(listOf(item("a", "refreshed")))

        assertTrue(latest.isEmpty())
        assertEquals(listOf(Uri.parse("content://queue/a")), removed)
        assertEquals(0, adapter.itemCount)
    }
}
