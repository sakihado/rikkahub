package me.rerere.rikkahub.service

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.rerere.rikkahub.hostshell.HostQueryEvaluator
import me.rerere.rikkahub.hostshell.HostShellExecutor
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

private const val TAG = "HostBridgeDaemon"
private const val MAX_REQUEST_BODY_BYTES = 64 * 1024
private const val MAX_HEADER_STDERR_BYTES = 6 * 1024

/**
 * 宿主机与 PRoot 工作区之间的单向只读诊断桥接服务（纯 `java.net.ServerSocket` 无状态实现）。
 *
 * - 仅绑定 `127.0.0.1` 随机端口，启动时生成 256-bit 随机鉴权 Token 写入 `filesDir/host_bridge/endpoint`
 *   （仅在当前对话显式开启本机存储与只读状态挂载时，PRoot 才会挂载 `/run/host_bridge`）。
 * - 与特权智能体的 `android_shell` 及 `ShellApprovalStore` 彻底解耦：
 *   仅经 [HostQueryEvaluator] 接收无 Shell 元字符的固定只读系统探针指令，并固定以 `asRoot = false`（UID 2000）执行。
 * - 当工作区取消命令导致客户端断开连接时，实时感知 Socket EOF 并取消协程强杀宿主侧子进程组。
 */
class HostBridgeDaemon(
    private val bridgeDir: File,
    private val executor: HostShellExecutor,
) {
    private val token: String = generateToken()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Synchronized
    fun start() {
        if (serverSocket != null) return
        try {
            bridgeDir.mkdirs()
            val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
            serverSocket = server

            val endpointFile = File(bridgeDir, "endpoint")
            endpointFile.writeText("PORT=${server.localPort}\nTOKEN=$token\n")
            endpointFile.setReadable(true, false)

            scope.launch {
                while (!server.isClosed) {
                    val client = try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                    launch { handleClient(client) }
                }
            }
            Log.i(TAG, "HostBridgeDaemon listening on 127.0.0.1:${server.localPort}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HostBridgeDaemon", e)
        }
    }

    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { File(bridgeDir, "endpoint").delete() }
        scope.cancel()
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use { sock ->
            sock.soTimeout = 0
            val input = BufferedInputStream(sock.getInputStream())
            val req = readHttpRequest(input) ?: return

            if (req.method != "POST" || req.path != "/exec") {
                writeResponse(sock, 405, 126, ByteArray(0), "Method or path not allowed\n")
                return
            }

            val reqToken = req.headers["x-bridge-token"].orEmpty()
            if (!constantTimeEquals(reqToken, token)) {
                writeResponse(sock, 403, 126, ByteArray(0), "Forbidden: invalid bridge token\n")
                return
            }

            if (req.oversized) {
                writeResponse(sock, 413, 126, ByteArray(0), "host-sh: query body too large\n")
                return
            }

            val timeoutSec = req.headers["x-timeout-sec"]?.toIntOrNull()?.coerceIn(1, 120) ?: 45
            val rawQuery = req.body.toString(Charsets.UTF_8).trim()

            val safeCommand = when (val check = HostQueryEvaluator.evaluate(rawQuery)) {
                is HostQueryEvaluator.Result.Rejected -> {
                    writeResponse(
                        socket = sock,
                        httpCode = 200,
                        exitCode = 126,
                        stdout = ByteArray(0),
                        stderr = "[Read-Only Host Probe Rejected] ${check.reason}\n",
                    )
                    return
                }
                is HostQueryEvaluator.Result.Allowed -> check.safeQuotedCommand
            }

            if (!executor.backendState.value.isPrivileged && !executor.ensurePrivilegedAccess(interactive = false)) {
                writeResponse(
                    socket = sock,
                    httpCode = 200,
                    exitCode = 126,
                    stdout = ByteArray(0),
                    stderr = "host-sh: Sui / Shizuku / Root privilege is not active. Grant permission in Sui/Shizuku first.\n",
                )
                return
            }

            val execJob = scope.async {
                executor.execute(
                    command = safeCommand,
                    asRoot = false,
                    timeoutMs = timeoutSec * 1000L,
                    interactiveAuth = false,
                )
            }

            // 监听客户端连接断开（例如用户停止了 workspace_shell），立即联动取消并强杀宿主命令
            val disconnectWatcher = scope.launch {
                try {
                    while (execJob.isActive) {
                        val b = input.read()
                        if (b == -1) {
                            execJob.cancel()
                            break
                        }
                    }
                } catch (_: Exception) {
                    if (execJob.isActive) execJob.cancel()
                }
            }

            val result = try {
                execJob.await()
            } finally {
                disconnectWatcher.cancel()
            }

            val stderrText = buildString {
                if (result.stderr.isNotEmpty()) append(result.stderr)
                if (result.timedOut) append("\n[host-sh: command timed out after ${timeoutSec}s]\n")
            }
            writeResponse(
                socket = sock,
                httpCode = 200,
                exitCode = result.exitCode,
                stdout = result.stdoutBytes,
                stderr = stderrText,
            )
        }
    }

    private fun writeResponse(
        socket: Socket,
        httpCode: Int,
        exitCode: Int,
        stdout: ByteArray,
        stderr: String,
    ) {
        val out = socket.getOutputStream()
        val stderrB64 = if (stderr.isNotEmpty()) {
            val truncatedErr = stderr.toByteArray(Charsets.UTF_8).let {
                if (it.size > MAX_HEADER_STDERR_BYTES) it.copyOf(MAX_HEADER_STDERR_BYTES) else it
            }
            Base64.encodeToString(truncatedErr, Base64.NO_WRAP)
        } else {
            ""
        }

        val statusText = if (httpCode == 200) "OK" else "Error"
        val headers = buildString {
            append("HTTP/1.1 $httpCode $statusText\r\n")
            append("Connection: close\r\n")
            append("Content-Type: application/octet-stream\r\n")
            append("X-Exit-Code: $exitCode\r\n")
            if (stderrB64.isNotEmpty()) {
                append("X-Stderr-B64: $stderrB64\r\n")
            }
            append("Content-Length: ${stdout.size}\r\n")
            append("\r\n")
        }
        out.write(headers.toByteArray(Charsets.US_ASCII))
        if (stdout.isNotEmpty()) {
            out.write(stdout)
        }
        out.flush()
    }

    private data class ParsedHttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray,
        val oversized: Boolean = false,
    )

    private fun readHttpRequest(input: InputStream): ParsedHttpRequest? {
        val requestLine = readAsciiLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val path = parts[1]

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readAsciiLine(input) ?: return null
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }

        val rawLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (rawLength < 0 || rawLength > MAX_REQUEST_BODY_BYTES) {
            return ParsedHttpRequest(method, path, headers, ByteArray(0), oversized = rawLength > MAX_REQUEST_BODY_BYTES)
        }
        val body = ByteArray(rawLength)
        var offset = 0
        while (offset < rawLength) {
            val r = input.read(body, offset, rawLength - offset)
            if (r < 0) break
            offset += r
        }
        val actualBody = if (offset == rawLength) body else body.copyOf(offset)
        return ParsedHttpRequest(method, path, headers, actualBody)
    }

    private fun readAsciiLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b < 0) {
                return if (buf.size() == 0) null else buf.toString("US-ASCII")
            }
            if (b == '\n'.code) {
                val bytes = buf.toByteArray()
                val len = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, len, Charsets.US_ASCII)
            }
            if (buf.size() < 16_384) {
                buf.write(b)
            }
        }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }
}
