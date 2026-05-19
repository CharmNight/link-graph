package com.charmnight.linkgraph.jvm.relation

import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmResourceKind
import com.charmnight.linkgraph.jvm.index.JvmResourceSymbol
import com.charmnight.linkgraph.jvm.index.stableJvmId
import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiArrayInitializerMemberValue

class FrameworkRelationResolver : JvmRelationResolver {
    override val id: String = "jvm.framework"

    override fun resolve(context: JvmResolutionContext): List<JvmRelation> {
        return dubboRelations(context) + feignRelations(context) + mqRelations(context)
    }

    private fun dubboRelations(context: JvmResolutionContext): List<JvmRelation> {
        val relations = mutableListOf<JvmRelation>()
        projectClasses(context.symbolIndex).forEach { classSymbol ->
            val psiClass = context.findPsiClass(classSymbol) ?: return@forEach
            psiClass.annotations.forEach { annotation ->
                if (annotation.isDubboServiceAnnotation()) {
                    dubboServiceInterfaces(psiClass, context).forEach { serviceInterface ->
                        relations += relation(
                            kind = JvmRelationKind.DUBBO_PROVIDES,
                            from = classSymbol,
                            to = serviceInterface,
                            confidence = JvmRelationConfidence.PROVEN,
                            source = JvmRelationSource.FRAMEWORK_RULE,
                            evidence = annotation.evidence("Dubbo service provides ${serviceInterface.qualifiedName}", classSymbol.source),
                            qualifier = "${classSymbol.qualifiedName}:${serviceInterface.qualifiedName}",
                            metadata = mapOf(
                                "framework" to "dubbo",
                                "dubbo.annotation" to annotation.qualifiedName.orEmpty(),
                                "dubbo.serviceInterface" to serviceInterface.qualifiedName,
                            ),
                        )
                    }
                }
            }
            psiClass.fields.forEach { field ->
                field.annotations
                    .filter { annotation -> annotation.isDubboReferenceAnnotation() }
                    .forEach { annotation ->
                        val target = context.symbolIndex.classByTypeNear(field.type, classSymbol.packageName) ?: return@forEach
                        relations += relation(
                            kind = JvmRelationKind.DUBBO_REFERENCES,
                            from = classSymbol,
                            to = target,
                            confidence = JvmRelationConfidence.RULE_INFERRED,
                            source = JvmRelationSource.FRAMEWORK_RULE,
                            evidence = annotation.evidence("Dubbo reference to ${target.qualifiedName}", classSymbol.source),
                            qualifier = "${classSymbol.qualifiedName}:${field.name}:${target.qualifiedName}",
                            metadata = mapOf(
                                "framework" to "dubbo",
                                "dubbo.annotation" to annotation.qualifiedName.orEmpty(),
                                "dubbo.referenceField" to field.name,
                            ),
                        )
                    }
            }
        }
        return relations
    }

    private fun dubboServiceInterfaces(
        psiClass: PsiClass,
        context: JvmResolutionContext,
    ): List<JvmClassSymbol> {
        val direct = psiClass.interfaces.mapNotNull { psiInterface ->
            context.symbolIndex.classByQualifiedName(psiInterface.qualifiedName)
        } + psiClass.implementsListTypes.mapNotNull { type ->
            context.symbolIndex.classByQualifiedName(com.charmnight.linkgraph.jvm.index.canonicalTypeText(type))
        } + psiClass.implementsList?.referenceElements.orEmpty().mapNotNull { reference ->
            val name = (reference.resolve() as? PsiClass)?.qualifiedName
                ?: reference.qualifiedName?.takeIf { value -> value.contains('.') }
                ?: reference.referenceName?.let { simple ->
                    psiClass.qualifiedName?.substringBeforeLast('.', missingDelimiterValue = "")
                        ?.takeIf(String::isNotBlank)
                        ?.let { packageName -> "$packageName.$simple" }
                }
            context.symbolIndex.classByQualifiedName(name)
        }
        if (direct.isNotEmpty()) {
            return direct
        }
        return psiClass.annotations
            .filter { annotation -> annotation.isDubboServiceAnnotation() }
            .flatMap { annotation ->
                listOfNotNull(annotation.annotationStringValue("interfaceName")) +
                    annotation.classValues("interfaceClass") +
                    annotation.classValues("value")
            }
            .mapNotNull(context.symbolIndex::classByQualifiedName)
    }

    private fun feignRelations(context: JvmResolutionContext): List<JvmRelation> {
        val feignClients = projectClasses(context.symbolIndex)
            .mapNotNull { classSymbol ->
                val psiClass = context.findPsiClass(classSymbol) ?: return@mapNotNull null
                val annotation = psiClass.annotations.firstOrNull { annotation -> annotation.isFeignClientAnnotation() }
                    ?: return@mapNotNull null
                val classPath = annotation.annotationStringValue("path")
                    ?: annotation.annotationStringValue("value")
                classSymbol to FeignClientInfo(classPath = classPath)
            }
            .toMap()
        if (feignClients.isEmpty()) {
            return emptyList()
        }
        val endpoints = httpEndpoints(context)
        val relations = mutableListOf<JvmRelation>()
        projectMethods(context.symbolIndex).forEach { methodSymbol ->
            val psiMethod = context.findPsiMethod(methodSymbol) ?: return@forEach
            val callerClass = context.symbolIndex.classByQualifiedName(methodSymbol.ownerClassName) ?: return@forEach
            psiMethod.accept(
                object : JavaRecursiveElementVisitor() {
                    override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                        val targetMethod = expression.resolveMethod()
                        val targetClass = targetMethod?.containingClass
                        val feignClientSymbol = targetClass?.qualifiedName
                            ?.let(context.symbolIndex::classByQualifiedName)
                        val feignInfo = feignClientSymbol?.let(feignClients::get)
                        if (targetMethod != null && feignClientSymbol != null && feignInfo != null) {
                            val endpoint = feignEndpoint(feignInfo.classPath, targetMethod)
                            relations += relation(
                                kind = JvmRelationKind.FEIGN_CLIENT_CALLS,
                                from = callerClass,
                                to = feignClientSymbol,
                                confidence = JvmRelationConfidence.PROVEN,
                                source = JvmRelationSource.FRAMEWORK_RULE,
                                evidence = expression.evidence(
                                    "Feign call to ${feignClientSymbol.qualifiedName}.${targetMethod.name}",
                                    methodSymbol.source,
                                ),
                                qualifier = "${methodSymbol.signature}:${targetMethod.name}:${expression.textRange.startOffset}",
                                metadata = buildMap {
                                    put("framework", "feign")
                                    put("feign.clientClass", feignClientSymbol.qualifiedName)
                                    put("feign.clientMethod", targetMethod.name)
                                    put("feign.sourceMethod", methodSymbol.signature)
                                    endpoint?.let { value ->
                                        put("http.method", value.method)
                                        put("http.path", value.path)
                                    }
                                },
                            )
                            endpoint?.let { httpEndpoint ->
                                val endpointSymbol = endpointResourceSymbol(httpEndpoint)
                                relations += relation(
                                    kind = JvmRelationKind.FEIGN_ROUTES_TO,
                                    from = feignClientSymbol,
                                    to = endpointSymbol,
                                    confidence = JvmRelationConfidence.RULE_INFERRED,
                                    source = JvmRelationSource.FRAMEWORK_RULE,
                                    evidence = targetMethod.evidence("Feign route ${httpEndpoint.method} ${httpEndpoint.path}", feignClientSymbol.source),
                                    qualifier = "${feignClientSymbol.qualifiedName}:${targetMethod.name}:${httpEndpoint.method}:${httpEndpoint.path}",
                                    metadata = mapOf(
                                        "framework" to "feign",
                                        "feign.clientClass" to feignClientSymbol.qualifiedName,
                                        "feign.clientMethod" to targetMethod.name,
                                        "http.method" to httpEndpoint.method,
                                        "http.path" to httpEndpoint.path,
                                    ),
                                )
                                endpoints[httpEndpoint].orEmpty().forEach { providerMethod ->
                                    val providerClass = context.symbolIndex.classByQualifiedName(providerMethod.ownerClassName)
                                        ?: return@forEach
                                    relations += relation(
                                        kind = JvmRelationKind.FEIGN_ROUTES_TO,
                                        from = feignClientSymbol,
                                        to = providerClass,
                                        confidence = JvmRelationConfidence.RULE_INFERRED,
                                        source = JvmRelationSource.FRAMEWORK_RULE,
                                        evidence = targetMethod.evidence(
                                            "Feign route ${httpEndpoint.method} ${httpEndpoint.path} maps to ${providerMethod.signature}",
                                            feignClientSymbol.source,
                                        ),
                                        qualifier = "${feignClientSymbol.qualifiedName}:${targetMethod.name}:${providerMethod.signature}",
                                        metadata = mapOf(
                                            "framework" to "feign",
                                            "feign.clientClass" to feignClientSymbol.qualifiedName,
                                            "feign.clientMethod" to targetMethod.name,
                                            "http.method" to httpEndpoint.method,
                                            "http.path" to httpEndpoint.path,
                                            "http.providerMethod" to providerMethod.signature,
                                        ),
                                    )
                                }
                            }
                        }
                        super.visitMethodCallExpression(expression)
                    }
                },
            )
        }
        return relations
    }

    private fun httpEndpoints(context: JvmResolutionContext): Map<HttpEndpoint, List<com.charmnight.linkgraph.jvm.index.JvmMethodSymbol>> {
        return projectMethods(context.symbolIndex)
            .mapNotNull { methodSymbol ->
                val psiMethod = context.findPsiMethod(methodSymbol) ?: return@mapNotNull null
                val endpoint = controllerEndpoint(psiMethod) ?: return@mapNotNull null
                endpoint to methodSymbol
            }
            .groupBy({ it.first }, { it.second })
    }

    private fun controllerEndpoint(method: com.intellij.psi.PsiMethod): HttpEndpoint? {
        val owner = method.containingClass ?: return null
        if (!owner.annotations.any { annotation -> annotation.simpleName() in CONTROLLER_ANNOTATIONS }) {
            return null
        }
        val httpMethod = requestMethod(method) ?: return null
        val classMapping = owner.annotations.firstOrNull { annotation -> annotation.simpleName() == "RequestMapping" }
        val path = combinePaths(
            classMapping?.annotationStringValue("value") ?: classMapping?.annotationStringValue("path"),
            requestPath(method),
        ) ?: return null
        return HttpEndpoint(httpMethod, path)
    }

    private fun feignEndpoint(
        classPath: String?,
        method: com.intellij.psi.PsiMethod,
    ): HttpEndpoint? {
        val httpMethod = requestMethod(method) ?: return null
        val path = combinePaths(classPath, requestPath(method)) ?: return null
        return HttpEndpoint(httpMethod, path)
    }

    private fun requestMethod(method: com.intellij.psi.PsiMethod): String? {
        method.annotations.forEach { annotation ->
            when (annotation.simpleName()) {
                "GetMapping" -> return "GET"
                "PostMapping" -> return "POST"
                "PutMapping" -> return "PUT"
                "DeleteMapping" -> return "DELETE"
                "PatchMapping" -> return "PATCH"
                "RequestMapping" -> {
                    val methodValue = annotation.findDeclaredAttributeValue("method")?.text.orEmpty().uppercase()
                    HTTP_METHODS.firstOrNull { value -> methodValue.contains(value) }?.let { return it }
                }
            }
        }
        return null
    }

    private fun requestPath(method: com.intellij.psi.PsiMethod): String? {
        return method.annotations
            .firstOrNull { annotation -> annotation.simpleName() in REQUEST_MAPPING_ANNOTATIONS }
            ?.let { annotation -> annotation.annotationStringValue("value") ?: annotation.annotationStringValue("path") }
    }

    private fun combinePaths(
        classPath: String?,
        methodPath: String?,
    ): String? {
        val methodPart = methodPath ?: return null
        val parts = listOfNotNull(classPath, methodPart)
            .map { part -> part.trim().trim('/') }
            .filter(String::isNotBlank)
        return "/" + parts.joinToString("/")
    }

    private fun endpointResourceSymbol(endpoint: HttpEndpoint): JvmResourceSymbol =
        JvmResourceSymbol(
            id = stableJvmId("resource", "http:${endpoint.method} ${endpoint.path}"),
            path = "http:${endpoint.method} ${endpoint.path}",
            kind = JvmResourceKind.OTHER,
            source = null,
            origin = com.charmnight.linkgraph.source.SourceOrigin.PROJECT_SOURCE,
        )

    private fun mqRelations(context: JvmResolutionContext): List<JvmRelation> {
        val consumers = mutableListOf<JvmRelation>()
        val listenersByDestination = linkedMapOf<String, MutableList<Pair<com.charmnight.linkgraph.jvm.index.JvmMethodSymbol, PsiAnnotation>>>()
        projectMethods(context.symbolIndex).forEach { methodSymbol ->
            val psiMethod = context.findPsiMethod(methodSymbol) ?: return@forEach
            psiMethod.annotations.forEach { annotation ->
                val destination = mqDestination(annotation) ?: return@forEach
                listenersByDestination.getOrPut(destination) { mutableListOf() } += methodSymbol to annotation
                val ownerClass = context.symbolIndex.classByQualifiedName(methodSymbol.ownerClassName) ?: return@forEach
                val topic = mqTopicSymbol(context, destination)
                if (topic != null) {
                    consumers += relation(
                        kind = JvmRelationKind.MQ_CONSUMES,
                        from = ownerClass,
                        to = topic,
                        confidence = JvmRelationConfidence.RULE_INFERRED,
                        source = JvmRelationSource.FRAMEWORK_RULE,
                        evidence = annotation.evidence("MQ listener consumes $destination", methodSymbol.source),
                        qualifier = "${methodSymbol.signature}:$destination",
                        metadata = mapOf(
                            "framework" to "mq",
                            "mq.destination" to destination,
                            "mq.listenerMethod" to methodSymbol.signature,
                            "mq.annotation" to annotation.qualifiedName.orEmpty(),
                        ),
                    )
                }
            }
        }
        val publishers = mutableListOf<JvmRelation>()
        projectMethods(context.symbolIndex).forEach { methodSymbol ->
            val psiMethod = context.findPsiMethod(methodSymbol) ?: return@forEach
            val ownerClass = context.symbolIndex.classByQualifiedName(methodSymbol.ownerClassName) ?: return@forEach
            psiMethod.accept(
                object : JavaRecursiveElementVisitor() {
                    override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                        if (isMqPublishCall(expression)) {
                            mqDestinationArgument(expression)?.let { destination ->
                                val topic = mqTopicSymbol(context, destination)
                                if (topic != null) {
                                    publishers += relation(
                                        kind = JvmRelationKind.MQ_PUBLISHES,
                                        from = ownerClass,
                                        to = topic,
                                        confidence = JvmRelationConfidence.RULE_INFERRED,
                                        source = JvmRelationSource.FRAMEWORK_RULE,
                                        evidence = expression.evidence("MQ publish to $destination", methodSymbol.source),
                                        qualifier = "${methodSymbol.signature}:$destination:${expression.textRange.startOffset}",
                                        metadata = mapOf(
                                            "framework" to "mq",
                                            "mq.destination" to destination,
                                            "mq.publisherMethod" to methodSymbol.signature,
                                        ),
                                    )
                                }
                                listenersByDestination[destination].orEmpty().forEach { (listenerMethod, annotation) ->
                                    val listenerClass = context.symbolIndex.classByQualifiedName(listenerMethod.ownerClassName)
                                        ?: return@forEach
                                    publishers += relation(
                                        kind = JvmRelationKind.MQ_PUBLISHES,
                                        from = ownerClass,
                                        to = listenerClass,
                                        confidence = JvmRelationConfidence.RULE_INFERRED,
                                        source = JvmRelationSource.FRAMEWORK_RULE,
                                        evidence = expression.evidence("MQ publish to listener ${listenerMethod.signature}", methodSymbol.source),
                                        qualifier = "${methodSymbol.signature}:${listenerMethod.signature}:$destination",
                                        metadata = mapOf(
                                            "framework" to "mq",
                                            "mq.destination" to destination,
                                            "mq.publisherMethod" to methodSymbol.signature,
                                            "mq.listenerMethod" to listenerMethod.signature,
                                            "mq.listenerAnnotation" to annotation.qualifiedName.orEmpty(),
                                        ),
                                    )
                                }
                            }
                        }
                        super.visitMethodCallExpression(expression)
                    }
                },
            )
        }
        return consumers + publishers
    }

    private fun mqDestination(annotation: PsiAnnotation): String? {
        if (annotation.simpleName() !in MQ_LISTENER_ANNOTATIONS) {
            return null
        }
        return listOf("topics", "topic", "value", "queues", "queue", "destination")
            .firstNotNullOfOrNull { name -> annotation.annotationStringValue(name) }
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }

    private fun isMqPublishCall(expression: PsiMethodCallExpression): Boolean {
        val name = expression.methodExpression.referenceName ?: return false
        if (name !in MQ_PUBLISH_METHODS) {
            return false
        }
        val qualifierType = expression.methodExpression.qualifierExpression?.type?.canonicalText.orEmpty()
        return qualifierType.contains("KafkaTemplate") ||
            qualifierType.contains("RabbitTemplate") ||
            qualifierType.contains("JmsTemplate") ||
            qualifierType.contains("RocketMQTemplate") ||
            qualifierType.contains("StreamBridge") ||
            qualifierType.contains("MessageChannel")
    }

    private fun mqDestinationArgument(expression: PsiMethodCallExpression): String? =
        expression.argumentList.expressions.firstOrNull()?.staticString()
            ?.trim()
            ?.takeIf(String::isNotBlank)

    private fun mqTopicSymbol(
        context: JvmResolutionContext,
        destination: String,
    ): com.charmnight.linkgraph.jvm.index.JvmResourceSymbol? =
        context.symbolIndex.resourcesByPath[destination]
            ?: context.symbolIndex.resourcesByPath["mq:$destination"]

    private fun PsiAnnotation.simpleName(): String? =
        qualifiedName?.substringAfterLast('.') ?: nameReferenceElement?.referenceName

    private fun PsiAnnotation.isDubboServiceAnnotation(): Boolean {
        val qualified = qualifiedName.orEmpty()
        val simple = simpleName()
        return simple == "DubboService" ||
            qualified in setOf(
                "org.apache.dubbo.config.annotation.Service",
                "com.alibaba.dubbo.config.annotation.Service",
            )
    }

    private fun PsiAnnotation.isDubboReferenceAnnotation(): Boolean {
        val qualified = qualifiedName.orEmpty()
        val simple = simpleName()
        return simple == "DubboReference" ||
            qualified in setOf(
                "org.apache.dubbo.config.annotation.Reference",
                "com.alibaba.dubbo.config.annotation.Reference",
            )
    }

    private fun PsiAnnotation.isFeignClientAnnotation(): Boolean {
        val qualified = qualifiedName.orEmpty()
        return simpleName() == "FeignClient" ||
            qualified == "org.springframework.cloud.openfeign.FeignClient" ||
            qualified == "org.springframework.cloud.netflix.feign.FeignClient"
    }

    private fun PsiAnnotation.annotationStringValue(name: String): String? =
        findDeclaredAttributeValue(name)?.annotationString()
            ?: if (name == "value") {
                findDeclaredAttributeValue(null)?.annotationString()
            } else {
                null
            }

    private fun PsiAnnotation.classValues(name: String): List<String> =
        findDeclaredAttributeValue(name)?.text
            ?.split(',', '{', '}')
            ?.map { value -> value.trim().removeSuffix(".class") }
            ?.filter { value -> value != "void" && value.contains('.') }
            .orEmpty()

    private fun PsiAnnotationMemberValue.annotationString(): String? {
        if (this is PsiArrayInitializerMemberValue) {
            return initializers.firstOrNull()?.annotationString()
        }
        if (this is PsiLiteralExpression) {
            return value as? String
        }
        return null
    }

    private fun PsiExpression.staticString(): String? =
        (this as? PsiLiteralExpression)?.value as? String

    private companion object {
        private val MQ_LISTENER_ANNOTATIONS = setOf(
            "KafkaListener",
            "RabbitListener",
            "JmsListener",
            "RocketMQMessageListener",
            "StreamListener",
        )
        private val MQ_PUBLISH_METHODS = setOf(
            "send",
            "sendDefault",
            "convertAndSend",
            "syncSend",
            "asyncSend",
            "sendAndReceive",
        )
        private val CONTROLLER_ANNOTATIONS = setOf("Controller", "RestController")
        private val REQUEST_MAPPING_ANNOTATIONS = setOf(
            "RequestMapping",
            "GetMapping",
            "PostMapping",
            "PutMapping",
            "DeleteMapping",
            "PatchMapping",
        )
        private val HTTP_METHODS = setOf("GET", "POST", "PUT", "DELETE", "PATCH")
    }
}

private data class FeignClientInfo(
    val classPath: String?,
)

private data class HttpEndpoint(
    val method: String,
    val path: String,
)
