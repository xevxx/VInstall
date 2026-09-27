package com.vinstall.alwiz.installer

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vinstall.alwiz.model.PackageFormat
import com.vinstall.alwiz.util.FileUtil
import com.vinstall.alwiz.util.StorageBudget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArchiveHardeningTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun archiveRejectsEntryLimitDuplicateNamesAndInvalidExtension() {
        val guard = ArchiveExtractionGuard(temporaryFolder.root)
        repeat(ArchiveExtractionGuard.MAX_ENTRIES) { guard.recordEntry("entry_$it.txt", false) }
        assertTrue(runCatching { guard.recordEntry("overflow.txt", false) }.isFailure)

        val duplicateGuard = ArchiveExtractionGuard(temporaryFolder.root)
        duplicateGuard.recordEntry("one/base.apk", false)
        assertTrue(runCatching { duplicateGuard.recordEntry("two/BASE.apk", false) }.isFailure)

        val extensionGuard = ArchiveExtractionGuard(temporaryFolder.root)
        assertTrue(runCatching { extensionGuard.destination("payload.sh", ArchiveExtractionGuard.APK_EXTENSIONS) }.isFailure)
    }

    @Test
    fun metadataIsBoundedAndInterruptedExtractionLeavesNoOutput() {
        val oversized = ByteArray(ArchiveExtractionGuard.MAX_METADATA_BYTES + 1)
        assertTrue(runCatching { ArchiveExtractionGuard.readMetadata(oversized.inputStream()) }.isFailure)

        val guard = ArchiveExtractionGuard(temporaryFolder.root)
        val destination = temporaryFolder.root.resolve("base.apk")
        val interrupted = object : InputStream() {
            private var emitted = 0
            override fun read(): Int {
                if (emitted++ < 8) return 1
                throw IOException("interrupted")
            }
        }
        assertTrue(runCatching { guard.extract(interrupted, destination) }.isFailure)
        assertFalse(destination.exists())
        assertTrue(temporaryFolder.root.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test
    fun storageReserveRequiresFullTwoHundredFiftySixMiB() {
        val reserve = StorageBudget.RESERVED_FREE_BYTES
        assertTrue(StorageBudget.hasSpace(reserve + 1, 1))
        assertFalse(StorageBudget.hasSpace(reserve, 1))
    }

    @Test
    fun unreadableUriCannotReuseStaleCacheFile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val stale = context.cacheDir.resolve("stale.apk").apply { writeText("old package") }
        assertTrue(runCatching {
            FileUtil.extractToCache(context, Uri.parse("content://missing.provider/package.apk"), stale.name)
        }.isFailure)
        assertFalse(stale.exists())
    }

    @Test
    fun batchValidationRejectsLaterObbBeforeAnyInstallCanStart() {
        val preflights = listOf(
            PackagePreflight(PackageFormat.APK),
            PackagePreflight(
                PackageFormat.XAPK,
                requiresElevatedObb = true,
                elevatedModeAvailable = false,
                validationFailure = "Root or Shizuku required",
            ),
        )
        assertTrue(validatePackageBatch(preflights).isFailure)
    }
}
