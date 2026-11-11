package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 针对 RootfsPatcher 生成的 `/sdcard` 防误删包装器 (`rm` / `rmdir` / `unlink` + `rikkahub-sdcard-trash`)
 * 做真实 Shell 隔离执行测试，回归验证：
 * 1. 同一次 `rm` 删除多个不同目录下的同名文件时绝不相互覆盖；
 * 2. 混合传入 `/sdcard/...` 与非 `/sdcard` 目录且未指定 `-r` 时，绝不擅自递归删除非空目录；
 * 3. 指定 `-rf` 时，非 `/sdcard` 目录按原生语义递归删除，而 `/sdcard` 目录整块移入回收站并保留目录层级。
 */
class SafeSdcardTrashWrapperTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private data class IsolatedEnv(
        val binDir: File,
        val fakeSdcard: File,
        val trashDir: File,
        val workspaceDir: File,
    ) {
        fun runWrapper(tool: String, vararg args: String): Pair<Int, String> {
            val cmd = listOf("/bin/sh", File(binDir, tool).absolutePath) + args
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            val exit = proc.waitFor()
            return exit to out
        }
    }

    private fun prepareIsolatedEnv(): IsolatedEnv {
        val rootfs = tempFolder.newFolder("rootfs")
        File(rootfs, "etc").mkdirs()
        RootfsPatcher().patch(rootfs)

        val fakeSdcard = tempFolder.newFolder("fake_sdcard").canonicalFile
        val trashDir = File(fakeSdcard, ".Trash-RikkaHub")
        val workspaceDir = tempFolder.newFolder("workspace").canonicalFile

        val binDir = File(rootfs, "usr/local/bin")
        val libexecDir = File(rootfs, "usr/local/libexec")
        val helperFile = File(libexecDir, "rikkahub-sdcard-trash")

        // 将脚本内的 `/sdcard` 路径重映射到隔离的临时目录以便在任意非特权测试机上直接执行验证
        listOf(
            helperFile,
            File(binDir, "rm"),
            File(binDir, "rmdir"),
            File(binDir, "unlink"),
        ).forEach { script ->
            val rewritten = script.readText()
                .replace("/usr/local/libexec/rikkahub-sdcard-trash", helperFile.absolutePath)
                .replace("/sdcard", fakeSdcard.absolutePath)
            script.writeText(rewritten)
            script.setExecutable(true, false)
        }

        return IsolatedEnv(
            binDir = binDir,
            fakeSdcard = fakeSdcard,
            trashDir = trashDir,
            workspaceDir = workspaceDir,
        )
    }

    @Test
    fun `removing same-named files from different sdcard folders in one command preserves both`() {
        val env = prepareIsolatedEnv()
        val photo1 = File(env.fakeSdcard, "album1/a.jpg").apply {
            parentFile.mkdirs()
            writeText("PHOTO_ONE_PAYLOAD")
        }
        val photo2 = File(env.fakeSdcard, "album2/a.jpg").apply {
            parentFile.mkdirs()
            writeText("PHOTO_TWO_PAYLOAD")
        }

        val (exit, output) = env.runWrapper("rm", photo1.absolutePath, photo2.absolutePath)
        assertEquals("Expected exit 0, output: $output", 0, exit)
        assertFalse(photo1.exists())
        assertFalse(photo2.exists())

        val trashedFiles = env.trashDir.listFiles().orEmpty().filter { it.isFile }
        assertEquals("Both files must exist separately in trash without collision", 2, trashedFiles.size)
        val contents = trashedFiles.map { it.readText() }.toSet()
        assertEquals(setOf("PHOTO_ONE_PAYLOAD", "PHOTO_TWO_PAYLOAD"), contents)
    }

    @Test
    fun `rm without -r never recursively deletes non-sdcard directory even when mixed with sdcard file`() {
        val env = prepareIsolatedEnv()
        val sdFile = File(env.fakeSdcard, "note.txt").apply {
            writeText("sdcard-note")
        }
        val importantDir = File(env.workspaceDir, "important-directory").apply { mkdirs() }
        val innerSecret = File(importantDir, "precious.kt").apply {
            writeText("val keepMe = true")
        }

        val (exit, _) = env.runWrapper("rm", sdFile.absolutePath, importantDir.absolutePath)
        assertNotEquals("rm without -r on a directory must return non-zero", 0, exit)

        // /sdcard 文件安全进入回收站
        assertFalse(sdFile.exists())
        assertEquals(1, env.trashDir.listFiles().orEmpty().size)

        // 非 /sdcard 非空目录及内部文件完好无损！
        assertTrue("Non-sdcard directory must NOT be deleted without -r", importantDir.isDirectory)
        assertTrue("Inner file must remain intact", innerSecret.isFile)
        assertEquals("val keepMe = true", innerSecret.readText())
    }

    @Test
    fun `rm -rf moves whole sdcard folder intact to trash and deletes non-sdcard folder`() {
        val env = prepareIsolatedEnv()
        val albumDir = File(env.fakeSdcard, "CameraRoll").apply { mkdirs() }
        File(albumDir, "sub/img1.png").apply {
            parentFile.mkdirs()
            writeText("IMG_1")
        }
        val tempBuildDir = File(env.workspaceDir, "build-cache").apply { mkdirs() }
        File(tempBuildDir, "obj.o").writeText("OBJ")

        val (exit, out) = env.runWrapper("rm", "-rf", albumDir.absolutePath, tempBuildDir.absolutePath)
        assertEquals("Expected exit 0, output: $out", 0, exit)
        assertFalse(albumDir.exists())
        assertFalse(tempBuildDir.exists())

        val trashedEntries = env.trashDir.listFiles().orEmpty()
        assertEquals(1, trashedEntries.size)
        val movedAlbum = trashedEntries.single()
        assertTrue(movedAlbum.isDirectory)
        assertEquals("IMG_1", File(movedAlbum, "sub/img1.png").readText())
    }

    @Test
    fun `trailing options after targets like rm sdcard_file ws_dir -rf work identically to leading options`() {
        val env = prepareIsolatedEnv()
        val sdFile = File(env.fakeSdcard, "trailing.txt").apply { writeText("TRAILING") }
        val buildCache = File(env.workspaceDir, "build-cache").apply { mkdirs() }
        File(buildCache, "artifact.bin").writeText("BIN")

        val (exit, out) = env.runWrapper("rm", sdFile.absolutePath, buildCache.absolutePath, "-rf")
        assertEquals("Trailing -rf should succeed without treating -rf as filename, out=$out", 0, exit)
        assertFalse(sdFile.exists())
        assertFalse("build-cache should be recursively deleted by trailing -rf", buildCache.exists())
        assertEquals("TRAILING", env.trashDir.listFiles()!!.single().readText())
    }

    @Test
    fun `refuses to delete trash directory itself or files inside trash`() {
        val env = prepareIsolatedEnv()
        val f = File(env.fakeSdcard, "doc.pdf").apply { writeText("PDF") }
        assertEquals(0, env.runWrapper("unlink", f.absolutePath).first)

        val trashed = env.trashDir.listFiles()!!.single()
        val (delItemExit, _) = env.runWrapper("rm", "-rf", trashed.absolutePath)
        assertNotEquals(0, delItemExit)
        assertTrue("Trashed file must still exist", trashed.exists())

        val (delTrashRootExit, _) = env.runWrapper("rm", "-rf", env.trashDir.absolutePath)
        assertNotEquals(0, delTrashRootExit)
        assertTrue(env.trashDir.isDirectory)
    }
}
