package com.charmnight.linkgraph.jvm.relation

class SpringEndpointRelationResolver : JvmRelationResolver {
    override val id: String = "jvm.spring-endpoint"

    override fun resolve(context: JvmResolutionContext): List<JvmRelation> =
        projectMethods(context.symbolIndex).mapNotNull { methodSymbol ->
            val psiMethod = context.findPsiMethod(methodSymbol) ?: return@mapNotNull null
            val endpoint = HttpEndpointRelationExtractor.controllerEndpoint(psiMethod) ?: return@mapNotNull null
            val controllerClass = context.symbolIndex.classByQualifiedName(methodSymbol.ownerClassName) ?: return@mapNotNull null
            JvmRelation(
                id = jvmRelationId(JvmRelationKind.SPRING_ROUTES_TO, controllerClass.id, HttpEndpointRelationExtractor.endpointResourceSymbol(endpoint).id) +
                    ":${methodSymbol.id}",
                kind = JvmRelationKind.SPRING_ROUTES_TO,
                fromSymbolId = controllerClass.id,
                toSymbolId = HttpEndpointRelationExtractor.endpointResourceSymbol(endpoint).id,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.FRAMEWORK_RULE,
                samples = listOf(psiMethod.evidence("Spring endpoint ${endpoint.method} ${endpoint.path}", methodSymbol.source)),
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
