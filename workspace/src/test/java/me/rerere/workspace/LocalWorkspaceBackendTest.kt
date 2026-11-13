package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
                WorkspaceBindMount(source = skillsDir, target = "/skills", access = MountAccess.WRITABLE_AUTO),
                WorkspaceBindMount(source = uploadDir, target = "/upload", access = MountAccess.READ_ONLY),
            ),
        )
        return manager to skillsDir
    }

    @Test
    fun layoutEnforcesWritableAndReadOnlyRootsWithNormalization() {
        val (manager, _) = createManager()
        val layout = manager.layout

        assertEquals("/workspace", layout.workspaceDir)
        assertEquals(listOf("/workspace", "/tmp", "/skills"), layout.writableRoots)
        assertEquals(listOf("/upload"), layout.readOnlyRoots)

        assertTrue(layout.isWritable("/workspace"))
        assertTrue(layout.isWritable("/skills/demo/SKILL.md"))
        assertFalse(layout.isWritable("/upload/a.txt"))
        assertTrue(layout.isReadOnly("/upload/a.txt"))
        // 词法穿越（`..`）必须先归一化再判定，绝不能借可写前缀绕过只读检查
        assertFalse(layout.isWritable("/skills/../upload/a.txt"))
        assertTrue(layout.isReadOnly("/skills/../upload/a.txt"))
        assertFalse(layout.isWritable("/workspace-other/a.txt"))
        assertFalse(layout.isWritable("/"))
    }

    @Test
    fun writeAbsoluteTextRejectsReadOnlyMountsImmediately() {
        val (manager, _) = createManager()
        val backend = manager.backend(root)

        val err = assertThrows(IllegalArgumentException::class.java) {
            backend.writeAbsoluteText("/upload/evil.txt", "blocked", overwrite = true)
        }
        assertTrue(err.message!!.contains("read-only"))

        val traversalErr = assertThrows(IllegalArgumentException::class.java) {
            backend.writeAbsoluteText("/skills/../upload/evil.txt", "blocked", overwrite = true)
        }
        assertTrue(traversalErr.message!!.contains("read-only"))
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
    fun sessionHostAccessMountsSdcardStrictlyReadOnlyAndDefaultsToIsolated() {
        val nativeLibraryDir = tempFolder.newFolder("native")
        val skillsDir = tempFolder.newFolder("skills")
        val bridgeDir = tempFolder.newFolder("host_bridge")
        val sdcardDir = tempFolder.newFolder("sdcard")
        File(sdcardDir, "secret.md").writeText("sdcard-content")

        val manager = WorkspaceManager(
            baseDir = tempFolder.newFolder("workspaces"),
            shellRunner = ProotShellRunner(nativeLibraryDir),
            bindMounts = listOf(
                WorkspaceBindMount(source = skillsDir, target = "/skills", access = MountAccess.WRITABLE_AUTO),
                WorkspaceBindMount(
                    source = bridgeDir,
                    target = "/run/host_bridge",
                    access = MountAccess.READ_ONLY,
                    sessionHostMount = true,
                ),
                WorkspaceBindMount(
                    source = sdcardDir,
                    target = "/sdcard",
                    access = MountAccess.READ_ONLY,
                    sessionHostMount = true,
                ),
                WorkspaceBindMount(
                    source = sdcardDir,
                    target = "/storage/emulated/0",
                    access = MountAccess.READ_ONLY,
                    sessionHostMount = true,
                ),
            ),
        ).also { it.ensureWorkspace(root) }

        // 默认状态（hostAccess = false）：完全隔离，不挂载 /sdcard、/storage/emulated/0 或 /run/host_bridge
        val isolatedBackend = manager.backend(root)
        assertFalse(isolatedBackend.layout.isReadOnly("/sdcard/secret.md"))
        assertEquals(listOf("/workspace", "/tmp", "/skills"), isolatedBackend.layout.writableRoots)
        assertEquals(
            File(manager.linuxDir(root), "sdcard/secret.md").canonicalFile,
            isolatedBackend.localAbsoluteFile("/sdcard/secret.md"),
        )
        val isolatedLaunch = manager.terminalLaunch(root, shellCompatibilityMode = false)!!
        assertFalse(isolatedLaunch.args.any { it.endsWith(":/sdcard") })
        assertFalse(isolatedLaunch.args.any { it.endsWith(":/storage/emulated/0") })
        assertFalse(isolatedLaunch.args.any { it.endsWith(":/run/host_bridge") })
        assertTrue(isolatedLaunch.args.any { it.endsWith(":/skills") })

        // 会话显式开启本机只读挂载（hostAccess = true）：可读 /sdcard 与 /storage/emulated/0，但全盘严格只读
        val roBackend = manager.backend(root, hostAccess = true)
        assertEquals(14L, roBackend.absoluteFileSize("/sdcard/secret.md"))
        assertEquals(14L, roBackend.absoluteFileSize("/storage/emulated/0/secret.md"))
        assertTrue(roBackend.layout.isReadOnly("/sdcard/secret.md"))
        assertTrue(roBackend.layout.isReadOnly("/sdcard/Download/RikkaHub/out.apk"))
        assertTrue(roBackend.layout.isReadOnly("/storage/emulated/0/Download/RikkaHub/out.apk"))
        assertFalse(roBackend.layout.isWritable("/sdcard/Download/RikkaHub/out.apk"))
        assertThrows(IllegalArgumentException::class.java) {
            roBackend.writeAbsoluteText("/sdcard/Download/RikkaHub/out.txt", "nope", overwrite = true)
        }
        val roLaunch = manager.terminalLaunch(root, shellCompatibilityMode = false, hostAccess = true)!!
        assertTrue(roLaunch.args.any { it.endsWith(":/sdcard") })
        assertTrue(roLaunch.args.any { it.endsWith(":/storage/emulated/0") })
        assertTrue(roLaunch.args.any { it.endsWith(":/run/host_bridge") })
    }
}
