package com.charmnight.linkgraph.jvm.relation

/**
 * 解析 Spring Controller 上的 HTTP 端点关系。
 *
 * 扫描 JVM 符号索引中所有方法，对每个方法检查是否带有 Spring MVC 注解
 * （@RequestMapping / @GetMapping 等），若有则建立"Controller 类 → HTTP 端点资源"的关系。
 * 这种关系让架构图能展示出"哪个类对外提供了哪些 HTTP 接口"。
 */
class SpringEndpointRelationResolver : JvmRelationResolver {
    /** 解析器唯一标识。 */
    override val id: String = "jvm.spring-endpoint"

    /**
     * 在 JVM 解析上下文中提取 Spring 端点关系。
     *
     * @param context 提供 PSI 访问与符号索引
     * @return 提取到的关系列表；非 Spring 项目返回空列表
     */
    override fun resolve(context: JvmResolutionContext): List<JvmRelation> =
        // 遍历索引中所有方法，挑出 Spring Controller 的处理方法
        projectMethods(context.symbolIndex).mapNotNull { methodSymbol ->
            // 找不到 PSI 或非 Controller 方法的直接跳过
            val psiMethod = context.findPsiMethod(methodSymbol) ?: return@mapNotNull null
            val endpoint = HttpEndpointRelationExtractor.controllerEndpoint(psiMethod) ?: return@mapNotNull null
            // Controller 类必须在索引中存在，否则关系端点不完整
            val controllerClass = context.symbolIndex.classByQualifiedName(methodSymbol.ownerClassName) ?: return@mapNotNull null
            JvmRelation(
                // 关系 ID 包含方法 ID 后缀，避免不同方法指向同一端点时撞 ID
                id = jvmRelationId(JvmRelationKind.SPRING_ROUTES_TO, controllerClass.id, HttpEndpointRelationExtractor.endpointResourceSymbol(endpoint).id) +
                    ":${methodSymbol.id}",
                kind = JvmRelationKind.SPRING_ROUTES_TO,
                fromSymbolId = controllerClass.id,
                toSymbolId = HttpEndpointRelationExtractor.endpointResourceSymbol(endpoint).id,
                // 注解直接证明 → 最高置信度
                confidence = JvmRelationConfidence.PROVEN,
                // 关系来源是框架规则（而非显式代码或运行时）
                source = JvmRelationSource.FRAMEWORK_RULE,
                // 保留 PSI 证据片段，便于事后核查
                samples = listOf(psiMethod.evidence("Spring endpoint ${endpoint.method} ${endpoint.path}", methodSymbol.source)),
                // 元数据记录框架、HTTP、Spring 相关细节，UI 与下游消费方按需读取
                metadata = mapOf(
                    "framework" to "spring-web",
                    "http.method" to endpoint.method,
                    "http.path" to endpoint.path,
                    "spring.controllerClass" to controllerClass.qualifiedName,
                    "spring.handlerMethod" to methodSymbol.signature,
                    "spring.annotationEvidence" to "ANNOTATION_LITERAL",
                ),
            )
        }
}
