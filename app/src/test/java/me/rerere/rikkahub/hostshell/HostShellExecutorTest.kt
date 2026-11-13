package me.rerere.rikkahub.hostshell

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

class HostShellExecutorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `normal command with lifeline fallback completes cleanly without premature EOF kill`() = runBlocking {
        val executor = HostShellExecutor(context = null)
        val script = executor.buildLifelineFallbackScript("sleep 0.25; printf 'NORMAL_OK'")
        val res = executor.runLocalProcess(
            commandArgs = listOf("/bin/sh", "-c", script),
            keepStdinAsLifeline = true,
            timeoutMs = 5_000L,
            maxStdoutBytes = 4096,
        )

        assertFalse("Normal command must not be marked timedOut", res.timedOut)
        assertEquals(0, res.exitCode)
        assertEquals("NORMAL_OK", res.stdout)
    }

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

        assertTrue("Expected timeout after 350ms, got exitCode=${res.exitCode}", res.timedOut)
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
    fun `cancelling remote Binder execution closes lifeline immediately even if waitForTimeout ignores Thread interrupt`() = runBlocking {
        val executor = HostShellExecutor(context = null)
        val lifelineClosed = AtomicBoolean(false)
        val enteredBlockingBinderCall = CompletableDeferred<Unit>()

        val job = launch {
            executor.awaitBlockingRemoteProcess(
                lifelineHandle = Closeable { lifelineClosed.set(true) },
                stdoutStream = InputStream.nullInputStream(),
                stderrStream = InputStream.nullInputStream(),
                timeoutMs = 10_000L,
                maxStdoutBytes = 1024,
                waitForTimeout = { ms ->
                    enteredBlockingBinderCall.complete(Unit)
                    // 模拟完全忽略 Thread.interrupt() 的同步内核 Binder 阻塞调用（长达 2 秒）
                    val deadline = System.nanoTime() + minOf(ms, 2_000L) * 1_000_000L
                    while (System.nanoTime() < deadline) {
                        try {
                            Thread.sleep(25L)
                        } catch (_: InterruptedException) {
                            // 故意吞掉中断信号，模拟不可中断的同步 Binder ioctl！
                        }
                    }
                    false
                },
                exitValue = { 0 },
                destroyRemote = {},
            )
        }

        enteredBlockingBinderCall.await()
        val cancelStartNs = System.nanoTime()
        job.cancel()
        job.join()
        val elapsedMs = (System.nanoTime() - cancelStartNs) / 1_000_000L

        assertTrue("Lifeline must be closed immediately upon cancellation", lifelineClosed.get())
        assertTrue(
            "Cancellation must not wait for 2000ms uninterruptible Binder call (took ${elapsedMs}ms)",
            elapsedMs < 300L,
        )
    }

    @Test
    fun `Sui backend fails closed with exit 126 when root is false and shelldrop is unavailable`() = runBlocking {
        val pm = HostPrivilegeManager(context = null)
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
