package me.rerere.rikkahub.hostshell

/**
 * 工作区单向只读宿主探针（`host-sh`）的专用固定指令校验与构造器。
 *
 * 与特权智能体的通用 `ShellPolicyEngine` 彻底解耦：
 * 1. **禁止一切 Shell 元字符与复合语法**（`|`, `;`, `&`, `>`, `<`, `$`, `` ` ``, `\`, 换行等），
 *    所有管道过滤、正则提取、JSON/SQLite 处理与文件落盘一律留在 PRoot 容器内部完成；
 * 2. **严格只读系统探针白名单**，并禁止所有带文件写出副作用的参数（如 `logcat -f`、`screencap /sdcard/...`）；
 * 3. **参数级单引号安全重封装**：解析出纯参数数组后逐个经 [shellSingleQuote] 重新拼装，
 *    宿主机 `/system/bin/sh` 绝无发生二次变量展开、通配符展开或命令注入的可能。
 */
object HostQueryEvaluator {

    sealed interface Result {
        data class Allowed(val safeQuotedCommand: String) : Result
        data class Rejected(val reason: String) : Result
    }

    private val FORBIDDEN_METACHARS = charArrayOf(';', '|', '&', '>', '<', '`', '$', '\\', '\n', '\r', '(', ')', '*', '?', '[', ']')

    private val MUTATING_DUMPSYS_SUBCOMMANDS = setOf(
        "set", "reset", "unplug", "enable", "disable", "step", "force-idle", "unforce", "whitelist", " write",
    )

    fun evaluate(rawQuery: String): Result {
        val trimmed = rawQuery.trim()
        if (trimmed.isEmpty()) {
            return Result.Rejected("host-sh query cannot be empty.")
        }

        // 1. 拒绝所有 Shell 元字符与通配符，强制把管道和脚本留在 PRoot 容器内
        val badChar = trimmed.firstOrNull { it in FORBIDDEN_METACHARS }
        if (badChar != null) {
            val display = when (badChar) {
                '\n', '\r' -> "\\n"
                else -> badChar.toString()
            }
            return Result.Rejected(
                "Shell operator/metacharacter '$display' is not allowed in host-sh. " +
                    "Run a single read-only query in host-sh and pipe its stdout inside workspace_shell (e.g. `host-sh \"dumpsys battery\" | grep level`)."
            )
        }

        val tokens = tokenizeSimpleArgs(trimmed)
            ?: return Result.Rejected("Unclosed quotes in host-sh query.")
        if (tokens.isEmpty()) {
            return Result.Rejected("host-sh query cannot be empty.")
        }

        // 2. 敏感应用与系统提权目录红线拦截
        for (token in tokens) {
            ShellPolicyEngine.BLOCKED_SENSITIVE_PACKAGES.firstOrNull { pkg ->
                token == pkg || token.contains(pkg)
            }?.let { pkg ->
                return Result.Rejected("Protected financial/password package '$pkg' cannot be queried via host-sh.")
            }
            if (token.contains("/data/adb") || token.contains("/dev/block")) {
                return Result.Rejected("Protected system path cannot be queried via host-sh.")
            }
        }

        val cmd = tokens[0].substringAfterLast('/').lowercase()
        val args = tokens.drop(1)

        // 3. 窄白名单校验（纯只读系统诊断指令）
        val errorReason: String? = when (cmd) {
            "getprop", "ps", "pidof" -> null

            "id", "uname", "uptime", "date" -> null

            "df" -> if (args.any { !it.startsWith("-") && !it.startsWith("/data") && !it.startsWith("/storage") && !it.startsWith("/system") }) {
                "df only supports standard filesystem flags/paths."
            } else null

            "wm" -> {
                val sub = args.firstOrNull()
                if (sub !in setOf("size", "density") || args.size > 1) {
                    "host-sh only allows read-only `wm size` or `wm density` without arguments."
                } else null
            }

            "screencap" -> {
                if (args.any { it != "-p" }) {
                    "host-sh only allows `screencap -p` (PNG to stdout, no file path)."
                } else null
            }

            "uiautomator" -> {
                if (args.firstOrNull() != "dump" || args.getOrNull(1) != "/proc/self/fd/1" || args.size != 2) {
                    "host-sh only allows `uiautomator dump /proc/self/fd/1`."
                } else null
            }

            "settings" -> {
                val sub = args.firstOrNull()
                if (sub !in setOf("get", "list")) {
                    "host-sh only allows `settings get <ns> <key>` or `settings list <ns>`."
                } else null
            }

            "pm" -> {
                val sub = args.firstOrNull()
                if (sub !in setOf("list", "path", "dump")) {
                    "host-sh only allows read-only `pm list`, `pm path`, or `pm dump`."
                } else null
            }

            "dumpsys" -> {
                val service = args.firstOrNull()?.lowercase()
                when {
                    // 拒绝读取通知详情（防静默提取短信验证码或金融交易通知）
                    service == "notification" ->
                        "`dumpsys notification` is restricted in host-sh to protect verification codes and private notifications."
                    args.any { it.lowercase() in MUTATING_DUMPSYS_SUBCOMMANDS } ->
                        "Mutating dumpsys subcommands (set/reset/unplug/enable/disable) are not allowed in host-sh."
                    else -> null
                }
            }

            "logcat" -> {
                when {
                    args.none { it == "-d" || (it.startsWith("-") && !it.startsWith("--") && 'd' in it) } ->
                        "`logcat` in host-sh must include `-d` (non-blocking dump to stdout)."
                    args.any { it == "-f" || it.startsWith("-f") || it == "--file" || it == "-c" || it == "--clear" } ->
                        "`logcat` file output (`-f`) and buffer clear (`-c`) are not allowed in host-sh."
                    else -> null
                }
            }

            else -> "`$cmd` is not in the read-only host-sh probe allowlist (supported: getprop, dumpsys, pm list/path/dump, settings get/list, logcat -d, wm size/density, screencap -p, uiautomator dump, ps, pidof, df, uname, uptime, id)."
        }

        if (errorReason != null) {
            return Result.Rejected(errorReason)
        }

        // 使用裸命令名（已限定在系统 PATH 内）+ 全参数单引号封装，杜绝任意路径可执行文件调用
        val canonicalTokens = listOf(cmd) + args
        val safeCmd = canonicalTokens.joinToString(" ") { shellSingleQuote(it) }
        return Result.Allowed(safeCmd)
    }

    private fun tokenizeSimpleArgs(input: String): List<String>? {
        val result = mutableListOf<String>()
        val cur = StringBuilder()
        var hasToken = false
        var inSingle = false
        var inDouble = false

        for (c in input) {
            when {
                c == '\'' && !inDouble -> {
                    inSingle = !inSingle
                    hasToken = true
                }
                c == '"' && !inSingle -> {
                    inDouble = !inDouble
                    hasToken = true
                }
                c.isWhitespace() && !inSingle && !inDouble -> {
                    if (hasToken) {
                        result += cur.toString()
                        cur.setLength(0)
                        hasToken = false
                    }
                }
                else -> {
                    cur.append(c)
                    hasToken = true
                }
            }
        }
        if (inSingle || inDouble) return null
        if (hasToken) result += cur.toString()
        return result
    }
}
