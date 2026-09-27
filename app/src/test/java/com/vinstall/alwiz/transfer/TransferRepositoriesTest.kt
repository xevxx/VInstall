package com.vinstall.alwiz.transfer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransferRepositoriesTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cleanPrivateTransferFiles()
    }

    @After
    fun tearDown() = cleanPrivateTransferFiles()

    @Test
    fun sanitizeFileName_acceptsSupportedFormatsAndNormalizesUnsafeCharacters() {
        assertEquals("My package _ (arm64).apkm", IncomingPackageRepository.sanitizeFileName("My package ⚡ (arm64).APKM"))
        assertEquals("archive.zip", IncomingPackageRepository.sanitizeFileName("archive.zip"))
    }

    @Test
    fun sanitizeFileName_rejectsTraversalAndUnsupportedFormats() {
        listOf("../payload.apk", "folder\\payload.apk", "payload.exe", "..", "").forEach { name ->
            assertTrue(runCatching { IncomingPackageRepository.sanitizeFileName(name) }.isFailure)
        }
    }

    @Test
    fun incomingUpload_commitsAtomicallyListsAndDeletesByUri() {
        val repository = IncomingPackageRepository(context)
        val pending = repository.beginUpload("sample.apk", expectedSize = 4)
        pending.write(byteArrayOf(1, 2, 3, 4))
        val entry = pending.commit()

        assertFalse(File(context.filesDir, "incoming/${entry.id}.apk.part").exists())
        assertEquals(entry.id, repository.find(entry.id)?.id)
        assertEquals(listOf(entry.id), repository.list().map { it.id })
        assertTrue(repository.deleteByUri(repository.uriFor(entry)))
        assertTrue(repository.list().isEmpty())
    }

    @Test
    fun incomingUpload_abortRemovesPartialFile() {
        val repository = IncomingPackageRepository(context)
        repository.beginUpload("sample.xapk").use { pending ->
            pending.write(byteArrayOf(1, 2, 3))
        }
        val files = File(context.filesDir, "incoming").listFiles().orEmpty()
        assertTrue(files.none { it.name.endsWith(".part") })
        assertTrue(repository.list().isEmpty())
    }

    @Test
    fun incomingCleanup_removesEntriesAfterTwentyFourHours() {
        val repository = IncomingPackageRepository(context)
        val pending = repository.beginUpload("sample.apks")
        pending.write(byteArrayOf(1))
        val entry = pending.commit()

        assertEquals(1, repository.cleanupExpired(entry.createdAt + TimeUnit.HOURS.toMillis(25)))
        assertTrue(repository.list().isEmpty())
    }

    @Test
    fun exportRepository_registersFindsDeletesAndExpires() {
        val source = File(context.cacheDir, "source.apkv").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        val repository = ExportRepository(context)
        val entry = repository.register(source)

        assertEquals("source.apkv", entry.displayName)
        assertEquals(3L, entry.size)
        assertNotNull(repository.find(entry.id))
        assertEquals(1, repository.cleanupExpired(entry.createdAt + TimeUnit.DAYS.toMillis(8)))
        assertTrue(repository.list().isEmpty())
    }

    @Test
    fun exportRegistrationRenamesSameDirectoryInputAtomically() {
        val exportDirectory = File(context.filesDir, "exports").apply { mkdirs() }
        val source = File(exportDirectory, "friendly-name.apkv").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val entry = ExportRepository(context).register(source)

        assertFalse(source.exists())
        assertTrue(entry.file.isFile)
        assertEquals("friendly-name.apkv", entry.displayName)
        assertEquals(byteArrayOf(1, 2, 3, 4).toList(), entry.file.readBytes().toList())
    }

    @Test
    fun concurrentCleanupCannotDeleteExportBeingRegistered() {
        val exportDirectory = File(context.filesDir, "exports").apply { mkdirs() }
        val source = File(exportDirectory, "concurrent.apkv").apply { writeBytes(ByteArray(4096) { 7 }) }
        val repositoryA = ExportRepository(context)
        val repositoryB = ExportRepository(context)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val registered = executor.submit<ExportEntry> { start.await(); repositoryA.register(source) }
            val cleaned = executor.submit<Int> { start.await(); repositoryB.cleanupExpired() }
            start.countDown()
            val entry = registered.get(5, TimeUnit.SECONDS)
            cleaned.get(5, TimeUnit.SECONDS)
            assertNotNull(repositoryA.find(entry.id))
            assertTrue(entry.file.isFile)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun exportCleanupUsesGraceForOrphansAndRemovesOldPartialArtifacts() {
        val exportDirectory = File(context.filesDir, "exports").apply { mkdirs() }
        val recentOrphan = File(exportDirectory, "recent.apkv").apply { writeBytes(byteArrayOf(1)) }
        val oldOrphan = File(exportDirectory, "old.apkv").apply {
            writeBytes(byteArrayOf(2))
            setLastModified(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(2))
        }
        val stalePartial = File(exportDirectory, ".export.partial").apply {
            writeBytes(byteArrayOf(3))
            setLastModified(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(2))
        }

        ExportRepository(context).list()

        assertTrue(recentOrphan.exists())
        assertFalse(oldOrphan.exists())
        assertFalse(stalePartial.exists())
    }

    private fun cleanPrivateTransferFiles() {
        File(context.filesDir, "incoming").deleteRecursively()
        File(context.filesDir, "exports").deleteRecursively()
        File(context.cacheDir, "source.apkv").delete()
    }
}
