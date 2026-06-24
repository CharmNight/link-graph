package com.charmnight.linkgraph.toolwindow.debug

import com.charmnight.linkgraph.foundation.LinkGraphDebugEnvironment
import com.charmnight.linkgraph.jvm.index.stableJvmId

/**
 * debug-only 工具窗口自动化请求。
 * 默认发布路径不会创建或消费这份配置。
 *
 * 该数据类只承载"是否要自动触发某些动作"的开关与目标参数，
 * 真正的执行逻辑在 [LinkGraphDebugAutomationCoordinator] 中实现。
 */
data class LinkGraphDebugAutomationRequest(
    /** 是否在启动后自动打开链路图工具窗口。 */
    val autoOpenToolWindow: Boolean = false,
    /** 自动载入的图模式（架构图/类图等），为空表示不自动载图。 */
    val autoloadGraphMode: String? = null,
    /** 自动按方法签名载入真实方法链路图。 */
    val autoloadMethodSignature: String? = null,
    /** 是否自动发起一次架构图请求。 */
    val autoRequestArchitectureGraph: Boolean = false,
    /** 是否自动对架构图触发讲解任务。 */
    val autoRequestArchitectureGraphBeautification: Boolean = false,
    /** 是否自动对架构图触发问答任务。 */
    val autoRequestArchitectureGraphQa: Boolean = false,
    /** 是否自动发起一次类图请求。 */
    val autoRequestClassDiagram: Boolean = false,
    /** 类图请求的目标范围节点 ID，用于聚焦特定包或模块。 */
    val autoRequestClassDiagramScopeNodeId: String? = null,
    /** 类使用处请求的目标节点 ID。 */
    val autoRequestClassUsageTargetNodeId: String? = null,
    /** 类使用处请求的全限定名，便于在没有节点 ID 时按符号查找。 */
    val autoRequestClassUsageTargetQualifiedName: String? = null,
    /** 是否自动对类图触发讲解任务。 */
    val autoRequestClassDiagramBeautification: Boolean = false,
    /** 是否自动对类图触发问答任务。 */
    val autoRequestClassDiagramQa: Boolean = false,
    /** 是否自动触发源码导航。 */
    val autoRequestSourceNavigation: Boolean = false,
    /** 是否自动触发生成实现计划。 */
    val autoRequestPlan: Boolean = false,
    /** 是否自动触发生成代码草稿。 */
    val autoRequestCodeDrafts: Boolean = false,
) {
    /** 当前请求是否包含任一需要执行的动作。 */
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

    /** 当前请求是否需要先打开工具窗口才能执行。 */
    val requiresToolWindowOpen: Boolean
        get() = hasAnyAction

    companion object {
        /** 环境变量名：是否自动打开工具窗口。 */
        const val DEBUG_AUTOOPEN_ENV: String = "LINKGRAPH_DEBUG_AUTOOPEN"
        /** 环境变量名：自动载入的图模式。 */
        private const val DEBUG_AUTOLOAD_GRAPH_ENV = "LINKGRAPH_DEBUG_AUTOLOAD_GRAPH"
        /** 环境变量名：自动载入的方法签名。 */
        private const val DEBUG_AUTOLOAD_METHOD_SIGNATURE_ENV = "LINKGRAPH_DEBUG_AUTOLOAD_METHOD_SIGNATURE"
        /** 环境变量名：是否自动请求架构图。 */
        private const val DEBUG_AUTO_REQUEST_ARCHITECTURE_GRAPH_ENV = "LINKGRAPH_DEBUG_AUTO_REQUEST_ARCHITECTURE_GRAPH"
        /** 环境变量名：是否自动请求架构图讲解。 */
        private const val DEBUG_AUTO_REQUEST_ARCHITECTURE_GRAPH_BEAUTIFICATION_ENV =
            "LINKGRAPH_DEBUG_AUTO_REQUEST_ARCHITECTURE_GRAPH_BEAUTIFICATION"
        /** 环境变量名：是否自动请求架构图问答。 */
        private const val DEBUG_AUTO_REQUEST_ARCHITECTURE_GRAPH_QA_ENV = "LINKGRAPH_DEBUG_AUTO_REQUEST_ARCHITECTURE_GRAPH_QA"
        /** 环境变量名：是否自动请求类图。 */
        private const val DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_ENV = "LINKGRAPH_DEBUG_AUTO_REQUEST_CLASS_DIAGRAM"
        /** 环境变量名：类图请求的范围节点 ID。 */
        const val DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_SCOPE_ENV: String =
            "LINKGRAPH_DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_SCOPE"
        /** 环境变量名：类使用处请求的目标。 */
        const val DEBUG_AUTO_REQUEST_CLASS_USAGE_TARGET_ENV: String =
            "LINKGRAPH_DEBUG_AUTO_REQUEST_CLASS_USAGE_TARGET"
        /** 环境变量名：是否自动请求类图讲解。 */
        private const val DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_BEAUTIFICATION_ENV =
            "LINKGRAPH_DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_BEAUTIFICATION"
        /** 环境变量名：是否自动请求类图问答。 */
        private const val DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_QA_ENV = "LINKGRAPH_DEBUG_AUTO_REQUEST_CLASS_DIAGRAM_QA"
        /** 环境变量名：是否自动请求源码导航。 */
        private const val DEBUG_AUTO_REQUEST_SOURCE_NAVIGATION_ENV = "LINKGRAPH_DEBUG_AUTO_REQUEST_SOURCE_NAVIGATION"
        /** 环境变量名：是否自动请求生成计划。 */
        private const val DEBUG_AUTO_REQUEST_PLAN_ENV = "LINKGRAPH_DEBUG_AUTO_REQUEST_PLAN"
        /** 环境变量名：是否自动请求生成代码草稿。 */
        private const val DEBUG_AUTO_REQUEST_CODE_DRAFTS_ENV = "LINKGRAPH_DEBUG_AUTO_REQUEST_CODE_DRAFTS"

        /**
         * 从环境变量中读取自动化请求配置。
         * 默认读取 [System.getenv]，测试时可以注入自定义映射以便复现。
         */
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
            // 当目标值不是已经规范化的 jvm:class: 前缀形式时，视为原始全限定名。
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
                // 类图请求可以由显式开关触发，也可以通过指定 scope 间接触发。
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

        /**
         * 读取并解析布尔型调试开关。
         */
        private fun debugFlag(
            envName: String,
            environment: Map<String, String>,
        ): Boolean =
            LinkGraphDebugEnvironment.isEnabled(envName, environment)

        /**
         * 把外部传入的类标识规范化为节点可识别的形式。
         * 已经是 `jvm:class:` 前缀时直接保留，否则按稳定 JVM ID 规则生成。
         */
        private fun normalizeClassNodeId(rawValue: String): String? {
            val value = rawValue.trim().takeIf(String::isNotBlank) ?: return null
            if (value.startsWith("jvm:class:")) {
                return value
            }
            return stableJvmId("class", value)
        }
    }
}
