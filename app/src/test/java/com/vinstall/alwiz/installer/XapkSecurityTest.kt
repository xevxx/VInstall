package com.vinstall.alwiz.installer

import com.vinstall.alwiz.settings.InstallMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule

class XapkSecurityTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun obbDestinationAlwaysUsesValidatedPackageDirectory() {
        val root = temporaryFolder.root
        val destination = XapkInstaller.validatedObbDestination(
            root,
            "com.example.game",
            "../../untrusted/main.1.com.example.game.obb",
        )

        assertEquals(
            root.resolve("Android/obb/com.example.game/main.1.com.example.game.obb").canonicalFile,
            destination,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonObbExpansionIsRejected() {
        XapkInstaller.validatedObbDestination(temporaryFolder.root, "com.example.game", "payload.apk")
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidManifestPackageIsRejected() {
        XapkInstaller.validatedObbDestination(temporaryFolder.root, "../escape", "main.obb")
    }

    @Test
    fun elevatedModeNeverFallsBackForObbPackages() {
        assertTrue(
            XapkInstaller.selectElevatedMode(
                InstallMode.NORMAL, rootAvailable = true, shizukuAvailable = true,
                shizukuGranted = true, shizukuProcessAvailable = true,
            ).isFailure,
        )
        assertTrue(
            XapkInstaller.selectElevatedMode(
                InstallMode.ROOT, rootAvailable = false, shizukuAvailable = false,
                shizukuGranted = false, shizukuProcessAvailable = false,
            ).isFailure,
        )
        assertEquals(
            XapkInstaller.ElevatedMode.SHIZUKU,
            XapkInstaller.selectElevatedMode(
                InstallMode.SHIZUKU, rootAvailable = false, shizukuAvailable = true,
                shizukuGranted = true, shizukuProcessAvailable = true,
            ).getOrThrow(),
        )
    }
}
