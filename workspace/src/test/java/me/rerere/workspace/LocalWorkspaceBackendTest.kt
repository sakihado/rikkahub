package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class LocalWorkspaceBackendTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val root = "test-workspace"

    private fun createManager(shellRunner: WorkspaceShellRunner = HostShellRunner()): Pair<WorkspaceManager, File> {
        val skillsDir = tempFolder.newFolder("skills")
        val uploadDir = tempFolder.newFolder("upload")
        val manager = WorkspaceManager(
            baseDir = tempFolder.newFolder("workspaces"),
            shellRunner = shellRunner,
            bindMounts = listOf(
                WorkspaceBindMount(source = skillsDir, target = "/skills", writable = true),
                WorkspaceBindMount(source = uploadDir, target = "/upload"),
            ),
        )
        return manager to skillsDir
    }

    @Test
    fun layoutWritableRootsIncludeOnlyWritableBindMounts() {
        val (manager, _) = createManager()
        val layout = manager.layout

        assertEquals("/workspace", layout.workspaceDir)
        assertEquals(listOf("/workspace", "/tmp", "/skills"), layout.writableRoots)
        assertTrue(layout.isWritable("/workspace"))
        assertTrue(layout.isWritable("/skills/demo/SKILL.md"))
        assertFalse(layout.isWritable("/upload/a.txt"))
        assertFalse(layout.isWritable("/workspace-other/a.txt"))
        assertFalse(layout.isWritable("/"))
    }

    @Test
    fun layoutConvertsBetweenAreaAndAbsolutePaths() {
        val layout = WorkspaceLayout(workspaceDir = "/workspace", writableRoots = emptyList())

        assertEquals("/workspace", layout.absolutePath(WorkspaceStorageArea.FILES, ""))
        assertEquals("/workspace/src/a.kt", layout.absolutePath(WorkspaceStorageArea.FILES, "src/a.kt"))
        assertEquals("/", layout.absolutePath(WorkspaceStorageArea.LINUX, ""))
        assertEquals("/etc/hostname", layout.absolutePath(WorkspaceStorageArea.LINUX, "etc/hostname"))
        assertEquals("", layout.relativeToWorkspace("/workspace"))
        assertEquals("src", layout.relativeToWorkspace("/workspace/src"))
        assertEquals("src", layout.relativeToWorkspace("src"))
    }

    @Test
    fun fileWrittenByAreaPathIsReadableByAbsolutePath() {
        val (manager, skillsDir) = createManager()
        val backend = manager.backend(root)

        backend.writeText("notes/a.txt", "hello", overwrite = true)
        File(skillsDir, "demo.md").writeText("skill")

        assertEquals(5L, backend.absoluteFileSize("/workspace/notes/a.txt"))
        val workspaceFile = ByteArrayOutputStream()
        backend.exportAbsoluteFile("/workspace/notes/a.txt", workspaceFile)
        assertEquals("hello", workspaceFile.toString(Charsets.UTF_8.name()))

        val skillFile = ByteArrayOutputStream()
        backend.exportAbsoluteFile("/skills/demo.md", skillFile)
        assertEquals("skill", skillFile.toString(Charsets.UTF_8.name()))

        assertEquals(
            File(manager.filesDir(root), "notes/a.txt").canonicalFile,
            backend.localFile(WorkspaceStorageArea.FILES, "notes/a.txt"),
        )
    }

    @Test
    fun terminalLaunchSharesMountTableWithCommandExecution() {
        val nativeLibraryDir = tempFolder.newFolder("native")
        val (manager, skillsDir) = createManager(ProotShellRunner(nativeLibraryDir))
        manager.ensureWorkspace(root)

        val launch = manager.terminalLaunch(root, shellCompatibilityMode = true)!!

        assertEquals(File(nativeLibraryDir, "libproot_exec.so").absolutePath, launch.executable)
        assertEquals(manager.filesDir(root).absolutePath, launch.cwd)
        assertEquals(
            listOf("--root-id", "--link2symlink", "--kill-on-exit", "-r", manager.linuxDir(root).absolutePath),
            launch.args.take(5),
        )
        assertTrue(launch.args.containsAll(listOf("-w", "/workspace")))
        assertTrue("${manager.filesDir(root).absolutePath}:/workspace" in launch.args)
        assertTrue("${skillsDir.absolutePath}:/skills" in launch.args)
        assertTrue(launch.args.any { it.endsWith(":/upload") })
        // 空 Rootfs 里没有 bash, 回退到 /bin/sh
        assertEquals(listOf("USER=root", "SHELL=/bin/sh", "/bin/sh"), launch.args.takeLast(3))
        assertTrue("PROOT_NO_SECCOMP=1" in launch.env)
        assertTrue("TMPDIR=${manager.tempDir(root).absolutePath}" in launch.env)
    }

    @Test
    fun hostShellRunnerHasNoInteractiveTerminal() {
        val (manager, _) = createManager()
        assertEquals(null, manager.terminalLaunch(root, shellCompatibilityMode = false))
    }

    @Test
    fun disablingHostBridgeFiltersOutHostBridgeAndSdcardMounts() {
        val nativeLibraryDir = tempFolder.newFolder("native")
        val skillsDir = tempFolder.newFolder("skills")
        val bridgeDir = tempFolder.newFolder("host_bridge")
        val sdcardDir = tempFolder.newFolder("sdcard")
        val rikkaDownloadDir = File(sdcardDir, "Download/RikkaHub").apply { mkdirs() }
        File(sdcardDir, "secret.md").writeText("sdcard-content")

        val manager = WorkspaceManager(
            baseDir = tempFolder.newFolder("workspaces"),
            shellRunner = ProotShellRunner(nativeLibraryDir),
            bindMounts = listOf(
                WorkspaceBindMount(source = skillsDir, target = "/skills", writable = true),
                WorkspaceBindMount(source = bridgeDir, target = "/run/host_bridge", requiresHostBridge = true),
                WorkspaceBindMount(source = sdcardDir, target = "/sdcard", requiresHostBridge = true),
                WorkspaceBindMount(source = sdcardDir, target = "/storage/emulated/0", requiresHostBridge = true),
                WorkspaceBindMount(
                    source = rikkaDownloadDir,
                    target = "/sdcard/Download/RikkaHub",
                    writable = true,
                    requiresHostBridge = true,
                ),
                WorkspaceBindMount(
                    source = rikkaDownloadDir,
                    target = "/storage/emulated/0/Download/RikkaHub",
                    writable = true,
                    requiresHostBridge = true,
                ),
            ),
        ).also { it.ensureWorkspace(root) }

        // 开启桥接时：支持 /sdcard 和 /storage/emulated/0 双路径别名解析，且包含可写免审目录与挂载参数
        val enabledBackend = manager.backend(root, hostBridgeEnabled = true)
        assertTrue(enabledBackend.layout.isWritable("/sdcard/Download/RikkaHub/out.apk"))
        assertTrue(enabledBackend.layout.isWritable("/storage/emulated/0/Download/RikkaHub/out.apk"))
        assertEquals(14L, enabledBackend.absoluteFileSize("/sdcard/secret.md"))
        assertEquals(14L, enabledBackend.absoluteFileSize("/storage/emulated/0/secret.md"))
        val enabledLaunch = manager.terminalLaunch(root, shellCompatibilityMode = false, hostBridgeEnabled = true)!!
        assertTrue(enabledLaunch.args.any { it.endsWith(":/sdcard") })
        assertTrue(enabledLaunch.args.any { it.endsWith(":/storage/emulated/0") })
        assertTrue(enabledLaunch.args.any { it.endsWith(":/run/host_bridge") })

        // 关闭桥接时：退回纯隔离沙盒，不再挂载 /sdcard、/storage/emulated/0 与 /run/host_bridge
        val disabledBackend = manager.backend(root, hostBridgeEnabled = false)
        assertFalse(disabledBackend.layout.isWritable("/sdcard/Download/RikkaHub/out.apk"))
        assertFalse(disabledBackend.layout.isWritable("/storage/emulated/0/Download/RikkaHub/out.apk"))
        assertEquals(listOf("/workspace", "/tmp", "/skills"), disabledBackend.layout.writableRoots)
        // 未挂载 /sdcard 时回退到 Rootfs 内部目录（文件不存在）
        assertEquals(
            File(manager.linuxDir(root), "sdcard/secret.md").canonicalFile,
            disabledBackend.localAbsoluteFile("/sdcard/secret.md"),
        )
        val disabledLaunch = manager.terminalLaunch(root, shellCompatibilityMode = false, hostBridgeEnabled = false)!!
        assertFalse(disabledLaunch.args.any { it.endsWith(":/sdcard") })
        assertFalse(disabledLaunch.args.any { it.endsWith(":/storage/emulated/0") })
        assertFalse(disabledLaunch.args.any { it.endsWith(":/run/host_bridge") })
        assertTrue(disabledLaunch.args.any { it.endsWith(":/skills") })
    }
}
