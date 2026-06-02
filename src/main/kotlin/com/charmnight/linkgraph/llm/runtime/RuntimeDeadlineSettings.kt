package com.charmnight.linkgraph.llm.runtime

import com.charmnight.linkgraph.settings.LinkGraphSettingsState

fun LinkGraphSettingsState.withRuntimeDeadlineTimeout(
    runtimeContext: AgentRuntimeContext,
): LinkGraphSettingsState {
    val remainingSeconds = runtimeContext.remainingRuntimeSeconds() ?: return this
    runtimeContext.requireWithinDeadline()
    return copy(runtimeTimeoutSecondsOverride = effectiveTimeoutSeconds().coerceAtMost(remainingSeconds.coerceAtLeast(1)))
}

fun RunBudget.withConfiguredRuntimeTimeout(
    settings: LinkGraphSettingsState,
): RunBudget {
    return copy(
        maxRuntimeSeconds = settings.effectiveTimeoutSeconds(),
        startedAtEpochMillis = System.currentTimeMillis(),
    )
}
