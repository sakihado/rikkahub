package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceLayoutTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun deeperApprovalMountOverridesWritableParent() {
        val manager = WorkspaceManager(baseDir = tempFolder.newFolder("workspaces"))
        val layout = manager.layout(
            listOf(
                WorkspaceBindMount(File("/storage/emulated/0"), "/storage/emulated/0", access = MountAccess.WRITABLE_AUTO),
                WorkspaceBindMount(
                    File("/storage/emulated/0/Documents"),
                    "/storage/emulated/0/Documents",
                    access = MountAccess.WRITABLE_ASK,
                ),
                WorkspaceBindMount(
                    File("/storage/emulated/0/Documents/w"),
                    "/storage/emulated/0/Documents/w",
                    access = MountAccess.WRITABLE_AUTO,
                ),
            )
        )

        assertTrue(layout.isWritable("/storage/emulated/0/Download/a.txt"))
        assertFalse(layout.isWritable("/storage/emulated/0/Documents"))
        assertFalse(layout.isWritable("/storage/emulated/0/Documents/a.txt"))
        assertTrue(layout.isWritable("/storage/emulated/0/Documents/w/a.txt"))
        // 前缀相同但不是子目录
        assertTrue(layout.isWritable("/storage/emulated/0/Documents2/a.txt"))
    }

    @Test
    fun readOnlyHostAccessOverridesWritableSharedStorageSubmounts() {
        val sdcard = tempFolder.newFolder("sdcard")
        val notes = File(sdcard, "Notes").apply { mkdirs() }
        val extCard = tempFolder.newFolder("extcard")
        val manager = WorkspaceManager(
            baseDir = tempFolder.newFolder("workspaces"),
            bindMounts = listOf(
                WorkspaceBindMount(sdcard, "/sdcard", access = MountAccess.READ_ONLY, sessionHostMount = true),
                WorkspaceBindMount(sdcard, "/storage/emulated/0", access = MountAccess.READ_ONLY, sessionHostMount = true),
            ),
        )
        val extra = listOf(
            WorkspaceBindMount(notes, "/storage/emulated/0/Notes", access = MountAccess.WRITABLE_AUTO),
            WorkspaceBindMount(extCard, "/storage/1234-5678/Work", access = MountAccess.WRITABLE_AUTO),
        )

        // 未开启 hostAccess 时，工作区外部存储挂载按配置生效
        val normalLayout = manager.layout(hostAccess = false, extraMounts = extra)
        assertFalse(normalLayout.isReadOnly("/storage/emulated/0/Notes/a.txt"))
        assertTrue(normalLayout.isWritable("/storage/emulated/0/Notes/a.txt"))
        assertTrue(normalLayout.isWritable("/storage/1234-5678/Work/a.txt"))

        // 开启 hostAccess 时，整个 /sdcard 与 /storage/emulated/0 强制只读（与 libsdcard_ro.so 保持一致）
        val hostLayout = manager.layout(hostAccess = true, extraMounts = extra)
        assertTrue(hostLayout.isReadOnly("/storage/emulated/0/Notes/a.txt"))
        assertFalse(hostLayout.isWritable("/storage/emulated/0/Notes/a.txt"))
        assertTrue(hostLayout.isReadOnly("/sdcard/Notes/a.txt"))
        assertFalse(hostLayout.isWritable("/sdcard/Notes/a.txt"))
        // 非 /storage/emulated/0 下的外置存储不受 libsdcard_ro.so 锁定影响
        assertFalse(hostLayout.isReadOnly("/storage/1234-5678/Work/a.txt"))
        assertTrue(hostLayout.isWritable("/storage/1234-5678/Work/a.txt"))
    }

    @Test
    fun dotSegmentsCannotEscapeWritableRoots() {
        val layout = WorkspaceLayout(
            workspaceDir = "/workspace",
            writableRoots = listOf("/workspace", "/storage/emulated/0"),
            approvalRoots = listOf("/storage/emulated/0/Documents"),
        )

        assertTrue(layout.isWritable("/workspace/./a/../b.txt"))
        assertFalse(layout.isWritable("/workspace/../etc/passwd"))
        assertFalse(layout.isWritable("/storage/emulated/0/x/../Documents/a.txt"))
        assertFalse(layout.isWritable("/../workspace/a.txt"))
    }

    @Test
    fun commandsFailWhenWorkspaceMountIsUnavailable() {
        val manager = WorkspaceManager(baseDir = tempFolder.newFolder("workspaces"))
        manager.ensureWorkspace("ws")
        val missing = listOf(
            WorkspaceBindMount(File(tempFolder.root, "missing"), "/storage/emulated/0/missing"),
        )

        val error = assertThrows(WorkspaceMountUnavailableException::class.java) {
            manager.executeCommand("ws", "true", extraMounts = missing)
        }
        assertEquals(listOf("/storage/emulated/0/missing"), error.targets)
        assertTrue(error.message!!.contains("/storage/emulated/0/missing"))
        assertThrows(WorkspaceMountUnavailableException::class.java) {
            manager.terminalLaunch("ws", shellCompatibilityMode = false, extraMounts = missing)
        }
    }
}
