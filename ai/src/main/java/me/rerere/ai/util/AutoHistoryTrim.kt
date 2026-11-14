package me.rerere.ai.util

/**
 * 按字节估算 token 数的经验系数：英文约 4 字节 / token，中文更省。
 *
 * 只用来把「已释放多少字节」折算成上下文占用的减少量，精度要求不高。
 */
const val BYTES_PER_TOKEN_ESTIMATE = 4L

/**
 * 长任务中途自动裁剪工具输出的配置，对应聊天页工具栏图标的三个滑块。
 *
 * 三个值都是 0 表示关闭；任一为 0（滑块在最左端）同样视为关闭，不会触发自动裁剪。
 */
data class AutoTrimConfig(
    /** 保留最近多少个工具步骤的完整输出，更早的才裁剪 */
    val keepRecentSteps: Int,
    /** 本次至少能释放多少字节才动手，避免为一点点体积破坏提示词缓存 */
    val minFreeBytes: Long,
    /** 上下文占用达到多少 token 后才开始考虑裁剪 */
    val contextTokens: Int,
) {
    val isEnabled: Boolean
        get() = keepRecentSteps > 0 && minFreeBytes > 0 && contextTokens > 0

    companion object {
        /** 全部关闭 */
        val Disabled = AutoTrimConfig(keepRecentSteps = 0, minFreeBytes = 0L, contextTokens = 0)
    }
}

/** 一次自动裁剪要处理哪些步骤 */
data class AutoTrimPlan(
    val targets: List<TrimmableStepSize>,
    /** 这批步骤裁剪后可以释放的净体积 */
    val freedBytes: Long,
)

/**
 * 判断这一步工具执行完之后是否该做一次批量自动裁剪。
 *
 * 只在 [HistoryTrimStats] 的未裁剪步骤里取，保留最近 [AutoTrimConfig.keepRecentSteps] 步不动，
 * 更早的步骤一次性全部标记。批量而非每次滑一格，是为了让裁剪后的请求前缀保持稳定，
 * 后续几十步都能继续命中提示词缓存。
 *
 * @param contextTokens 当前上下文占用；多步长任务里来自上一步请求返回的 usage，拿不到时为 null
 * @return 需要裁剪时返回计划，否则返回 null
 */
fun HistoryTrimStats.planAutoTrim(
    config: AutoTrimConfig,
    contextTokens: Int?,
): AutoTrimPlan? {
    if (!config.isEnabled) return null
    if (contextTokens == null || contextTokens < config.contextTokens) return null

    val untrimmed = steps.filter { !it.trimmed }
    if (untrimmed.size <= config.keepRecentSteps) return null

    val targets = untrimmed.dropLast(config.keepRecentSteps)
    val freed = targets.sumOf { it.savedBytes }
    if (freed < config.minFreeBytes) return null
    return AutoTrimPlan(targets = targets, freedBytes = freed)
}
