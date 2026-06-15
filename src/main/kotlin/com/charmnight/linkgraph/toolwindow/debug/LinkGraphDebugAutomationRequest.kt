package com.charmnight.linkgraph.toolwindow.debug

import com.charmnight.linkgraph.foundation.LinkGraphDebugEnvironment
import com.charmnight.linkgraph.jvm.index.stableJvmId

/**
 * debug-only 工具窗口自动化请求。
 * 默认发布路径不会创建或消费这份配置。
 */
data class LinkGraphDebugAutomationRequest(
    val autoOpenToolWindow: Boolean = false,
    val autoloadGraphMode: String? = null,
    val autoloadMethodSignature: String? = null,
    val autoRequestArchitectureGraph: Boolean = false,
    val autoRequestArchitectureGraphBeautification: Boolean = false,
    val autoRequestArchitectureGraphQa: Boolean = false,
    val autoRequestClassDiagram: Boolean = false,
    val autoRequestClassDiagramScopeNodeId: String? = null,
    val autoRequestClassUsageTargetNodeId: String? = null,
    val autoRequestClassUsageTargetQualifiedName: String? = null,
    val autoRequestClassDiagramBeautification: Boolean = false,
    val autoRequestClassDiagramQa: Boolean = false,
    val autoRequestSourceNavigation: Boolean = false,
    val autoRequestPlan: Boolean = false,
    val autoRequestCodeDrafts: Boolean = false,
) {
    val hasAnyAction: Boolean
        get() = autoOpenToolWindow ||
            autoloadGraphMode != null ||
            autoloadMethodSignature != null ||
            autoRequestArchitectureGraph ||
            autoRequestArchitectureGraphBeautification ||
            autoRequestArchitectureGraphQa ||
            autoRequestClassDiagram ||
            autoRequestClassDiagramScopeNodeId != null ||
            autoRequestClassUsageTargetNodeId != null ||
            autoRequestClassUsageTargetQualifiedName != null ||
            autoRequestClassDiagramBeautification ||
            autoRequestClassDiagramQa ||
            autoRequestSourceNavigation ||
            autoRequestPlan ||
            autoRequestCodeDrafts

    val requiresToolWindowOpen: Boolean
        get() = hasAnyAction

    companion object {
        const val DEBUG_AUTOOPEN_ENV: String = "LINKGRAPH_DEBUG_AUTOOPEN"
        private const val DEBUG_AUTOLOAD_GRAPH_ENV = "LINKGRAPH_DEBUG_AUTOLOAD_GRAPH"
        private const val DEBUG_AUTOLOAD_METHOD_SIGNATURE_ENV = "LINKGRAPH_DEBUG_AUTOLOAD_METHOD_SIGNATURE"
        private const val DEBUG_AUTO_REQUEST_ARCHITECTURE_GRAPH_ENV = "LINKGRAPH_DEBUG_AUTO_REQUEST_ARCHITECTURE_GRAPH"
        private const val DEBUG_AUTO_REQUEST_ARCHITECTURE_GRAPH_BEAUTIFICATION_ENV =
            "LINKGRAPH_DEBUG_AUTO_REQUEST_ARCHITECTURE_GRAPH_BEAUTIFICATION"
        private const val DEBUG_AUTO_REQUEST_ARCHITECTURE_GRAPH_QA_ENV = "LINKGRAPH_DEBUG_AUTO_REQUEST_ARCHITECTURE_GRAPH_QA"
        private const val DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_ENV = "LINKGRAPH_DEBUG_AUTO_REQUEST_CLASS_DIAGRAM"
        const val DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_SCOPE_ENV: String =
            "LINKGRAPH_DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_SCOPE"
        const val DEBUG_AUTO_REQUEST_CLASS_USAGE_TARGET_ENV: String =
            "LINKGRAPH_DEBUG_AUTO_REQUEST_CLASS_USAGE_TARGET"
        private const val DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_BEAUTIFICATION_ENV =
            "LINKGRAPH_DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_BEAUTIFICATION"
        private const val DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_QA_ENV = "LINKGRAPH_DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_QA"
        private const val DEBUG_AUTO_REQUEST_SOURCE_NAVIGATION_ENV = "LINKGRAPH_DEBUG_AUTO_REQUEST_SOURCE_NAVIGATION"
        private const val DEBUG_AUTO_REQUEST_PLAN_ENV = "LINKGRAPH_DEBUG_AUTO_REQUEST_PLAN"
        private const val DEBUG_AUTO_REQUEST_CODE_DRAFTS_ENV = "LINKGRAPH_DEBUG_AUTO_REQUEST_CODE_DRAFTS"

        fun fromEnvironment(
            environment: Map<String, String> = System.getenv(),
        ): LinkGraphDebugAutomationRequest {
            val classDiagramScopeNodeId = LinkGraphDebugEnvironment.value(
                DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_SCOPE_ENV,
                environment,
            )?.let(::normalizeClassNodeId)
            val classUsageTargetValue = LinkGraphDebugEnvironment.value(
                DEBUG_AUTO_REQUEST_CLASS_USAGE_TARGET_ENV,
                environment,
            )
            val classUsageTargetNodeId = classUsageTargetValue?.let(::normalizeClassNodeId)
            val classUsageTargetQualifiedName = classUsageTargetValue
                ?.trim()
                ?.takeIf { value -> value.isNotBlank() && !value.startsWith("jvm:class:") }
            return LinkGraphDebugAutomationRequest(
                autoOpenToolWindow = debugFlag(DEBUG_AUTOOPEN_ENV, environment),
                autoloadGraphMode = LinkGraphDebugEnvironment.value(DEBUG_AUTOLOAD_GRAPH_ENV, environment)
                    ?.trim()
                    ?.lowercase()
                    ?.takeIf(String::isNotBlank),
                autoloadMethodSignature = LinkGraphDebugEnvironment.value(DEBUG_AUTOLOAD_METHOD_SIGNATURE_ENV, environment)
                    ?.trim()
                    ?.takeIf(String::isNotBlank),
                autoRequestArchitectureGraph = debugFlag(DEBUG_AUTO_REQUEST_ARCHITECTURE_GRAPH_ENV, environment),
                autoRequestArchitectureGraphBeautification =
                    debugFlag(DEBUG_AUTO_REQUEST_ARCHITECTURE_GRAPH_BEAUTIFICATION_ENV, environment),
                autoRequestArchitectureGraphQa = debugFlag(DEBUG_AUTO_REQUEST_ARCHITECTURE_GRAPH_QA_ENV, environment),
                autoRequestClassDiagram = debugFlag(DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_ENV, environment) ||
                    classDiagramScopeNodeId != null,
                autoRequestClassDiagramScopeNodeId = classDiagramScopeNodeId,
                autoRequestClassUsageTargetNodeId = classUsageTargetNodeId,
                autoRequestClassUsageTargetQualifiedName = classUsageTargetQualifiedName,
                autoRequestClassDiagramBeautification =
                    debugFlag(DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_BEAUTIFICATION_ENV, environment),
                autoRequestClassDiagramQa = debugFlag(DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_QA_ENV, environment),
                autoRequestSourceNavigation = debugFlag(DEBUG_AUTO_REQUEST_SOURCE_NAVIGATION_ENV, environment),
                autoRequestPlan = debugFlag(DEBUG_AUTO_REQUEST_PLAN_ENV, environment),
                autoRequestCodeDrafts = debugFlag(DEBUG_AUTO_REQUEST_CODE_DRAFTS_ENV, environment),
            )
        }

        private fun debugFlag(
            envName: String,
            environment: Map<String, String>,
        ): Boolean =
            LinkGraphDebugEnvironment.isEnabled(envName, environment)

        private fun normalizeClassNodeId(rawValue: String): String? {
            val value = rawValue.trim().takeIf(String::isNotBlank) ?: return null
            if (value.startsWith("jvm:class:")) {
                return value
            }
            return stableJvmId("class", value)
        }
    }
}
