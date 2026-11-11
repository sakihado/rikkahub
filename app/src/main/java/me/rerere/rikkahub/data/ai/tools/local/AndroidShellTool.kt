package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.phoneuse.HostShellExecutor
import me.rerere.rikkahub.phoneuse.PolicyDecision
import me.rerere.rikkahub.phoneuse.ShellPolicyEngine
import java.io.File
import kotlin.uuid.Uuid

const val ANDROID_SHELL_TOOL_NAME = "android_shell"

private const val INLINE_MAX_CHARS = 16 * 1024
private const val INLINE_PREVIEW_CHARS = 4 * 1024

internal fun buildAndroidShellTool(
    executor: HostShellExecutor,
    toolOutputsDir: File,
): Tool = Tool(
    name = ANDROID_SHELL_TOOL_NAME,
    description = """
        Execute an Android host shell command on the user's real device (runs as UID 2000 ADB/Sui shell by default, or UID 0 Root when `root=true`).
        Ideal for fast, zero-screenshot inspection and control of the live Android environment:
        - System & battery/memory diagnostics: `dumpsys battery`, `dumpsys meminfo`, `dumpsys activity top`, `getprop`, `ps -A`, `df -h`
        - Notifications & clipboard: `dumpsys notification --noredact`, `cmd clipboard get-text`
        - Crash & runtime logs: `logcat -d -t 200` (always pass `-d` so logcat exits immediately)
        - App inspection & deep links: `pm list packages -3`, `pm path <pkg>`, `am start -a android.intent.action.VIEW -d "<uri>"`
        - Private app SQLite inspection (requires `root=true`): `sqlite3 -readonly /data/data/<pkg>/databases/... "SELECT ..."`
        Read-only diagnostic commands run automatically without prompting. State-changing commands (`pm clear`, `settings put`, `rm`, `am`, `input`, etc.) or `root=true` will pause for user approval in the chat UI.
        Destructive partition/bootloader/Magisk-module commands and banking/password-manager package access are strictly blocked.
        Note: If a Linux workspace is bound and you need heavy text/JSON/SQLite processing, prefer piping `host-sh "<readonly_cmd>"` inside `workspace_shell` to Python/jq.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Android shell command to execute (non-interactive)")
                })
                put("root", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Run as UID 0 root instead of UID 2000 shell. Only set true when accessing /data/data or root-only paths. Defaults to false.")
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put("description", "Timeout in seconds (1-120, default 20)")
                })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { input ->
        shouldRequireApproval(input, executor)
    },
    execute = { input ->
        val params = input.jsonObject
        val command = params.optString("command")?.trim().orEmpty()
        val asRoot = params.optBoolean("root") ?: false
        val timeoutSec = (params.optInt("timeout") ?: 20).coerceIn(1, 120)

        val policy = ShellPolicyEngine.evaluate(command, asRoot)
        if (policy.decision == PolicyDecision.DENY) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("exitCode", 126)
                        put("blocked", true)
                        put("stderr", policy.reason)
                        put("stdout", "")
                    }.toString()
                )
            )
        }

        // 若该命令刚经过用户点击允许，且属于可会话复用的非破坏性命令，记录到本会话缓存
        if (policy.decision == PolicyDecision.ASK && policy.canRememberInSession) {
            executor.recordSessionApproval(command, asRoot)
        }

        val result = executor.execute(
            command = command,
            asRoot = asRoot,
            timeoutMs = timeoutSec * 1000L,
        )

        val fullStdout = result.stdout
        val stderrText = result.stderr
        val savedOutputPath: String?
        val returnedStdout: String

        if (fullStdout.length > INLINE_MAX_CHARS) {
            toolOutputsDir.mkdirs()
            val fileName = "android_shell_${Uuid.random()}.txt"
            File(toolOutputsDir, fileName).writeText(fullStdout)
            savedOutputPath = "/tool_outputs/$fileName"
            returnedStdout = buildString {
                appendLine("[Output is ${fullStdout.length} chars; full content saved to $savedOutputPath]")
                appendLine(fullStdout.take(INLINE_PREVIEW_CHARS))
                appendLine("\n... (truncated, ${fullStdout.length - INLINE_PREVIEW_CHARS} more chars in $savedOutputPath)")
            }
        } else {
            savedOutputPath = null
            returnedStdout = fullStdout
        }

        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("exitCode", result.exitCode)
                    put("identity", result.effectiveIdentity)
                    put("stdout", returnedStdout)
                    put("stderr", stderrText)
                    put("timedOut", result.timedOut)
                    if (savedOutputPath != null) {
                        put("savedOutputPath", savedOutputPath)
                    }
                }.toString()
            )
        )
    },
)

private fun shouldRequireApproval(input: JsonElement, executor: HostShellExecutor): Boolean {
    val params = runCatching { input.jsonObject }.getOrNull() ?: return true
    val command = params.optString("command")?.trim().orEmpty()
    if (command.isEmpty()) return false
    val asRoot = params.optBoolean("root") ?: false

    val policy = ShellPolicyEngine.evaluate(command, asRoot)
    return when (policy.decision) {
        PolicyDecision.ALLOW -> false
        // DENY 命令无需弹审批框让用户误点，直接放进 execute 里立即返回硬拦截报错
        PolicyDecision.DENY -> false
        PolicyDecision.ASK -> {
            if (policy.canRememberInSession && executor.hasSessionApproval(command, asRoot)) {
                false
            } else {
                true
            }
        }
    }
}

private fun JsonObject.optString(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.optBoolean(key: String): Boolean? =
    this[key]?.jsonPrimitive?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }

private fun JsonObject.optInt(key: String): Int? =
    this[key]?.jsonPrimitive?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
