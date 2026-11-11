package me.rerere.rikkahub.phoneuse

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HostShellExecutorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `timeout kills spawned background grandchild process cleanly`() = runBlocking {
        val executor = HostShellExecutor(context = null)
        val marker = File(tempFolder.root, "grandchild_escaped.txt")
        val startedMarker = File(tempFolder.root, "grandchild_started.txt")

        // 父 shell 派生一个后台子进程，等待 1.2s 后写文件；我们在 350ms 时触发超时
        val script = executor.buildLifelineFallbackScript(
            "( touch '${startedMarker.absolutePath}'; sleep 1.2; echo ESCAPED > '${marker.absolutePath}' ) & wait"
        )
        val res = executor.runLocalProcess(
            commandArgs = listOf("/bin/sh", "-c", script),
            keepStdinAsLifeline = true,
            timeoutMs = 350L,
            maxStdoutBytes = 4096,
        )

        assertTrue(res.timedOut)
        assertEquals(124, res.exitCode)
        assertTrue("Background child should have started before timeout", startedMarker.exists())

        // 等待超过 1.2s，验证后台子进程已随超时被彻底强杀，未写出标记文件
        delay(1_300L)
        assertFalse("Grandchild process escaped timeout cleanup and wrote marker!", marker.exists())
    }

    @Test
    fun `coroutine cancellation kills spawned background grandchild process cleanly`() = runBlocking {
        val executor = HostShellExecutor(context = null)
        val marker = File(tempFolder.root, "cancelled_escaped.txt")
        val startedMarker = File(tempFolder.root, "cancelled_started.txt")

        val script = executor.buildLifelineFallbackScript(
            "( touch '${startedMarker.absolutePath}'; sleep 1.2; echo ESCAPED > '${marker.absolutePath}' ) & wait"
        )
        val job = launch {
            executor.runLocalProcess(
                commandArgs = listOf("/bin/sh", "-c", script),
                keepStdinAsLifeline = true,
                timeoutMs = 10_000L,
                maxStdoutBytes = 4096,
            )
        }

        while (!startedMarker.exists()) {
            delay(30L)
        }
        job.cancel()
        job.join()

        delay(1_300L)
        assertFalse("Grandchild process escaped cancellation cleanup!", marker.exists())
    }

    @Test
    fun `Sui backend fails closed with exit 126 when root is false and shelldrop is unavailable`() = runBlocking {
        val pm = HostPrivilegeManager(context = null)
        // 通过反射将测试状态置为 SUI_ROOT，而 context=null 时 native libshelldrop 不存在
        val stateField = HostPrivilegeManager::class.java.getDeclaredField("_backendState").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val flow = stateField.get(pm) as kotlinx.coroutines.flow.MutableStateFlow<HostBackendType>
        flow.value = HostBackendType.SUI_ROOT

        val executor = HostShellExecutor(context = null, privilegeManager = pm)
        val res = executor.execute("id -u", asRoot = false, timeoutMs = 3_000L)

        assertEquals(126, res.exitCode)
        assertEquals("uid2000-unavailable", res.effectiveIdentity)
        assertTrue(res.stderr.contains("Refusing to silently escalate"))
    }
}
