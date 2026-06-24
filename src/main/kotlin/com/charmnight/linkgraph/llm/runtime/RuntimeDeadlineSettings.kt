package com.charmnight.linkgraph.llm.runtime

import com.charmnight.linkgraph.settings.LinkGraphSettingsState

/**
 * 根据运行时上下文的剩余时间，给设置状态打上"按时限制"覆盖。
 *
 * 如果运行时上下文还剩余 N 秒可用，就把设置中的 timeout 上限裁剪到 N 秒，
 * 避免单次 run 超出整体预算。剩余时间为 null（无限制）时直接返回原设置。
 */
fun LinkGraphSettingsState.withRuntimeDeadlineTimeout(
    runtimeContext: AgentRuntimeContext,
): LinkGraphSettingsState {
    val remainingSeconds = runtimeContext.remainingRuntimeSeconds() ?: return this
    // 主动检查 deadline，让超时立即抛出而非依赖后续判断
    runtimeContext.requireWithinDeadline()
    // 取设置生效超时与剩余时间的较小值；至少保留 1 秒避免立即超时
    return copy(runtimeTimeoutSecondsOverride = effectiveTimeoutSeconds().coerceAtMost(remainingSeconds.coerceAtLeast(1)))
}

/**
 * 把 RunBudget 与设置中的超时结合，生成带"启动时刻"的新预算。
 *
 * @param settings 当前设置状态，提供 effectiveTimeoutSeconds
 * @return 新的 RunBudget，超时取自设置，启动时刻取当前系统时间
 */
fun RunBudget.withConfiguredRuntimeTimeout(
    settings: LinkGraphSettingsState,
): RunBudget {
    return copy(
        maxRuntimeSeconds = settings.effectiveTimeoutSeconds(),
        startedAtEpochMillis = System.currentTimeMillis(),
    )
}
