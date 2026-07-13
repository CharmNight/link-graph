package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.agent.model.GenerationContext
import com.charmnight.linkgraph.agent.model.GraphBeautificationContext
import com.charmnight.linkgraph.agent.model.GraphDiffContext
import com.charmnight.linkgraph.agent.model.GraphQaContext
import com.charmnight.linkgraph.agent.model.SourceSnippetContext
import com.charmnight.linkgraph.settings.LinkGraphSettingsState

internal const val REMOTE_SOURCE_CONTEXT_BLOCKED_WARNING: String =
    "远程 LLM 源码片段外发未授权，本次未发送源码片段。"

internal data class RemoteSourceContextPolicyResult<T>(
    val context: T,
    val warning: String? = null,
) {
    fun warnings(): List<String> = listOfNotNull(warning)
}

internal fun GenerationContext.withRemoteSourceContextPolicy(
    settings: LinkGraphSettingsState,
): RemoteSourceContextPolicyResult<GenerationContext> {
    return if (shouldStripRemoteSourceContext(settings, sourceContext)) {
        RemoteSourceContextPolicyResult(
            context = copy(sourceContext = emptyList()),
            warning = REMOTE_SOURCE_CONTEXT_BLOCKED_WARNING,
        )
    } else {
        RemoteSourceContextPolicyResult(context = this)
    }
}

internal fun GraphQaContext.withRemoteSourceContextPolicy(
    settings: LinkGraphSettingsState,
): RemoteSourceContextPolicyResult<GraphQaContext> {
    return if (shouldStripRemoteSourceContext(settings, sourceContext)) {
        RemoteSourceContextPolicyResult(
            context = copy(sourceContext = emptyList()),
            warning = REMOTE_SOURCE_CONTEXT_BLOCKED_WARNING,
        )
    } else {
        RemoteSourceContextPolicyResult(context = this)
    }
}

internal fun GraphDiffContext.withRemoteSourceContextPolicy(
    settings: LinkGraphSettingsState,
): RemoteSourceContextPolicyResult<GraphDiffContext> {
    return if (shouldStripRemoteSourceContext(settings, sourceContext)) {
        RemoteSourceContextPolicyResult(
            context = copy(sourceContext = emptyList()),
            warning = REMOTE_SOURCE_CONTEXT_BLOCKED_WARNING,
        )
    } else {
        RemoteSourceContextPolicyResult(context = this)
    }
}

internal fun GraphBeautificationContext.withRemoteSourceContextPolicy(
    settings: LinkGraphSettingsState,
): RemoteSourceContextPolicyResult<GraphBeautificationContext> {
    return if (shouldStripRemoteSourceContext(settings, sourceContext, stepSourceContext)) {
        RemoteSourceContextPolicyResult(
            context = copy(
                sourceContext = emptyList(),
                stepSourceContext = emptyList(),
            ),
            warning = REMOTE_SOURCE_CONTEXT_BLOCKED_WARNING,
        )
    } else {
        RemoteSourceContextPolicyResult(context = this)
    }
}

private fun shouldStripRemoteSourceContext(
    settings: LinkGraphSettingsState,
    vararg sourceContexts: List<SourceSnippetContext>,
): Boolean {
    val sanitized = settings.sanitized()
    return sanitized.usesRemoteProvider() &&
        !sanitized.allowRemoteSourceContext &&
        sourceContexts.any { sourceContext -> sourceContext.isNotEmpty() }
}
