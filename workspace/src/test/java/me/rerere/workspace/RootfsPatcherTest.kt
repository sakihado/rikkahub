package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RootfsPatcherTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `patch cleans up legacy trash wrappers and installs read-only host-sh probe`() {
        val rootfs = tempFolder.newFolder("rootfs")
        File(rootfs, "bin").mkdirs()
        File(rootfs, "bin/sh").writeText("#!/bin/sh\n")
        File(rootfs, "etc").mkdirs()

        // 模拟旧版本遗留的回收站包装脚本与旧补丁版本号
        val legacyBin = File(rootfs, "usr/local/bin").apply { mkdirs() }
        val legacyLibexec = File(rootfs, "usr/local/libexec").apply { mkdirs() }
        File(legacyBin, "rm").writeText("#!/bin/sh\n# move to .Trash-RikkaHub\n")
        File(legacyBin, "rmdir").writeText("#!/bin/sh\n# move to .Trash-RikkaHub\n")
        File(legacyBin, "unlink").writeText("#!/bin/sh\n# move to .Trash-RikkaHub\n")
        File(legacyLibexec, "rikkahub-sdcard-trash").writeText("#!/bin/sh\nTRASH=/sdcard/.Trash-RikkaHub\n")
        // 非 RikkaHub 创建的用户自定义工具不得被误删
        val customTool = File(legacyBin, "my-tool").apply { writeText("#!/bin/sh\necho hi\n") }
        File(rootfs, "etc/.rikkahub-patched").writeText("6")

        RootfsPatcher().patch(rootfs, listOf("1.1.1.1"))

        assertFalse(File(legacyBin, "rm").exists())
        assertFalse(File(legacyBin, "rmdir").exists())
        assertFalse(File(legacyBin, "unlink").exists())
        assertFalse(File(legacyLibexec, "rikkahub-sdcard-trash").exists())
        assertTrue(customTool.exists())

        val hostSh = File(legacyBin, "host-sh")
        assertTrue(hostSh.isFile && hostSh.canExecute())
        val script = hostSh.readText()
        // 不再包含 -r/--root 特权参数
        assertFalse(script.contains("X-As-Root"))
        assertEquals("nameserver 1.1.1.1\n", File(rootfs, "etc/resolv.conf").readText())
    }

    @Test
    fun `host-sh fails closed with exit 126 when endpoint mount is absent`() {
        val rootfs = tempFolder.newFolder("rootfs")
        File(rootfs, "bin").mkdirs()
        File(rootfs, "bin/sh").writeText("#!/bin/sh\n")
        RootfsPatcher().patch(rootfs)

        val hostSh = File(rootfs, "usr/local/bin/host-sh")
        val isolatedScript = hostSh.readText().replace(
            "/run/host_bridge/endpoint",
            File(tempFolder.root, "nonexistent_endpoint").absolutePath,
        )
        val runner = tempFolder.newFile("host-sh-test").apply {
            writeText(isolatedScript)
            setExecutable(true, false)
        }

        val proc = ProcessBuilder("/bin/sh", runner.absolutePath, "getprop ro.build.version.release")
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        val exit = proc.waitFor()

        assertEquals(126, exit)
        assertTrue(output.contains("Host access is disabled for this conversation"))
    }
}
