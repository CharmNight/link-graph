package com.charmnight.linkgraph.toolwindow.debug

import com.charmnight.linkgraph.foundation.LinkGraphDebugEnvironment

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
        private const val DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_BEAUTIFICATION_ENV =
            "LINKGRAPH_DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_BEAUTIFICATION"
        private const val DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_QA_ENV = "LINKGRAPH_DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_QA"
        private const val DEBUG_AUTO_REQUEST_SOURCE_NAVIGATION_ENV = "LINKGRAPH_DEBUG_AUTO_REQUEST_SOURCE_NAVIGATION"
        private const val DEBUG_AUTO_REQUEST_PLAN_ENV = "LINKGRAPH_DEBUG_AUTO_REQUEST_PLAN"
        private const val DEBUG_AUTO_REQUEST_CODE_DRAFTS_ENV = "LINKGRAPH_DEBUG_AUTO_REQUEST_CODE_DRAFTS"

        fun fromEnvironment(): LinkGraphDebugAutomationRequest {
            return LinkGraphDebugAutomationRequest(
                autoOpenToolWindow = debugFlag(DEBUG_AUTOOPEN_ENV),
                autoloadGraphMode = LinkGraphDebugEnvironment.value(DEBUG_AUTOLOAD_GRAPH_ENV)
                    ?.trim()
                    ?.lowercase()
                    ?.takeIf(String::isNotBlank),
                autoloadMethodSignature = LinkGraphDebugEnvironment.value(DEBUG_AUTOLOAD_METHOD_SIGNATURE_ENV)
                    ?.trim()
                    ?.takeIf(String::isNotBlank),
                autoRequestArchitectureGraph = debugFlag(DEBUG_AUTO_REQUEST_ARCHITECTURE_GRAPH_ENV),
                autoRequestArchitectureGraphBeautification =
                    debugFlag(DEBUG_AUTO_REQUEST_ARCHITECTURE_GRAPH_BEAUTIFICATION_ENV),
                autoRequestArchitectureGraphQa = debugFlag(DEBUG_AUTO_REQUEST_ARCHITECTURE_GRAPH_QA_ENV),
                autoRequestClassDiagram = debugFlag(DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_ENV),
                autoRequestClassDiagramBeautification = debugFlag(DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_BEAUTIFICATION_ENV),
                autoRequestClassDiagramQa = debugFlag(DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_QA_ENV),
                autoRequestSourceNavigation = debugFlag(DEBUG_AUTO_REQUEST_SOURCE_NAVIGATION_ENV),
                autoRequestPlan = debugFlag(DEBUG_AUTO_REQUEST_PLAN_ENV),
                autoRequestCodeDrafts = debugFlag(DEBUG_AUTO_REQUEST_CODE_DRAFTS_ENV),
            )
        }

        private fun debugFlag(envName: String): Boolean {
            return LinkGraphDebugEnvironment.isEnabled(envName)
        }
    }
}
