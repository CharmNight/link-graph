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

class SpringEventRelationResolver : JvmRelationResolver {
    override val id: String = "jvm.spring-event"

    private val listenerAnnotations = setOf(
        "org.springframework.context.event.EventListener",
        "org.springframework.transaction.event.TransactionalEventListener",
    )

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

    private fun projectPsiMethods(context: JvmResolutionContext): List<Pair<JvmMethodSymbol, PsiMethod>> =
        projectClasses(context.symbolIndex).flatMap { classSymbol ->
            val psiClass = context.findPsiClass(classSymbol) ?: return@flatMap emptyList()
            psiClass.methods.mapNotNull { psiMethod ->
                context.symbolIndex.methodsBySignature[methodSignature(psiMethod)]?.let { methodSymbol ->
                    methodSymbol to psiMethod
                }
            }
        }

    private fun publishedEventClassNames(method: PsiMethod): List<String> {
        val eventClassNames = linkedSetOf<String>()
        val body = method.body ?: return emptyList()
        body.accept(
            object : JavaRecursiveElementVisitor() {
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

    private fun listenerEventClassNames(method: PsiMethod): List<String> {
        val parameterType = method.parameterList.parameters.firstOrNull()?.type ?: return emptyList()
        val resolvedName = (parameterType as? PsiClassType)?.resolve()?.qualifiedName ?: canonicalTypeText(parameterType)
        val packageName = method.containingClass?.qualifiedName?.substringBeforeLast('.', missingDelimiterValue = "")
            ?: (method.containingFile as? PsiJavaFile)?.packageName.orEmpty()
        return eventNameCandidates(resolvedName, packageName)
    }

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
