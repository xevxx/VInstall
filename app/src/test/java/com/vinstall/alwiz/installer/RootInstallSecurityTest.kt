package com.vinstall.alwiz.installer

import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RootInstallSecurityTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun rootWriteUsesStdinAndGeneratedNameOnly() {
        val malicious = "base.apk;touch /data/local/tmp/pwned"
        val command = SplitInstaller.rootWriteCommand(123, "42", "split_001.apk")

        assertEquals("pm install-write -S 123 42 split_001.apk -", command)
        assertFalse(command.contains(malicious))
        assertTrue(runCatching { SplitInstaller.rootWriteCommand(123, "42", malicious) }.isFailure)
        assertTrue(runCatching { SplitInstaller.rootWriteCommand(123, "42;id", "base.apk") }.isFailure)
    }

    @Test
    fun failedRootWriteAbandonsSessionWithoutUsingArchiveFilename() = runBlocking {
        val malicious = File(temporaryFolder.root, "base.apk;id").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val commands = mutableListOf<Pair<String, File?>>()
        SplitInstaller.rootProcessRunner = RootProcessRunner { command, input ->
            commands += command to input
            when {
                command.startsWith("pm install-create") -> RootProcessResult(0, "Success: created install session [77]")
                command.startsWith("pm install-write") -> RootProcessResult(1, "Failure [write failed]")
                command == "pm install-abandon 77" -> RootProcessResult(0, "Success")
                else -> RootProcessResult(1, "unexpected")
            }
        }
        try {
            assertTrue(SplitInstaller.installViaRoot(listOf(malicious)).isFailure)
            assertEquals("pm install-write -S 3 77 base.apk -", commands[1].first)
            assertEquals(malicious, commands[1].second)
            assertEquals("pm install-abandon 77", commands.last().first)
            assertTrue(commands.none { it.first.contains(malicious.name) })
        } finally {
            SplitInstaller.rootProcessRunner = RuntimeRootProcessRunner
        }
    }
}
