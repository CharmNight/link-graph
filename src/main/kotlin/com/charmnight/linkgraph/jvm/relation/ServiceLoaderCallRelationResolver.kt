package com.charmnight.linkgraph.jvm.relation

import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression

/** ServiceLoader 调用关系解析器：识别代码中通过 ServiceLoader.load 加载的 SPI 实现。 */
class ServiceLoaderCallRelationResolver : JvmRelationResolver {
    /** 解析器的唯一标识，用于在 JVM 关系网络中注册和引用此 ServiceLoader 解析器。 */
    override val id: String = "jvm.service-loader"

    /**
     * 扫描项目中所有方法体，识别对 ServiceLoader.load 的调用，
     * 并据此产出 SPI 接口加载关系以及对应实现的提供关系。
     */
    override fun resolve(context: JvmResolutionContext): List<JvmRelation> {
        val relations = mutableListOf<JvmRelation>()
        projectMethods(context.symbolIndex).forEach { methodSymbol ->
            val psiMethod = context.findPsiMethod(methodSymbol) ?: return@forEach
            psiMethod.accept(
                object : JavaRecursiveElementVisitor() {
                    override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                        if (!isServiceLoaderLoad(expression)) {
                            super.visitMethodCallExpression(expression)
                            return
                        }
                        val serviceClassName = expression.argumentList.expressions
                            .firstOrNull()
                            ?.let(::classObjectTypeName)
                        val serviceClass = serviceClassName?.let(context.symbolIndex::classByQualifiedName)
                        if (serviceClass != null) {
                            relations += relation(
                                kind = JvmRelationKind.SERVICE_LOADER_LOADS,
                                from = methodSymbol,
                                to = serviceClass,
                                confidence = JvmRelationConfidence.PROVEN,
                                source = JvmRelationSource.PSI,
                                evidence = expression.evidence(
                                    "ServiceLoader.load(${serviceClass.qualifiedName}.class)",
                                    methodSymbol.source,
                                ),
                                qualifier = serviceClass.qualifiedName,
                                metadata = mapOf(
                                    "service.loader.interface" to serviceClass.qualifiedName,
                                    "service.loader.sourceMethod" to methodSymbol.signature,
                                ),
                            )
                            context.symbolIndex.serviceProviderIndex.providersFor(serviceClass.qualifiedName)
                                .flatMap { file -> file.providerClassNames }
                                .distinct()
                                .mapNotNull(context.symbolIndex::classByQualifiedName)
                                .forEach { providerClass ->
                                    relations += relation(
                                        kind = JvmRelationKind.SPI_PROVIDES,
                                        from = methodSymbol,
                                        to = providerClass,
                                        confidence = JvmRelationConfidence.RULE_INFERRED,
                                        source = JvmRelationSource.FRAMEWORK_RULE,
                                        evidence = expression.evidence(
                                            "ServiceLoader.load may select ${providerClass.qualifiedName}",
                                            methodSymbol.source,
                                        ),
                                        qualifier = "${serviceClass.qualifiedName}:${providerClass.qualifiedName}:${methodSymbol.signature}",
                                        metadata = mapOf(
                                            "service.loader.interface" to serviceClass.qualifiedName,
                                            "service.loader.provider" to providerClass.qualifiedName,
                                            "service.loader.sourceMethod" to methodSymbol.signature,
                                            "relation.confidence.reason" to "Provider declared in META-INF/services; runtime selection depends on classpath order.",
                                        ),
                                    )
                                }
                        } else if (expression.argumentList.expressions.isNotEmpty()) {
                            val ownerClass = context.symbolIndex.classByQualifiedName(methodSymbol.ownerClassName)
                            if (ownerClass != null) {
                                relations += relation(
                                    kind = JvmRelationKind.SERVICE_LOADER_LOADS,
                                    from = methodSymbol,
                                    to = ownerClass,
                                    confidence = JvmRelationConfidence.RUNTIME_REQUIRED,
                                    source = JvmRelationSource.FRAMEWORK_RULE,
                                    evidence = expression.evidence(
                                        "ServiceLoader.load target is not a static class literal",
                                        methodSymbol.source,
                                    ),
                                    qualifier = methodSymbol.signature,
                                    metadata = mapOf(
                                        "service.loader.sourceMethod" to methodSymbol.signature,
                                        "relation.requiredEvidence" to "Runtime class literal or configuration value is required.",
                                    ),
                                )
                            }
                        }
                        super.visitMethodCallExpression(expression)
                    }
                },
            )
        }
        return relations
    }

    /**
     * 判断给定方法调用表达式是否为 ServiceLoader.load 系列调用，
     * 兼容简短写法、全限定名写法以及解析后引用 ServiceLoader 的情况。
     */
    private fun isServiceLoaderLoad(expression: PsiMethodCallExpression): Boolean {
        if (expression.methodExpression.referenceName != "load") {
            return false
        }
        val qualifier = expression.methodExpression.qualifierExpression
        if (qualifier?.text in setOf("ServiceLoader", "java.util.ServiceLoader")) {
            return true
        }
        val resolved = (qualifier as? PsiReferenceExpression)?.resolve()
        return resolved?.text?.contains("java.util.ServiceLoader") == true
    }

    /**
     * 从表达式里提取类对象字面量所对应的规范化类型名，
     * 用于识别 ServiceLoader.load(Xxx.class) 中传入的服务接口。
     */
    private fun classObjectTypeName(expression: com.intellij.psi.PsiExpression): String? {
        val classObject = expression as? PsiClassObjectAccessExpression ?: return null
        return com.charmnight.linkgraph.jvm.index.canonicalTypeText(classObject.operand.type)
    }
}
