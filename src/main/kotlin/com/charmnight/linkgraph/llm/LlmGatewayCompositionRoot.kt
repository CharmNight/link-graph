package com.charmnight.linkgraph.llm

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/**
 * P3-1 LLM Gateway 组合根：集中构造项目级共享的 [LlmGateway] 实例。
 *
 * 设计：
 * - 内置 3 个 gateway（OpenAI Chat / OpenAI Responses / Anthropic Messages）通过 [RoutingLlmGateway]
 *   路由，已经直接调用 [LlmGatewayClient]（SSRF / size guard 已生效）。
 * - 第三方通过 plugin.xml 注册 [LlmGatewayContributor] EP，由本类用 [LlmGatewayContributorBackedGateway]
 *   包装为 [LlmGateway]，包装类强制经过 [LlmGatewayClient]，第三方实现无法绕过安全策略。
 *
 * 工厂方法 [createGateway] 集中此装配逻辑，避免 7 个 service 各自 new RoutingLlmGateway() 的散布。
 *
 * 接口稳定性：本类属内部 API，[LlmGateway] 接口本身冻结为公共 API。
 */
object LlmGatewayCompositionRoot {
    /** EP 名称：第三方 plugin.xml 用此注册自定义 contributor。 */
    val EP_NAME: ExtensionPointName<LlmGatewayContributor> =
        ExtensionPointName.create("com.charmnight.linkgraph.llmGatewayContributor")

    /**
     * 构造项目级共享 gateway。
     *
     * 1. 先用内置 [RoutingLlmGateway]（路由到 3 个内置 gateway）
     * 2. 遍历 EP 注册的 contributor，把每个 contributor 包装为 [LlmGatewayContributorBackedGateway]
     * 3. 返回的 [LlmGateway] 实际由路由表分发：内置协议走内置 gateway，自定义协议走 contributor 包装
     *
     * 第三方 contributor 的 supportedProtocol 必须与 LlmRequest.protocol.name 一致才能被路由到。
     */
    fun createGateway(project: Project): LlmGateway {
        // 内置 3 个 gateway 已经直接使用 LlmGatewayClient，安全 guard 自动生效。
        val routingGateway = RoutingLlmGateway()

        // 收集第三方 contributor（动态 EP，支持热加载）
        val contributors = EP_NAME.extensionList
        if (contributors.isEmpty()) {
            return routingGateway
        }

        // 把 contributor 包装为 LlmGateway，注册到路由表。
        // 包装类强制经过 LlmGatewayClient，第三方实现无法绕过 SSRF / size guard。
        val contributorGatewaysByProtocol = contributors.associateBy(
            keySelector = { it.supportedProtocol },
            valueTransform = { contributor -> LlmGatewayContributorBackedGateway(contributor) },
        )

        return ContributorAwareLlmGateway(
            builtinGateway = routingGateway,
            contributorGatewaysByProtocol = contributorGatewaysByProtocol,
        )
    }
}

/**
 * 把内置 [RoutingLlmGateway] 与 contributor 路由结合的 gateway。
 *
 * - 请求的协议在 contributor 表里 → 走 contributor 包装（强制经 client）
 * - 否则 → 走内置 routingGateway（已经直接用 client）
 */
private class ContributorAwareLlmGateway(
    private val builtinGateway: LlmGateway,
    private val contributorGatewaysByProtocol: Map<String, LlmGateway>,
) : LlmGateway {
    override fun generate(request: LlmRequest): LlmResponse {
        val target = contributorGatewaysByProtocol[request.protocol.name] ?: builtinGateway
        return target.generate(request)
    }

    override fun stream(
        request: LlmRequest,
        listener: (LlmStreamEvent) -> Unit,
    ): LlmResponse {
        val target = contributorGatewaysByProtocol[request.protocol.name] ?: builtinGateway
        return target.stream(request, listener)
    }
}
