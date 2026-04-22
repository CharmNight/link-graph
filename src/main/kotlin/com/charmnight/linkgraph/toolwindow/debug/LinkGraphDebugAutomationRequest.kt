package com.charmnight.linkgraph.toolwindow.debug

/**
 * debug-only 工具窗口自动化请求。
 * 默认发布路径不会创建或消费这份配置。
 */
data class LinkGraphDebugAutomationRequest(
    val autoOpenToolWindow: Boolean = false,
    val autoloadGraphMode: String? = null,
    val autoloadMethodSignature: String? = null,
    val autoRequestPlan: Boolean = false,
    val autoRequestCodeDrafts: Boolean = false,
) {
    val hasAnyAction: Boolean
        get() = autoOpenToolWindow ||
            autoloadGraphMode != null ||
            autoloadMethodSignature != null ||
            autoRequestPlan ||
            autoRequestCodeDrafts

    val requiresToolWindowOpen: Boolean
        get() = hasAnyAction

    companion object {
        const val DEBUG_AUTOOPEN_ENV: String = "LINKGRAPH_DEBUG_AUTOOPEN"
        private const val DEBUG_AUTOLOAD_GRAPH_ENV = "LINKGRAPH_DEBUG_AUTOLOAD_GRAPH"
        private const val DEBUG_AUTOLOAD_METHOD_SIGNATURE_ENV = "LINKGRAPH_DEBUG_AUTOLOAD_METHOD_SIGNATURE"
        private const val DEBUG_AUTO_REQUEST_PLAN_ENV = "LINKGRAPH_DEBUG_AUTO_REQUEST_PLAN"
        private const val DEBUG_AUTO_REQUEST_CODE_DRAFTS_ENV = "LINKGRAPH_DEBUG_AUTO_REQUEST_CODE_DRAFTS"

        fun fromEnvironment(): LinkGraphDebugAutomationRequest {
            return LinkGraphDebugAutomationRequest(
                autoOpenToolWindow = debugFlag(DEBUG_AUTOOPEN_ENV),
                autoloadGraphMode = System.getenv(DEBUG_AUTOLOAD_GRAPH_ENV)
                    ?.trim()
                    ?.lowercase()
                    ?.takeIf(String::isNotBlank),
                autoloadMethodSignature = System.getenv(DEBUG_AUTOLOAD_METHOD_SIGNATURE_ENV)
                    ?.trim()
                    ?.takeIf(String::isNotBlank),
                autoRequestPlan = debugFlag(DEBUG_AUTO_REQUEST_PLAN_ENV),
                autoRequestCodeDrafts = debugFlag(DEBUG_AUTO_REQUEST_CODE_DRAFTS_ENV),
            )
        }

        private fun debugFlag(envName: String): Boolean {
            return System.getenv(envName)?.trim()?.equals("true", ignoreCase = true) == true
        }
    }
}
