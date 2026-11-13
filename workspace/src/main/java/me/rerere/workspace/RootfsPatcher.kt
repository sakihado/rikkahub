package me.rerere.workspace

import java.io.File
import java.nio.file.Files

/**
 * 给解压出来的 Linux Rootfs 写入在 Android PRoot 下正常运行所需的环境补丁:
 *
 * - `/etc/resolv.conf` 等网络基础文件: Android 没有 `/etc/resolv.conf`, 镜像里往往是指向
 *   `systemd-resolved` 的悬空符号链接, 不处理的话域名解析会失败。
 * - `/etc/profile.d/rikkahub-android.sh`: 登录 shell 启动时加载, 修好 `apt` 沙盒用户、
 *   区域设置和系统 CA 证书路径。
 * - `/etc/apt/apt.conf.d/99rikkahub-proot`: 非登录场景下直接调用 `apt-get` 也能生效。
 * - `/usr/local/bin/host-sh`: 单向纯只读宿主机诊断探针客户端（仅在当前会话挂载 `/run/host_bridge` 时可用）。
 *
 * 所有补丁都是幂等的; 版本号记录在标记文件里, 升级应用后已有工作区也会在下次启动命令时自动补齐。
 */
class RootfsPatcher {
    fun patch(linuxDir: File, nameservers: List<String> = emptyList()) {
        if (!WorkspaceManager.isUsableRootfs(linuxDir)) return
        // DNS 每次都刷新: 用户切换 Wi-Fi / 移动网络或开关 VPN 后上游服务器会变,
        // 内容相同时 writeRootfsFile 不会重写文件
        writeResolvConf(linuxDir, nameservers)
        val marker = File(linuxDir, PATCH_MARKER)
        if (marker.isFile && marker.readText().trim() == PATCH_VERSION) return

        writeHostsAndPasswd(linuxDir)
        writeAndroidProfile(linuxDir)
        writeAptProotConfig(linuxDir)
        cleanupLegacyTrashWrappers(linuxDir)
        writeHostBridgeScript(linuxDir)

        marker.parentFile?.mkdirs()
        marker.writeText(PATCH_VERSION)
    }

    private fun writeResolvConf(linuxDir: File, nameservers: List<String>) {
        val servers = nameservers.filter { it.isNotBlank() }.distinct().ifEmpty { FALLBACK_DNS }
        val content = buildString {
            servers.forEach { appendLine("nameserver $it") }
        }
        writeRootfsFile(linuxDir, "etc/resolv.conf", content)
    }

    private fun writeHostsAndPasswd(linuxDir: File) {
        val hosts = File(linuxDir, "etc/hosts")
        if (!hosts.exists() || hosts.length() == 0L) {
            writeRootfsFile(
                linuxDir,
                "etc/hosts",
                "127.0.0.1\tlocalhost\n::1\tlocalhost ip6-localhost ip6-loopback\n",
            )
        }
        // 部分精简 Rootfs 缺少基础用户/组表, git/ssh/tar/apt 等工具查 uid 0 时会报错
        val passwd = File(linuxDir, "etc/passwd")
        if (!passwd.exists()) {
            writeRootfsFile(linuxDir, "etc/passwd", "root:x:0:0:root:/root:/bin/sh\n")
        }
        val group = File(linuxDir, "etc/group")
        if (!group.exists()) {
            writeRootfsFile(linuxDir, "etc/group", "root:x:0:\n")
        }
    }

    private fun writeAndroidProfile(linuxDir: File) {
        writeRootfsFile(linuxDir, "etc/profile.d/rikkahub-android.sh", ANDROID_PROFILE_SCRIPT)
    }

    private fun writeAptProotConfig(linuxDir: File) {
        // 只有 Debian/Ubuntu 系才有 /etc/apt, Alpine 等发行版不写无用配置
        if (!File(linuxDir, "etc/apt").isDirectory) return
        writeRootfsFile(linuxDir, "etc/apt/apt.conf.d/99rikkahub-proot", APT_PROOT_CONFIG)
    }

    /**
     * 清理旧版本遗留的 `/usr/local/bin/{rm,rmdir,unlink}` 及 `/usr/local/libexec/rikkahub-sdcard-trash`
     * 回收站包装脚本：现已统一由 `libsdcard_ro.so` 在系统调用层强制只读 (`EROFS`)，不再劫持容器内的标准删除命令。
     */
    private fun cleanupLegacyTrashWrappers(linuxDir: File) {
        listOf(
            "usr/local/bin/rm",
            "usr/local/bin/rmdir",
            "usr/local/bin/unlink",
            "usr/local/libexec/rikkahub-sdcard-trash",
        ).forEach { relativePath ->
            val target = File(linuxDir, relativePath)
            if (target.isFile && runCatching { target.readText().contains(".Trash-RikkaHub") }.getOrDefault(false)) {
                runCatching { target.delete() }
            }
        }
    }

    /**
     * 注入宿主机单向只读诊断客户端 `/usr/local/bin/host-sh`：
     * - 仅在当前会话启用了本机存储与只读状态挂载（`/run/host_bridge/endpoint` 存在）时可用。
     * - 仅转发单条纯只读 Android 宿主查询指令（如 `dumpsys`、`getprop`、`pm list`、`logcat -d`），
     *   管道过滤与文件落盘一律留在工作区 Linux 容器内处理。
     */
    private fun writeHostBridgeScript(linuxDir: File) {
        writeRootfsExecutable(linuxDir, "usr/local/bin/host-sh", HOST_BRIDGE_SCRIPT)
    }

    private fun writeRootfsExecutable(linuxDir: File, relativePath: String, content: String) {
        writeRootfsFile(linuxDir, relativePath, content)
        File(linuxDir, relativePath).setExecutable(true, false)
    }

    /**
     * 写 Rootfs 内部文件前先 unlink 已有条目:
     * 镜像里的 `/etc/resolv.conf` 常是指向不存在目标的符号链接, 直接 `writeText` 会顺着坏链接失败,
     * 或者意外写到链接指向的别处。
     */
    private fun writeRootfsFile(linuxDir: File, relativePath: String, content: String) {
        val target = File(linuxDir, relativePath)
        val path = target.toPath()
        // 校验解析后的父目录仍在 linuxDir 内, 防止镜像里的恶意符号链接(如 etc -> /sdcard)把写入引出沙盒
        val canonicalLinux = linuxDir.canonicalFile
        val parent = target.parentFile ?: return
        if (parent.exists()) {
            val canonicalParent = parent.canonicalFile
            if (!canonicalParent.startsWith(canonicalLinux)) return
        }
        if (Files.isSymbolicLink(path)) {
            Files.delete(path)
        } else if (target.isFile && target.readText() == content) {
            return
        }
        parent.mkdirs()
        if (!parent.canonicalFile.startsWith(canonicalLinux)) return
        target.writeText(content)
    }

    companion object {
        private const val PATCH_VERSION = "7"
        private const val PATCH_MARKER = "etc/.rikkahub-patched"

        private val FALLBACK_DNS = listOf("8.8.8.8", "1.1.1.1", "223.5.5.5")

        // PRoot 用 --root-id 伪装 uid 0, apt 默认降权到 _apt 用户在无真实 CAP_SETUID 的 Android 内核上会失败;
        // seccomp 过滤在部分内核上同样会拦住 apt 的子进程
        private const val APT_PROOT_CONFIG = """
APT::Sandbox::User "root";
APT::Sandbox::Seccomp "false";
Acquire::Retries "3";
"""

        // 只在对应证书文件存在时才导出环境变量, 避免未装 ca-certificates 时把 curl/python 指到空路径上
        private const val ANDROID_PROFILE_SCRIPT = """
# Generated by RikkaHub for PRoot on Android
export HOME="${'$'}{HOME:-/root}"
export USER="${'$'}{USER:-root}"
export LOGNAME="${'$'}{LOGNAME:-root}"
export LANG="${'$'}{LANG:-C.UTF-8}"
export LC_ALL="${'$'}{LC_ALL:-C.UTF-8}"
export TMPDIR="${'$'}{TMPDIR:-/tmp}"
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

for _cert in /etc/ssl/certs/ca-certificates.crt /etc/ssl/cert.pem; do
    if [ -f "${'$'}_cert" ]; then
        export SSL_CERT_FILE="${'$'}_cert"
        export CURL_CA_BUNDLE="${'$'}_cert"
        export REQUESTS_CA_BUNDLE="${'$'}_cert"
        export NODE_EXTRA_CA_CERTS="${'$'}_cert"
        break
    fi
done
unset _cert
"""

        private const val HOST_BRIDGE_SCRIPT = """#!/bin/sh
# RikkaHub Read-Only Host Diagnostic Probe (PRoot -> Android Host UID 2000)
# Usage: host-sh [-t SEC] "<readonly_android_query>"
set -eu

ENDPOINT_FILE="/run/host_bridge/endpoint"
if [ ! -f "${'$'}ENDPOINT_FILE" ]; then
    echo "host-sh: Host access is disabled for this conversation. Enable 'Mount device storage (read-only)' in the chat '+' menu if needed." >&2
    exit 126
fi

TIMEOUT=45

while [ "${'$'}#" -gt 0 ]; do
    case "${'$'}1" in
        -t|--timeout)
            shift
            TIMEOUT="${'$'}{1:-45}"
            shift
            ;;
        --)
            shift
            break
            ;;
        -*)
            echo "Usage: host-sh [-t SEC] '<readonly_android_query>'" >&2
            exit 2
            ;;
        *)
            break
            ;;
    esac
done

if [ "${'$'}#" -eq 0 ]; then
    echo "Usage: host-sh [-t SEC] '<readonly_android_query>'" >&2
    exit 2
fi

CMD="${'$'}*"
PORT="${'$'}(sed -n 's/^PORT=//p' "${'$'}ENDPOINT_FILE" | tr -d '\r\n')"
TOKEN="${'$'}(sed -n 's/^TOKEN=//p' "${'$'}ENDPOINT_FILE" | tr -d '\r\n')"

if [ -z "${'$'}PORT" ] || [ -z "${'$'}TOKEN" ]; then
    echo "host-sh: invalid endpoint configuration" >&2
    exit 126
fi

HDR_TMP="${'$'}(mktemp /tmp/host_sh_hdr.XXXXXX)"
OUT_TMP="${'$'}(mktemp /tmp/host_sh_out.XXXXXX)"
cleanup() {
    rm -f "${'$'}HDR_TMP" "${'$'}OUT_TMP"
}
trap cleanup EXIT INT TERM

MAX_TIME=${'$'}((TIMEOUT + 5))

if command -v curl >/dev/null 2>&1; then
    curl -sS -X POST \
        --max-time "${'$'}MAX_TIME" \
        -H "X-Bridge-Token: ${'$'}TOKEN" \
        -H "X-Timeout-Sec: ${'$'}TIMEOUT" \
        -H "Content-Type: text/plain; charset=utf-8" \
        --data-binary "${'$'}CMD" \
        -D "${'$'}HDR_TMP" \
        -o "${'$'}OUT_TMP" \
        "http://127.0.0.1:${'$'}PORT/exec" || {
            echo "host-sh: failed to reach Android HostBridgeDaemon on 127.0.0.1:${'$'}PORT" >&2
            exit 125
        }
elif command -v python3 >/dev/null 2>&1; then
    python3 - "${'$'}PORT" "${'$'}TOKEN" "${'$'}TIMEOUT" "${'$'}MAX_TIME" "${'$'}HDR_TMP" "${'$'}OUT_TMP" "${'$'}CMD" <<'PY'
import sys, urllib.request, urllib.error

port, token, timeout_s, max_time, hdr_path, out_path, cmd = sys.argv[1:8]
req = urllib.request.Request(
    f"http://127.0.0.1:{port}/exec",
    data=cmd.encode("utf-8"),
    headers={
        "X-Bridge-Token": token,
        "X-Timeout-Sec": timeout_s,
        "Content-Type": "text/plain; charset=utf-8",
    },
    method="POST",
)
try:
    with urllib.request.urlopen(req, timeout=int(max_time)) as resp:
        with open(hdr_path, "w", encoding="utf-8") as hf:
            for k, v in resp.headers.items():
                hf.write(f"{k}: {v}\n")
        with open(out_path, "wb") as of:
            of.write(resp.read())
except Exception as e:
    sys.stderr.write(f"host-sh: request failed: {e}\n")
    sys.exit(125)
PY
else
    echo "host-sh: neither curl nor python3 is installed in Rootfs" >&2
    exit 127
fi

EXIT_CODE="${'$'}(grep -i '^X-Exit-Code:' "${'$'}HDR_TMP" | tail -n 1 | cut -d: -f2- | tr -d ' \r\n')"
STDERR_B64="${'$'}(grep -i '^X-Stderr-B64:' "${'$'}HDR_TMP" | tail -n 1 | cut -d: -f2- | tr -d ' \r\n')"

if [ -n "${'$'}STDERR_B64" ]; then
    if command -v base64 >/dev/null 2>&1; then
        printf '%s' "${'$'}STDERR_B64" | base64 -d >&2 2>/dev/null || true
    elif command -v python3 >/dev/null 2>&1; then
        python3 -c "import sys,base64; sys.stderr.buffer.write(base64.b64decode(sys.argv[1]))" "${'$'}STDERR_B64" || true
    fi
fi

cat "${'$'}OUT_TMP"
exit "${'$'}{EXIT_CODE:-1}"
"""
    }
}
