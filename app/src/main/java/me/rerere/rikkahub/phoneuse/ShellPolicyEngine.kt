package me.rerere.rikkahub.phoneuse

import me.rerere.rikkahub.data.ai.tools.local.PHONE_USE_BLOCKED_APPS

enum class PolicyDecision {
    /** 窄白名单内的纯只读查询：免审批静默执行 */
    ALLOW,
    /** 修改状态、Root 提权、复杂语法或未知命令：需要在聊天界面由用户点击确认 */
    ASK,
    /** 变砖、格机、破坏提权模块、触碰金融/密码应用：底层硬拦截，直接拒绝 */
    DENY,
}

data class PolicyEvaluation(
    val decision: PolicyDecision,
    val reason: String,
    /** 是否允许在当前会话记住审批（破坏性写操作如 rm / pm clear / uninstall 永远单次审批） */
    val canRememberInSession: Boolean = false,
)

/**
 * 统一宿主命令策略引擎（供 android_shell、工作区 host-sh、PhoneUse 共同调用）
 *
 * 参考 Claude Code 权限模型：
 * 1. 按复合操作符拆分所有子命令独立评估
 * 2. 优先级严格遵守 DENY > ASK > ALLOW
 * 3. 白名单外默认一律归入 ASK，不依赖黑名单穷举未知高危命令
 */
object ShellPolicyEngine {

    /** 金融、支付与核心密码管理器包名红线 */
    val BLOCKED_SENSITIVE_PACKAGES: Set<String> = PHONE_USE_BLOCKED_APPS + setOf(
        "com.x8bit.bitwarden",
        "com.kunzisoft.keepass.free",
        "com.kunzisoft.keepass.libre",
        "keepass2android.keepass2android",
        "com.agilebits.onepassword",
        "proton.android.pass",
        "com.dashlane",
    )

    private val PROTECTED_ROOT_PATHS = setOf(
        "/",
        "/*",
        "/sdcard",
        "/sdcard/*",
        "/storage",
        "/storage/*",
        "/storage/emulated",
        "/storage/emulated/*",
        "/storage/emulated/0",
        "/storage/emulated/0/*",
        "/data",
        "/data/*",
        "/data/data",
        "/data/data/*",
        "/data/user",
        "/data/user/0",
        "/data/user/0/*",
        "/system",
        "/system/*",
        "/vendor",
        "/product",
        "/apex",
        "/efs",
        "/persist",
        "/mnt",
    )

    private val HARD_DENY_BINARIES = setOf(
        "mkfs",
        "mke2fs",
        "mkswap",
        "fdisk",
        "sfdisk",
        "gdisk",
        "parted",
        "blockdev",
        "flash_image",
        "fastboot",
        "recovery",
        "wipe",
        "reboot",
        "shutdown",
        "halt",
        "poweroff",
        "setenforce",
        "insmod",
        "rmmod",
        "modprobe",
    )

    private val ESCALATION_BINARIES = setOf(
        "su",
        "magisk",
        "ksud",
        "apd",
        "rish",
        "nsenter",
        "chroot",
        "unshare",
    )

    private val ALWAYS_SINGLE_APPROVAL_BINARIES = setOf(
        "rm",
        "rmdir",
        "unlink",
        "mv",
        "dd",
        "pm",
        "settings",
        "wm",
        "setprop",
        "svc",
    )

    private val PURE_READ_ONLY_BINARIES = setOf(
        "ls",
        "stat",
        "file",
        "du",
        "df",
        "free",
        "uptime",
        "id",
        "whoami",
        "uname",
        "date",
        "pwd",
        "echo",
        "printf",
        "true",
        "false",
        "cat",
        "head",
        "tail",
        "grep",
        "egrep",
        "fgrep",
        "wc",
        "cut",
        "tr",
        "sort",
        "uniq",
        "diff",
        "cmp",
        "md5sum",
        "sha1sum",
        "sha256sum",
        "base64",
        "xxd",
        "od",
        "strings",
        "readlink",
        "realpath",
        "dirname",
        "basename",
        "which",
        "ps",
        "pgrep",
        "pidof",
        "lsof",
        "vmstat",
        "iostat",
        "netstat",
        "ss",
        "ifconfig",
        "getenforce",
        "getprop",
        "screencap",
        "lsmod",
        "printenv",
        "seq",
        "expr",
        "jq",
    )

    private val SAFE_REDIRECT_REGEX = Regex("""(?:[12&]?>>?\s*/dev/null|[12]>&[12])""")
    private val OUTPUT_REDIRECT_REGEX = Regex("""(?<![<>])>>?(?![>&])""")
    private val ADB_PATH_REGEX = Regex("""(^|[\s'"=/])/data/adb(/|[\s'"]|$)""")
    private val BLOCK_DEV_REGEX = Regex("""(^|[\s'"=])/dev/block(/|[\s'"]|$)""")

    fun evaluate(command: String, asRoot: Boolean = false): PolicyEvaluation {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) {
            return PolicyEvaluation(PolicyDecision.DENY, "命令不能为空")
        }

        // 1. 全局红线扫描（即使嵌套在 $() 或引号中也绝不放过）
        BLOCKED_SENSITIVE_PACKAGES.firstOrNull { pkg -> containsPackageToken(trimmed, pkg) }?.let { pkg ->
            return PolicyEvaluation(
                PolicyDecision.DENY,
                "安全红线拦截：禁止通过命令读取或操作受保护的金融/密码应用 ($pkg)"
            )
        }

        if (ADB_PATH_REGEX.containsMatchIn(trimmed)) {
            return PolicyEvaluation(
                PolicyDecision.DENY,
                "安全红线拦截：禁止访问或修改 /data/adb（保护 Magisk / Zygisk / Sui 模块与提权配置）"
            )
        }

        if (BLOCK_DEV_REGEX.containsMatchIn(trimmed)) {
            return PolicyEvaluation(
                PolicyDecision.DENY,
                "安全红线拦截：禁止直接操作分区块设备 (/dev/block)"
            )
        }

        // 2. 复合命令拆分与复杂语法检测
        val splitResult = splitCompoundCommands(trimmed)
        if (splitResult.unclosedQuotes) {
            return PolicyEvaluation(PolicyDecision.ASK, "引号未闭合或包含复杂转义，需人工确认")
        }

        var highestDecision = PolicyDecision.ALLOW
        var askReason = ""
        var allowSessionRemember = true

        if (asRoot) {
            highestDecision = PolicyDecision.ASK
            askReason = "请求以 Root (UID 0) 身份执行"
        }

        if (splitResult.hasExpansionOrSubshell) {
            highestDecision = maxOf(highestDecision, PolicyDecision.ASK)
            if (askReason.isEmpty()) {
                askReason = "包含命令替换 ($() / 反引号) 或子 Shell，需人工确认"
            }
            allowSessionRemember = false
        }

        if (hasFileOutputRedirect(trimmed)) {
            highestDecision = maxOf(highestDecision, PolicyDecision.ASK)
            if (askReason.isEmpty()) {
                askReason = "包含文件输出重定向 (> / >>)，需人工确认"
            }
            allowSessionRemember = false
        }

        val subcommands = splitResult.subcommands.filter { it.isNotBlank() }
        if (subcommands.isEmpty()) {
            return PolicyEvaluation(PolicyDecision.ASK, "无法解析子命令，需人工确认")
        }

        // 3. 逐条子命令评估（DENY 短路优先）
        for (sub in subcommands) {
            val eval = evaluateSubcommand(sub, asRoot)
            when (eval.decision) {
                PolicyDecision.DENY -> return eval
                PolicyDecision.ASK -> {
                    highestDecision = PolicyDecision.ASK
                    if (askReason.isEmpty() || !eval.canRememberInSession) {
                        askReason = eval.reason
                    }
                    if (!eval.canRememberInSession) {
                        allowSessionRemember = false
                    }
                }
                PolicyDecision.ALLOW -> Unit
            }
        }

        return when (highestDecision) {
            PolicyDecision.ALLOW -> PolicyEvaluation(PolicyDecision.ALLOW, "只读安全查询")
            PolicyDecision.ASK -> PolicyEvaluation(
                decision = PolicyDecision.ASK,
                reason = askReason.ifEmpty { "需要用户确认" },
                canRememberInSession = allowSessionRemember,
            )
            PolicyDecision.DENY -> PolicyEvaluation(PolicyDecision.DENY, askReason)
        }
    }

    private fun evaluateSubcommand(rawSub: String, asRoot: Boolean): PolicyEvaluation {
        val tokens = tokenize(rawSub)
        if (tokens.isEmpty()) {
            return PolicyEvaluation(PolicyDecision.ALLOW, "空子命令")
        }

        var idx = 0
        var hasRiskyEnv = false
        // 剥离前导环境变量赋值 KEY=VAL
        while (idx < tokens.size && isEnvAssignment(tokens[idx])) {
            val key = tokens[idx].substringBefore('=')
            if (key in setOf("LD_PRELOAD", "LD_LIBRARY_PATH", "PATH", "CLASSPATH", "BOOTCLASSPATH")) {
                hasRiskyEnv = true
            }
            idx++
        }
        if (idx >= tokens.size) {
            return if (hasRiskyEnv) {
                PolicyEvaluation(PolicyDecision.ASK, "修改敏感运行环境变量需确认")
            } else {
                PolicyEvaluation(PolicyDecision.ALLOW, "变量赋值")
            }
        }

        // 剥离无害包装命令 (timeout / time / nice / nohup)
        idx = stripSafeWrappers(tokens, idx)
        if (idx >= tokens.size) {
            return PolicyEvaluation(PolicyDecision.ASK, "包装命令缺少目标参数，需确认")
        }

        var cmd = binaryBasename(tokens[idx])
        var args = tokens.subList(idx + 1, tokens.size)

        // 解包 toybox / busybox 多合一二进制（防 /system/bin/toybox rm 绕过）
        if (cmd == "toybox" || cmd == "busybox") {
            val subIdx = args.indexOfFirst { !it.startsWith("-") }
            if (subIdx == -1) {
                return PolicyEvaluation(PolicyDecision.ALLOW, "$cmd 信息查询")
            }
            cmd = binaryBasename(args[subIdx])
            args = args.subList(subIdx + 1, args.size)
        }

        // ---- 第一层：DENY 规则 ----
        if (cmd in HARD_DENY_BINARIES || cmd.startsWith("mkfs.")) {
            return PolicyEvaluation(
                PolicyDecision.DENY,
                "安全红线拦截：禁止执行系统重启、分区格式化或内核级危险命令 ($cmd)"
            )
        }

        if (cmd == "svc" && args.getOrNull(0) == "power" &&
            args.getOrNull(1) in setOf("reboot", "shutdown")
        ) {
            return PolicyEvaluation(
                PolicyDecision.DENY,
                "安全红线拦截：禁止通过 svc power 重启或关机"
            )
        }

        if (cmd == "dd" && args.any {
                it.startsWith("of=/dev/") || it.startsWith("of=/system") || it.startsWith("of=/vendor")
            }
        ) {
            return PolicyEvaluation(
                PolicyDecision.DENY,
                "安全红线拦截：禁止使用 dd 写入设备节点或系统分区"
            )
        }

        // 防 UID 2000 下内嵌 su 自我提权逃逸
        if (!asRoot && (cmd in ESCALATION_BINARIES || args.any { binaryBasename(it) == "su" })) {
            return PolicyEvaluation(
                PolicyDecision.DENY,
                "非 Root 模式下禁止内嵌 $cmd 自我提权；若需 Root 权限请设置 root=true 并通过界面审批"
            )
        }
        if (asRoot && cmd in setOf("magisk", "ksud", "apd") &&
            args.any { it !in setOf("-v", "-V", "--version", "-c") }
        ) {
            return PolicyEvaluation(
                PolicyDecision.DENY,
                "安全红线拦截：禁止通过命令行修改 Root 管理器配置 ($cmd)"
            )
        }

        // 防整盘或顶层关键目录误删
        if (cmd in setOf("rm", "rmdir", "unlink")) {
            val targets = args.filterNot { it.startsWith("-") }
            for (target in targets) {
                val norm = normalizePath(target)
                if (norm in PROTECTED_ROOT_PATHS) {
                    return PolicyEvaluation(
                        PolicyDecision.DENY,
                        "安全红线拦截：禁止删除系统核心目录或整盘存储 ($target)"
                    )
                }
            }
            return PolicyEvaluation(
                PolicyDecision.ASK,
                "文件删除操作 ($cmd) 需确认",
                canRememberInSession = false,
            )
        }

        if (cmd == "find") {
            val hasDestructive = args.any {
                it in setOf("-delete", "-exec", "-execdir", "-ok", "-okdir") || it.startsWith("-fprint")
            }
            if (hasDestructive) {
                val searchPaths = args.takeWhile { !it.startsWith("-") && it != "!" && it != "(" }
                if (searchPaths.any { normalizePath(it) in PROTECTED_ROOT_PATHS }) {
                    return PolicyEvaluation(
                        PolicyDecision.DENY,
                        "安全红线拦截：禁止在顶层目录上执行 find -delete / -exec"
                    )
                }
                return PolicyEvaluation(
                    PolicyDecision.ASK,
                    "带执行或删除动作的 find 命令需确认",
                    canRememberInSession = false,
                )
            }
            return finishAllowOrEnvAsk(hasRiskyEnv, "find 只读搜索")
        }

        // ---- 第二层：细分 Android 与系统工具规则 ----
        if (cmd == "input") {
            return PolicyEvaluation(
                PolicyDecision.ASK,
                "屏幕点击/按键注入 (input) 需确认（日常操作界面请优先用带安全护栏的 Phone Use）",
                canRememberInSession = false,
            )
        }

        if (cmd == "dumpsys") {
            if (isMutatingDumpsys(args)) {
                return PolicyEvaluation(
                    PolicyDecision.ASK,
                    "此 dumpsys 参数会修改系统状态 (${args.take(3).joinToString(" ")})，需确认",
                    canRememberInSession = false,
                )
            }
            return finishAllowOrEnvAsk(hasRiskyEnv, "dumpsys 状态读取")
        }

        if (cmd == "pm") {
            val sub = args.firstOrNull { !it.startsWith("-") }
            if (sub in setOf(
                    "list", "path", "dump", "resolve-activity",
                    "query-activities", "query-services", "query-receivers"
                )
            ) {
                return finishAllowOrEnvAsk(hasRiskyEnv, "pm $sub 只读查询")
            }
            return PolicyEvaluation(
                PolicyDecision.ASK,
                "应用包管理变更 (pm ${sub.orEmpty()}) 需确认",
                canRememberInSession = false,
            )
        }

        if (cmd == "am") {
            if (args.getOrNull(0) == "stack" && args.getOrNull(1) == "list") {
                return finishAllowOrEnvAsk(hasRiskyEnv, "am stack list")
            }
            val sub = args.firstOrNull().orEmpty()
            return PolicyEvaluation(
                PolicyDecision.ASK,
                "Activity 管理操作 (am $sub) 需确认",
                canRememberInSession = sub == "start",
            )
        }

        if (cmd == "cmd") {
            val service = args.getOrNull(0)
            val action = args.getOrNull(1)
            val isSafeQuery = when (service) {
                "package" -> action in setOf("list", "path", "dump", "resolve-activity", "query-activities")
                "clipboard" -> action == "get-text"
                "appops" -> action == "get"
                "activity" -> action == "stack" && args.getOrNull(2) == "list"
                else -> false
            }
            if (isSafeQuery) {
                return finishAllowOrEnvAsk(hasRiskyEnv, "cmd $service $action")
            }
            return PolicyEvaluation(
                PolicyDecision.ASK,
                "系统服务指令 (cmd ${service.orEmpty()} ${action.orEmpty()}) 需确认",
                canRememberInSession = false,
            )
        }

        if (cmd == "settings") {
            val sub = args.firstOrNull { !it.startsWith("-") }
            if (sub in setOf("get", "list")) {
                return finishAllowOrEnvAsk(hasRiskyEnv, "settings $sub")
            }
            return PolicyEvaluation(
                PolicyDecision.ASK,
                "修改系统设置 (settings ${sub.orEmpty()}) 需确认",
                canRememberInSession = false,
            )
        }

        if (cmd == "wm") {
            if (args.size == 1 && args[0] in setOf("size", "density")) {
                return finishAllowOrEnvAsk(hasRiskyEnv, "wm ${args[0]} 查询")
            }
            return PolicyEvaluation(
                PolicyDecision.ASK,
                "修改屏幕分辨率或像素密度 (wm) 需确认",
                canRememberInSession = false,
            )
        }

        if (cmd == "appops") {
            if (args.firstOrNull() == "get") {
                return finishAllowOrEnvAsk(hasRiskyEnv, "appops get")
            }
            return PolicyEvaluation(
                PolicyDecision.ASK,
                "修改应用权限操作 (appops) 需确认",
                canRememberInSession = false,
            )
        }

        if (cmd == "logcat") {
            val clears = args.any { it == "-c" || it == "--clear" }
            if (clears) {
                return PolicyEvaluation(PolicyDecision.ASK, "清空系统日志 (logcat -c) 需确认", false)
            }
            val dumpsAndExits = args.any { arg ->
                arg == "-d" || arg == "--dump" ||
                    (arg.startsWith("-") && !arg.startsWith("--") && 'd' in arg)
            }
            if (dumpsAndExits) {
                return finishAllowOrEnvAsk(hasRiskyEnv, "logcat -d 只读读取")
            }
            return PolicyEvaluation(
                PolicyDecision.ASK,
                "logcat 未附带 -d 参数会持续阻塞，建议改为 logcat -d",
                canRememberInSession = false,
            )
        }

        if (cmd == "uiautomator") {
            if (args.firstOrNull() == "dump") {
                val target = args.getOrNull(1)
                if (target == null || target == "/proc/self/fd/1" || target == "/dev/tty" || target.startsWith("/tmp/")) {
                    return finishAllowOrEnvAsk(hasRiskyEnv, "uiautomator dump")
                }
            }
            return PolicyEvaluation(PolicyDecision.ASK, "uiautomator 操作需确认")
        }

        if (cmd == "sed") {
            val inPlace = args.any { it == "-i" || it.startsWith("-i") || it == "--in-place" }
            if (inPlace) {
                return PolicyEvaluation(PolicyDecision.ASK, "sed -i 原地修改文件需确认", false)
            }
            return finishAllowOrEnvAsk(hasRiskyEnv, "sed 文本处理")
        }

        if (cmd in setOf("awk", "gawk", "mawk")) {
            val canExec = args.any { "system(" in it || "getline" in it }
            if (canExec) {
                return PolicyEvaluation(PolicyDecision.ASK, "awk 包含外部调用需确认", false)
            }
            return finishAllowOrEnvAsk(hasRiskyEnv, "awk 文本过滤")
        }

        if (cmd == "sqlite3") {
            val readOnly = args.any { it == "-readonly" || it == "--readonly" }
            if (readOnly) {
                return finishAllowOrEnvAsk(hasRiskyEnv, "sqlite3 -readonly 查询")
            }
            return PolicyEvaluation(
                PolicyDecision.ASK,
                "非只读 sqlite3 访问需确认（只读查询请附带 -readonly 参数）",
                canRememberInSession = false,
            )
        }

        if (cmd == "top") {
            val bounded = args.any { it == "-n" || it.startsWith("-n") }
            return if (bounded) {
                finishAllowOrEnvAsk(hasRiskyEnv, "top -n 快照")
            } else {
                PolicyEvaluation(PolicyDecision.ASK, "top 未附带 -n 限制需确认")
            }
        }

        if (cmd == "ping") {
            val bounded = args.any { it == "-c" || it.startsWith("-c") }
            return if (bounded) {
                finishAllowOrEnvAsk(hasRiskyEnv, "ping -c 测试")
            } else {
                PolicyEvaluation(PolicyDecision.ASK, "ping 未附带 -c 限制需确认")
            }
        }

        if (cmd == "ip") {
            val obj = args.firstOrNull { !it.startsWith("-") }
            val mutating = args.any { it in setOf("add", "del", "delete", "change", "replace", "flush", "set") }
            if (!mutating && obj in setOf("addr", "a", "route", "r", "neigh", "rule", "link")) {
                return finishAllowOrEnvAsk(hasRiskyEnv, "ip $obj 查询")
            }
            return PolicyEvaluation(PolicyDecision.ASK, "修改网络栈配置 (ip) 需确认", false)
        }

        if (cmd == "mount") {
            return if (args.isEmpty()) {
                finishAllowOrEnvAsk(hasRiskyEnv, "mount 列表查看")
            } else {
                PolicyEvaluation(PolicyDecision.ASK, "挂载操作 (mount) 需确认", false)
            }
        }

        // ---- 第三层：纯只读基础工具窄白名单 ----
        if (cmd in PURE_READ_ONLY_BINARIES) {
            return finishAllowOrEnvAsk(hasRiskyEnv, "$cmd 只读命令")
        }

        // ---- 第四层：默认保守转审批 ----
        return PolicyEvaluation(
            decision = PolicyDecision.ASK,
            reason = "执行状态修改或非常规命令 ($cmd) 需确认",
            canRememberInSession = cmd !in ALWAYS_SINGLE_APPROVAL_BINARIES,
        )
    }

    private fun finishAllowOrEnvAsk(hasRiskyEnv: Boolean, okReason: String): PolicyEvaluation =
        if (hasRiskyEnv) {
            PolicyEvaluation(PolicyDecision.ASK, "修改敏感运行环境变量需确认")
        } else {
            PolicyEvaluation(PolicyDecision.ALLOW, okReason)
        }

    private fun isMutatingDumpsys(args: List<String>): Boolean {
        val nonFlags = args.filterNot { it.startsWith("-") }
        val service = nonFlags.getOrNull(0)?.lowercase() ?: return false
        val sub = nonFlags.getOrNull(1)?.lowercase() ?: return false
        return when (service) {
            "battery" -> sub in setOf("set", "unplug", "reset")
            "deviceidle" -> sub in setOf("force-idle", "unforce", "disable", "enable", "step", "whitelist")
            "autofill" -> sub == "set"
            "window" -> sub == "policy"
            else -> sub in setOf("set", "reset", "clear", "enable", "disable")
        }
    }

    private fun containsPackageToken(command: String, pkg: String): Boolean {
        var start = 0
        while (true) {
            val idx = command.indexOf(pkg, start)
            if (idx < 0) return false
            val beforeOk = idx == 0 || !command[idx - 1].isPackageChar()
            val afterIdx = idx + pkg.length
            val afterOk = afterIdx == command.length || !command[afterIdx].isPackageChar()
            if (beforeOk && afterOk) return true
            start = idx + 1
        }
    }

    private fun Char.isPackageChar(): Boolean = isLetterOrDigit() || this == '_' || this == '.'

    private fun hasFileOutputRedirect(command: String): Boolean {
        // 先剔除引号内的文本，再剔除安全的 >/dev/null 和 2>&1
        val unquoted = stripQuotedContent(command)
        val cleaned = SAFE_REDIRECT_REGEX.replace(unquoted, " ")
        return OUTPUT_REDIRECT_REGEX.containsMatchIn(cleaned)
    }

    private fun stripQuotedContent(s: String): String {
        val sb = StringBuilder(s.length)
        var inSingle = false
        var inDouble = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && !inSingle && i + 1 < s.length -> {
                    i += 2
                    continue
                }
                c == '\'' && !inDouble -> inSingle = !inSingle
                c == '"' && !inSingle -> inDouble = !inDouble
                !inSingle && !inDouble -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    private data class SplitResult(
        val subcommands: List<String>,
        val hasExpansionOrSubshell: Boolean,
        val unclosedQuotes: Boolean,
    )

    private fun splitCompoundCommands(s: String): SplitResult {
        val parts = mutableListOf<String>()
        val cur = StringBuilder()
        var inSingle = false
        var inDouble = false
        var hasExpansion = false
        var i = 0

        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && !inSingle) {
                cur.append(c)
                if (i + 1 < s.length) {
                    cur.append(s[i + 1])
                    i += 2
                    continue
                } else {
                    return SplitResult(emptyList(), hasExpansion, unclosedQuotes = true)
                }
            }
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle
                cur.append(c)
                i++
                continue
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble
                cur.append(c)
                i++
                continue
            }
            if (inDouble && (c == '`' || (c == '$' && i + 1 < s.length && (s[i + 1] == '(' || s[i + 1] == '{')))) {
                hasExpansion = true
            }
            if (!inSingle && !inDouble) {
                if (c == '`' || c == '(' || c == ')' ||
                    (c == '$' && i + 1 < s.length && (s[i + 1] == '(' || s[i + 1] == '{'))
                ) {
                    hasExpansion = true
                }
                when {
                    c == '\n' || c == ';' -> {
                        parts += cur.toString()
                        cur.setLength(0)
                        i++
                        continue
                    }
                    c == '&' && i + 1 < s.length && s[i + 1] == '&' -> {
                        parts += cur.toString()
                        cur.setLength(0)
                        i += 2
                        continue
                    }
                    c == '|' && i + 1 < s.length && (s[i + 1] == '|' || s[i + 1] == '&') -> {
                        parts += cur.toString()
                        cur.setLength(0)
                        i += 2
                        continue
                    }
                    c == '|' -> {
                        parts += cur.toString()
                        cur.setLength(0)
                        i++
                        continue
                    }
                    c == '&' -> {
                        // 排除 2>&1 或 &>/dev/null 中的 &
                        val prev = if (i > 0) s[i - 1] else ' '
                        val next = if (i + 1 < s.length) s[i + 1] else ' '
                        if (prev != '>' && next != '>') {
                            parts += cur.toString()
                            cur.setLength(0)
                            i++
                            continue
                        }
                    }
                }
            }
            cur.append(c)
            i++
        }

        if (cur.isNotEmpty()) {
            parts += cur.toString()
        }
        return SplitResult(
            subcommands = parts,
            hasExpansionOrSubshell = hasExpansion,
            unclosedQuotes = inSingle || inDouble,
        )
    }

    private fun tokenize(sub: String): List<String> {
        val tokens = mutableListOf<String>()
        val cur = StringBuilder()
        var inSingle = false
        var inDouble = false
        var hasTokenChar = false
        var i = 0
        while (i < sub.length) {
            val c = sub[i]
            when {
                c == '\\' && !inSingle && i + 1 < sub.length -> {
                    cur.append(sub[i + 1])
                    hasTokenChar = true
                    i += 2
                    continue
                }
                c == '\'' && !inDouble -> {
                    inSingle = !inSingle
                    hasTokenChar = true
                }
                c == '"' && !inSingle -> {
                    inDouble = !inDouble
                    hasTokenChar = true
                }
                c.isWhitespace() && !inSingle && !inDouble -> {
                    if (hasTokenChar) {
                        tokens += cur.toString()
                        cur.setLength(0)
                        hasTokenChar = false
                    }
                }
                else -> {
                    // 忽略行尾安全重定向如 2>/dev/null 或 2>&1
                    cur.append(c)
                    hasTokenChar = true
                }
            }
            i++
        }
        if (hasTokenChar) {
            tokens += cur.toString()
        }
        return tokens.filterNot { token ->
            token == "2>&1" || token == "1>&2" || token == ">/dev/null" ||
                token == "2>/dev/null" || token == "&>/dev/null"
        }
    }

    private fun isEnvAssignment(token: String): Boolean {
        val eq = token.indexOf('=')
        if (eq <= 0) return false
        val name = token.substring(0, eq)
        return name.first().let { it.isLetter() || it == '_' } &&
            name.all { it.isLetterOrDigit() || it == '_' }
    }

    private fun stripSafeWrappers(tokens: List<String>, startIdx: Int): Int {
        var i = startIdx
        while (i < tokens.size) {
            when (binaryBasename(tokens[i])) {
                "time", "nohup" -> i++
                "nice" -> {
                    i++
                    if (i < tokens.size && tokens[i] == "-n") i += 2
                    else if (i < tokens.size && tokens[i].startsWith("-")) i++
                }
                "timeout" -> {
                    i++
                    while (i < tokens.size && tokens[i].startsWith("-")) {
                        val flag = tokens[i]
                        i++
                        if (flag in setOf("-k", "-s", "--kill-after", "--signal") && i < tokens.size) {
                            i++
                        }
                    }
                    // 跳过超时秒数参数
                    if (i < tokens.size) i++
                }
                else -> break
            }
        }
        return i
    }

    private fun binaryBasename(token: String): String =
        token.substringAfterLast('/').lowercase()

    private fun normalizePath(path: String): String {
        val collapsed = path.trim().replace(Regex("/+"), "/")
        return if (collapsed.length > 1 && collapsed.endsWith("/") && !collapsed.endsWith("/*")) {
            collapsed.trimEnd('/')
        } else {
            collapsed
        }
    }
}
