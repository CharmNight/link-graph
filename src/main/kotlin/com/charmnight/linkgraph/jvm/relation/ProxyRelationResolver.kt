package com.charmnight.linkgraph.jvm.relation

import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiArrayInitializerExpression
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNewExpression

/** 代理使用关系解析器：识别 JDK 动态代理等代理工厂调用与目标接口之间的关系。 */
class ProxyRelationResolver : JvmRelationResolver {
    /** 当前解析器的唯一标识，用于在索引中区分不同关系来源。 */
    override val id: String = "jvm.proxy"

    /**
     * 解析项目内所有方法体中出现的代理工厂调用并生成关系。
     *
     * 对每个方法遍历其 PSI 结构，识别 JDK 动态代理、Spring 代理工厂调用
     * 以及 ProxyFactory 构造，针对每种情况生成对应代理使用关系。
     */
    override fun resolve(context: JvmResolutionContext): List<JvmRelation> {
        val relations = mutableListOf<JvmRelation>()
        projectMethods(context.symbolIndex).forEach { methodSymbol ->
            val psiMethod = context.findPsiMethod(methodSymbol) ?: return@forEach
            psiMethod.accept(
                object : JavaRecursiveElementVisitor() {
                    /**
                     * 处理方法调用，分别识别 JDK 动态代理和 Spring 代理工厂，
                     * 并在命中后构造对应关系，最后继续递归访问子表达式。
                     */
                    override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                        if (isJdkProxyFactory(expression)) {
                            proxyInterfaceNames(expression).forEach { interfaceName ->
                                val target = context.symbolIndex.classByQualifiedName(interfaceName) ?: return@forEach
                                relations += relation(
                                    kind = JvmRelationKind.USES_PROXY,
                                    from = methodSymbol,
                                    to = target,
                                    confidence = JvmRelationConfidence.RULE_INFERRED,
                                    source = JvmRelationSource.FRAMEWORK_RULE,
                                    evidence = expression.evidence(
                                        "JDK dynamic proxy may implement ${target.qualifiedName}",
                                        methodSymbol.source,
                                    ),
                                    qualifier = "${methodSymbol.signature}:${target.qualifiedName}",
                                    metadata = mapOf(
                                        "proxy.kind" to "JDK_DYNAMIC_PROXY",
                                        "proxy.sourceMethod" to methodSymbol.signature,
                                        "relation.confidence.reason" to "Proxy interface is static, invocation target still depends on InvocationHandler.",
                                    ),
                                )
                            }
                        }
                        if (isSpringProxyFactory(expression)) {
                            expression.argumentList.expressions
                                .firstOrNull()
                                ?.let { argument -> com.charmnight.linkgraph.jvm.index.canonicalTypeText(argument.type) }
                                ?.let(context.symbolIndex::classByQualifiedName)
                                ?.let { target ->
                                    relations += relation(
                                        kind = JvmRelationKind.USES_PROXY,
                                        from = methodSymbol,
                                        to = target,
                                        confidence = JvmRelationConfidence.RUNTIME_REQUIRED,
                                        source = JvmRelationSource.FRAMEWORK_RULE,
                                        evidence = expression.evidence(
                                            "Spring proxy target requires runtime advisor resolution for ${target.qualifiedName}",
                                            methodSymbol.source,
                                        ),
                                        qualifier = "${methodSymbol.signature}:${target.qualifiedName}",
                                        metadata = mapOf(
                                            "proxy.kind" to "SPRING_PROXY_FACTORY",
                                            "proxy.sourceMethod" to methodSymbol.signature,
                                            "relation.requiredEvidence" to "Runtime proxy factory/advisor state is required for exact invocation target.",
                                        ),
                                    )
                                }
                        }
                        super.visitMethodCallExpression(expression)
                    }

                    /**
                     * 处理 new 表达式，识别直接构造 Spring ProxyFactory 的情况，
                     * 这种写法同样属于代理使用，因此需要建立从方法到目标类的关系。
                     */
                    override fun visitNewExpression(expression: PsiNewExpression) {
                        val constructed = expression.classReference
                            ?.resolve()
                            ?.let { resolved -> resolved as? PsiClass }
                            ?.qualifiedName
                        if (constructed == "org.springframework.aop.framework.ProxyFactory") {
                            relations += relation(
                                kind = JvmRelationKind.USES_PROXY,
                                from = methodSymbol,
                                to = context.symbolIndex.classByQualifiedName(methodSymbol.ownerClassName) ?: return,
                                confidence = JvmRelationConfidence.RUNTIME_REQUIRED,
                                source = JvmRelationSource.FRAMEWORK_RULE,
                                evidence = expression.evidence(
                                    "Spring ProxyFactory construction requires runtime advisor resolution",
                                    methodSymbol.source,
                                ),
                                qualifier = methodSymbol.signature,
                                metadata = mapOf(
                                    "proxy.kind" to "SPRING_PROXY_FACTORY",
                                    "proxy.sourceMethod" to methodSymbol.signature,
                                    "relation.requiredEvidence" to "Runtime proxy factory/advisor state is required for exact invocation target.",
                                ),
                            )
                        }
                        super.visitNewExpression(expression)
                    }
                },
            )
        }
        return relations
    }

    /**
     * 判断调用表达式是否为 JDK 动态代理工厂方法。
     *
     * 通过方法名 newProxyInstance 以及调用方限定符是否为 Proxy 判断。
     */
    private fun isJdkProxyFactory(expression: PsiMethodCallExpression): Boolean =
        expression.methodExpression.referenceName == "newProxyInstance" &&
            expression.methodExpression.qualifierExpression?.text in setOf("Proxy", "java.lang.reflect.Proxy")

    /**
     * 判断调用表达式是否为 Spring 代理工厂方法。
     *
     * 匹配方法名 getProxy/createAopProxy，并要求调用方类型中包含 ProxyFactory 字样。
     */
    private fun isSpringProxyFactory(expression: PsiMethodCallExpression): Boolean =
        expression.methodExpression.referenceName in setOf("getProxy", "createAopProxy") &&
            expression.methodExpression.qualifierExpression?.type?.canonicalText?.contains("ProxyFactory") == true

    /**
     * 从 JDK 动态代理调用中解析被代理的接口全限定名列表。
     *
     * 第二个参数通常是接口的 Class 数组，这里兼容 new 表达式和数组初始化两种形式，
     * 提取其中的 ClassObjectAccess 表达式并解析出类型文本。
     */
    private fun proxyInterfaceNames(expression: PsiMethodCallExpression): List<String> {
        val argument = expression.argumentList.expressions.getOrNull(1) ?: return emptyList()
        val initializer = when (argument) {
            is PsiNewExpression -> argument.arrayInitializer
            is PsiArrayInitializerExpression -> argument
            else -> null
        } ?: return emptyList()
        return initializer.initializers.mapNotNull { initializerExpression ->
            val classObject = initializerExpression as? PsiClassObjectAccessExpression ?: return@mapNotNull null
            com.charmnight.linkgraph.jvm.index.canonicalTypeText(classObject.operand.type)
        }
    }
}
