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

/** 框架关系解析器：识别 Dubbo、Feign、MQ 等远程/消息框架相关的关系。 */
class FrameworkRelationResolver : JvmRelationResolver {
    /** 解析器稳定标识，外部按此 ID 注册和去重。 */
    override val id: String = "jvm.framework"

    /** 汇总 Dubbo、Feign、MQ 三类框架关系并返回给上游聚合。 */
    override fun resolve(context: JvmResolutionContext): List<JvmRelation> {
        return dubboRelations(context) + feignRelations(context) + mqRelations(context)
    }

    /**
     * 扫描项目中的 Dubbo Service 提供方与 Reference 注入字段，建立 PROVIDES / REFERENCES 关系。
     * PROVIDES 来自注解直接声明的接口实现，REFERENCES 来自字段注入的接口类型推断。
     */
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

    /** 提取 Dubbo Service 注解类所实现的对外服务接口符号列表。 */
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

    /**
     * 收集所有 Feign 客户端接口，再扫描项目方法中对这些接口的调用，
     * 同时把客户端方法映射到 HTTP 端点资源以及对应的服务端 Controller 实现。
     */
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

    /** 汇总项目中所有 Controller 暴露的 HTTP 端点，按端点聚合对应的处理方法。 */
    private fun httpEndpoints(context: JvmResolutionContext): Map<HttpEndpoint, List<com.charmnight.linkgraph.jvm.index.JvmMethodSymbol>> {
        return projectMethods(context.symbolIndex)
            .mapNotNull { methodSymbol ->
                val psiMethod = context.findPsiMethod(methodSymbol) ?: return@mapNotNull null
                val endpoint = controllerEndpoint(psiMethod) ?: return@mapNotNull null
                endpoint to methodSymbol
            }
            .groupBy({ it.first }, { it.second })
    }

    /** 转交给 HTTP 端点提取器，识别 Controller 方法对应的 HTTP 端点。 */
    private fun controllerEndpoint(method: com.intellij.psi.PsiMethod): HttpEndpoint? {
        return HttpEndpointRelationExtractor.controllerEndpoint(method)
    }

    /** 转交给 HTTP 端点提取器，根据类路径前缀和 Feign 方法签名推导端点。 */
    private fun feignEndpoint(
        classPath: String?,
        method: com.intellij.psi.PsiMethod,
    ): HttpEndpoint? {
        return HttpEndpointRelationExtractor.feignEndpoint(classPath, method)
    }

    /** 转交给 HTTP 端点提取器，获取请求方法字符串（GET/POST 等）。 */
    private fun requestMethod(method: com.intellij.psi.PsiMethod): String? {
        return HttpEndpointRelationExtractor.requestMethod(method)
    }

    /** 转交给 HTTP 端点提取器，获取请求路径。 */
    private fun requestPath(method: com.intellij.psi.PsiMethod): String? {
        return HttpEndpointRelationExtractor.requestPath(method)
    }

    /** 转交给 HTTP 端点提取器，把类路径与方法路径拼接成完整 URL。 */
    private fun combinePaths(
        classPath: String?,
        methodPath: String?,
    ): String? {
        return HttpEndpointRelationExtractor.combinePaths(classPath, methodPath)
    }

    /** 把 HTTP 端点转换为资源符号，用于在图谱中作为虚拟节点承载路由关系。 */
    private fun endpointResourceSymbol(endpoint: HttpEndpoint): JvmResourceSymbol =
        HttpEndpointRelationExtractor.endpointResourceSymbol(endpoint)

    /**
     * 扫描 MQ 监听注解与 MQ 发送调用，分别建立 CONSUMES 与 PUBLISHES 关系。
     * 同时把发布方法直接关联回监听方法，便于在没有显式主题资源时仍能追溯消费链路。
     */
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

    /** 根据监听注解的属性识别 MQ 目的地（topic/queue/destination 等）。 */
    private fun mqDestination(annotation: PsiAnnotation): String? {
        if (annotation.simpleName() !in MQ_LISTENER_ANNOTATIONS) {
            return null
        }
        return listOf("topics", "topic", "value", "queues", "queue", "destination")
            .firstNotNullOfOrNull { name -> annotation.annotationStringValue(name) }
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }

    /** 判断方法调用是否符合 MQ 模板发送的命名约定且接收者类型是已知的 MQ 客户端。 */
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

    /** 取出 MQ 发送调用第一个参数中的静态字符串作为目的地，无法解析时返回 null。 */
    private fun mqDestinationArgument(expression: PsiMethodCallExpression): String? =
        expression.argumentList.expressions.firstOrNull()?.staticString()
            ?.trim()
            ?.takeIf(String::isNotBlank)

    /** 按目的地查找 MQ 主题资源符号，支持直接命名或带 mq: 前缀的命名空间。 */
    private fun mqTopicSymbol(
        context: JvmResolutionContext,
        destination: String,
    ): com.charmnight.linkgraph.jvm.index.JvmResourceSymbol? =
        context.symbolIndex.resourcesByPath[destination]
            ?: context.symbolIndex.resourcesByPath["mq:$destination"]

    /** 提取注解的简短名，兼容全限定名和仅名称引用两种情况。 */
    private fun PsiAnnotation.simpleName(): String? =
        qualifiedName?.substringAfterLast('.') ?: nameReferenceElement?.referenceName

    /** 判定注解是否为 Dubbo 服务提供方注解（兼容 Apache / Alibaba 两套命名空间）。 */
    private fun PsiAnnotation.isDubboServiceAnnotation(): Boolean {
        val qualified = qualifiedName.orEmpty()
        val simple = simpleName()
        return simple == "DubboService" ||
            qualified in setOf(
                "org.apache.dubbo.config.annotation.Service",
                "com.alibaba.dubbo.config.annotation.Service",
            )
    }

    /** 判定注解是否为 Dubbo 引用注入注解（兼容 Apache / Alibaba 两套命名空间）。 */
    private fun PsiAnnotation.isDubboReferenceAnnotation(): Boolean {
        val qualified = qualifiedName.orEmpty()
        val simple = simpleName()
        return simple == "DubboReference" ||
            qualified in setOf(
                "org.apache.dubbo.config.annotation.Reference",
                "com.alibaba.dubbo.config.annotation.Reference",
            )
    }

    /** 判定注解是否为 Feign 客户端注解（兼容 Spring Cloud OpenFeign 与旧版 Netflix 命名空间）。 */
    private fun PsiAnnotation.isFeignClientAnnotation(): Boolean {
        val qualified = qualifiedName.orEmpty()
        return simpleName() == "FeignClient" ||
            qualified == "org.springframework.cloud.openfeign.FeignClient" ||
            qualified == "org.springframework.cloud.netflix.feign.FeignClient"
    }

    /** 读取注解上指定属性的字符串值，对 value 属性也支持缺省名引用。 */
    private fun PsiAnnotation.annotationStringValue(name: String): String? =
        findDeclaredAttributeValue(name)?.annotationString()
            ?: if (name == "value") {
                findDeclaredAttributeValue(null)?.annotationString()
            } else {
                null
            }

    /** 解析注解上 Class 数组属性的类名字符串列表，去掉 .class 后缀和占位 void 类型。 */
    private fun PsiAnnotation.classValues(name: String): List<String> =
        findDeclaredAttributeValue(name)?.text
            ?.split(',', '{', '}')
            ?.map { value -> value.trim().removeSuffix(".class") }
            ?.filter { value -> value != "void" && value.contains('.') }
            .orEmpty()

    /** 把注解成员值统一规约为字符串：数组取首元素，字面量取字符串值。 */
    private fun PsiAnnotationMemberValue.annotationString(): String? {
        if (this is PsiArrayInitializerMemberValue) {
            return initializers.firstOrNull()?.annotationString()
        }
        if (this is PsiLiteralExpression) {
            return value as? String
        }
        return null
    }

    /** 把表达式规约为静态字符串字面量，常用于解析注解或方法参数中的常量。 */
    private fun PsiExpression.staticString(): String? =
        (this as? PsiLiteralExpression)?.value as? String

    private companion object {
        /** MQ 监听注解的简短名集合，覆盖 Kafka/Rabbit/JMS/RocketMQ/Stream 主流客户端。 */
        private val MQ_LISTENER_ANNOTATIONS = setOf(
            "KafkaListener",
            "RabbitListener",
            "JmsListener",
            "RocketMQMessageListener",
            "StreamListener",
        )
        /** MQ 模板常见的发送方法名集合，用于识别发布动作。 */
        private val MQ_PUBLISH_METHODS = setOf(
            "send",
            "sendDefault",
            "convertAndSend",
            "syncSend",
            "asyncSend",
            "sendAndReceive",
        )
        /** Spring Controller 相关注解简短名集合。 */
        private val CONTROLLER_ANNOTATIONS = setOf("Controller", "RestController")
        /** Spring MVC 路径映射注解简短名集合。 */
        private val REQUEST_MAPPING_ANNOTATIONS = setOf(
            "RequestMapping",
            "GetMapping",
            "PostMapping",
            "PutMapping",
            "DeleteMapping",
            "PatchMapping",
        )
        /** 已知的 HTTP 方法大写集合，用于规范化端点元数据。 */
        private val HTTP_METHODS = setOf("GET", "POST", "PUT", "DELETE", "PATCH")
    }
}

/** Feign 客户端解析过程中缓存每个客户端接口对应的类路径前缀。 */
private data class FeignClientInfo(
    /** Feign 客户端注解上声明的 path 前缀，用于拼接完整请求路径。 */
    val classPath: String?,
)
