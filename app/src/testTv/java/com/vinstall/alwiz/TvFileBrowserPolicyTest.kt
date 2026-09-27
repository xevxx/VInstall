package com.vinstall.alwiz

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

class TvFileBrowserPolicyTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun acceptsOnlySupportedPackageExtensions() {
        listOf("app.apk", "bundle.APKM", "set.apks", "backup.apkv", "game.xapk", "files.zip")
            .forEach { assertTrue(it, TvFileBrowserPolicy.isSupportedPackage(it)) }

        listOf("app.apk.txt", "apk", ".hidden", "photo.jpg", "bundle.apkm/")
            .forEach { assertFalse(it, TvFileBrowserPolicy.isSupportedPackage(it)) }
    }

    @Test
    fun pathBoundaryDoesNotTreatSiblingPrefixAsChild() {
        val root = File("/storage/packages")
        assertTrue(TvFileBrowserPolicy.isWithin(File("/storage/packages/app.apk"), root))
        assertTrue(TvFileBrowserPolicy.isWithin(root, root))
        assertFalse(TvFileBrowserPolicy.isWithin(File("/storage/packages-evil/app.apk"), root))
    }

    @Test
    fun hidesSystemDirectoriesUntilExplicitlyShown() {
        listOf("Android", "android", "LOST.DIR", ".cache").forEach {
            assertFalse(it, TvFileBrowserPolicy.shouldShowDirectory(it, false))
            assertTrue(it, TvFileBrowserPolicy.shouldShowDirectory(it, true))
        }
        assertTrue(TvFileBrowserPolicy.shouldShowDirectory("Download", false))
    }

    @Test
    fun rememberedDirectoryWinsThenFallsBackToDownloads() {
        val home = temporaryFolder.newFolder("home")
        val downloads = File(home, "Download").apply { mkdirs() }
        val remembered = File(home, "Documents").apply { mkdirs() }
        val incoming = temporaryFolder.newFolder("incoming")

        val chosen = TvFileBrowserPolicy.chooseInitialDirectory(
            remembered, downloads, home, incoming, listOf(home, incoming)
        )
        assertTrue(chosen?.canonicalFile == remembered.canonicalFile)

        remembered.delete()
        val fallback = TvFileBrowserPolicy.chooseInitialDirectory(
            remembered, downloads, home, incoming, listOf(home, incoming)
        )
        assertTrue(fallback?.canonicalFile == downloads.canonicalFile)
    }

    @Test
    fun unmountedRememberedVolumeFallsBackToUserStorage() {
        val home = temporaryFolder.newFolder("user")
        val downloads = File(home, "Download").apply { mkdirs() }
        val incoming = temporaryFolder.newFolder("received")
        val missingUsb = File(temporaryFolder.root, "missing-usb/packages")

        val chosen = TvFileBrowserPolicy.chooseInitialDirectory(
            missingUsb, downloads, home, incoming, listOf(home, incoming)
        )
        assertTrue(chosen?.canonicalFile == downloads.canonicalFile)
    }
}
