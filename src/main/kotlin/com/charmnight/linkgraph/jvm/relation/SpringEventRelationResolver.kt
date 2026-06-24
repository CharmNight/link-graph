package com.charmnight.linkgraph.jvm.relation

import com.charmnight.linkgraph.jvm.index.JvmMethodSymbol
import com.charmnight.linkgraph.jvm.index.canonicalTypeText
import com.charmnight.linkgraph.jvm.index.methodSignature
import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNewExpression

/** Spring 事件关系解析器：把事件发布（applicationContext.publishEvent）与监听器（@EventListener）关联。 */
class SpringEventRelationResolver : JvmRelationResolver {
    /** 解析器在关系图谱中的唯一标识，固定为 Spring 事件相关的关系类型键。 */
    override val id: String = "jvm.spring-event"

    /** 被识别为事件监听器的注解全限定名集合，包含普通监听器与事务监听器两类。 */
    private val listenerAnnotations = setOf(
        "org.springframework.context.event.EventListener",
        "org.springframework.transaction.event.TransactionalEventListener",
    )

    /**
     * 执行关系解析：先收集事件监听方法，再扫描发布事件的方法，
     * 当发布的事件类名能匹配到对应监听器时生成一条监听关系。
     */
    override fun resolve(context: JvmResolutionContext): List<JvmRelation> {
        val listenerMethodsByEventClassName = listenerMethodsByEventClassName(context)
        if (listenerMethodsByEventClassName.isEmpty()) {
            return emptyList()
        }
        val relations = mutableListOf<JvmRelation>()
        projectPsiMethods(context).forEach { (publisherMethod, psiMethod) ->
            val publisherClass = context.symbolIndex.classByQualifiedName(publisherMethod.ownerClassName) ?: return@forEach
            publishedEventClassNames(psiMethod).forEach { eventClassName ->
                listenerMethodsByEventClassName[eventClassName].orEmpty().forEach { listenerMethod ->
                    val listenerClass = context.symbolIndex.classByQualifiedName(listenerMethod.ownerClassName) ?: return@forEach
                    if (listenerClass.id == publisherClass.id && listenerMethod.id == publisherMethod.id) {
                        return@forEach
                    }
                    relations += relation(
                        kind = JvmRelationKind.SPRING_EVENT_LISTENS,
                        from = publisherClass,
                        to = listenerClass,
                        confidence = JvmRelationConfidence.RULE_INFERRED,
                        source = JvmRelationSource.FRAMEWORK_RULE,
                        evidence = psiMethod.evidence(
                            "Spring event $eventClassName may be handled by ${listenerMethod.signature}",
                            publisherMethod.source,
                        ),
                        qualifier = "$eventClassName:${listenerMethod.signature}",
                        metadata = mapOf(
                            "spring.event" to eventClassName,
                            "spring.publisherMethod" to publisherMethod.signature,
                            "spring.listenerMethod" to listenerMethod.signature,
                            "relation.resolverId" to id,
                        ),
                    )
                }
            }
        }
        return relations
    }

    /** 按事件类型类名分组，返回每个事件类型对应的所有监听方法符号。 */
    private fun listenerMethodsByEventClassName(context: JvmResolutionContext): Map<String, List<JvmMethodSymbol>> {
        return projectPsiMethods(context)
            .flatMap { (method, psiMethod) ->
                if (!isSpringEventListener(psiMethod)) {
                    return@flatMap emptyList<Pair<String, JvmMethodSymbol>>()
                }
                listenerEventClassNames(psiMethod).map { eventClassName -> eventClassName to method }
            }
            .groupBy({ it.first }, { it.second })
    }

    /** 投影出符号索引中所有类的方法，并将每个方法与其 PSI 表示配对返回。 */
    private fun projectPsiMethods(context: JvmResolutionContext): List<Pair<JvmMethodSymbol, PsiMethod>> =
        projectClasses(context.symbolIndex).flatMap { classSymbol ->
            val psiClass = context.findPsiClass(classSymbol) ?: return@flatMap emptyList()
            psiClass.methods.mapNotNull { psiMethod ->
                context.symbolIndex.methodsBySignature[methodSignature(psiMethod)]?.let { methodSymbol ->
                    methodSymbol to psiMethod
                }
            }
        }

    /** 扫描方法体内的 `publishEvent` 调用，提取发布的事件类型的候选类名。 */
    private fun publishedEventClassNames(method: PsiMethod): List<String> {
        val eventClassNames = linkedSetOf<String>()
        val body = method.body ?: return emptyList()
        body.accept(
            object : JavaRecursiveElementVisitor() {
                /** 命中 publishEvent 调用时，解析首个参数的事件类名候选。 */
                override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                    if (expression.methodExpression.referenceName == "publishEvent") {
                        val eventExpression = expression.argumentList.expressions.firstOrNull()
                        eventExpression?.let(::eventClassNames)?.forEach(eventClassNames::add)
                    }
                    super.visitMethodCallExpression(expression)
                }
            },
        )
        return eventClassNames.toList()
    }

    /** 根据事件表达式推断可能的事件类名候选，区分直接 new 出来的对象与其他表达式。 */
    private fun eventClassNames(expression: com.intellij.psi.PsiExpression): List<String> =
        when (expression) {
            is PsiNewExpression -> {
                val resolvedName = (expression.classReference?.resolve() as? PsiClass)?.qualifiedName
                val shortName = expression.classReference?.referenceName
                val packageName = (expression.containingFile as? PsiJavaFile)?.packageName.orEmpty()
                eventNameCandidates(resolvedName ?: shortName, packageName)
            }
            else -> {
                val resolvedName = (expression.type as? PsiClassType)?.resolve()?.qualifiedName
                    ?: canonicalTypeText(expression.type)
                val packageName = (expression.containingFile as? PsiJavaFile)?.packageName.orEmpty()
                eventNameCandidates(resolvedName, packageName)
            }
        }

    /** 判断方法是否标注了被识别的事件监听器注解，支持全限定名、文本形式与 import 推断三种匹配方式。 */
    private fun isSpringEventListener(method: PsiMethod): Boolean =
        method.annotations.any { annotation ->
            val qualifiedName = annotation.qualifiedName
            if (qualifiedName in listenerAnnotations) {
                return@any true
            }
            val annotationTextName = annotation.text
                .trim()
                .removePrefix("@")
                .substringBefore("(")
                .trim()
            if (annotationTextName in listenerAnnotations) {
                return@any true
            }
            val simpleName = annotation.nameReferenceElement?.referenceName ?: return@any false
            val javaFile = method.containingFile as? PsiJavaFile ?: return@any false
            javaFile.importList?.allImportStatements.orEmpty().any { importStatement ->
                val importedName = importStatement.importReference?.qualifiedName
                importedName in listenerAnnotations &&
                    importedName?.substringAfterLast('.') == simpleName
            }
        }

    /** 解析监听方法的第一个参数类型，作为该监听器关注的事件类名候选。 */
    private fun listenerEventClassNames(method: PsiMethod): List<String> {
        val parameterType = method.parameterList.parameters.firstOrNull()?.type ?: return emptyList()
        val resolvedName = (parameterType as? PsiClassType)?.resolve()?.qualifiedName ?: canonicalTypeText(parameterType)
        val packageName = method.containingClass?.qualifiedName?.substringBeforeLast('.', missingDelimiterValue = "")
            ?: (method.containingFile as? PsiJavaFile)?.packageName.orEmpty()
        return eventNameCandidates(resolvedName, packageName)
    }

    /** 给定原始名称与所在包名，生成事件类名的候选列表（含全限定名或拼包名后的形式）。 */
    private fun eventNameCandidates(
        rawName: String?,
        packageName: String,
    ): List<String> {
        val name = rawName?.trim()?.takeIf(String::isNotBlank) ?: return emptyList()
        return listOf(
            when {
                name.contains('.') -> name
                packageName.isNotBlank() -> "$packageName.$name"
                else -> name
            },
        )
    }
}
