package com.charmnight.linkgraph.jvm.relation

import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiDeclarationStatement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiPolyadicExpression
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.PsiTreeUtil

class ReflectionRelationResolver : JvmRelationResolver {
    override val id: String = "jvm.reflection"

    override fun resolve(context: JvmResolutionContext): List<JvmRelation> {
        val relations = mutableListOf<JvmRelation>()
        projectMethods(context.symbolIndex).forEach { methodSymbol ->
            val psiMethod = context.findPsiMethod(methodSymbol) ?: return@forEach
            val sourceClass = context.symbolIndex.classByQualifiedName(methodSymbol.ownerClassName) ?: return@forEach
            val classVariables = classForNameVariables(psiMethod.body)
            PsiTreeUtil.collectElementsOfType(psiMethod.body, PsiClassObjectAccessExpression::class.java)
                .forEach { expression ->
                    val target = classObjectTarget(context, expression)
                    if (target != null && target.id != sourceClass.id) {
                        relations += classLiteralRelation(sourceClass, target, methodSymbol, expression)
                    }
                }
            psiMethod.accept(
                object : JavaRecursiveElementVisitor() {
                    override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                        val methodName = expression.methodExpression.referenceName
                        if (methodName == "forName") {
                            val classNameExpression = expression.argumentList.expressions.firstOrNull()
                            val resolvedClassName = classNameExpression?.staticString()
                            val target = resolvedClassName?.let(context.symbolIndex::classByQualifiedName)
                            if (target != null && target.id != sourceClass.id) {
                                relations += relation(
                                    kind = JvmRelationKind.REFLECTS_TO,
                                    from = sourceClass,
                                    to = target,
                                    confidence = classNameExpression.staticReflectionConfidence(),
                                    source = JvmRelationSource.PSI,
                                    evidence = expression.evidence("constant Class.forName to ${target.qualifiedName}", methodSymbol.source),
                                    metadata = mapOf(
                                        "reflect.kind" to "CLASS_FOR_NAME",
                                        "reflect.sourceMethod" to methodSymbol.signature,
                                        "confidence.label" to classNameExpression.staticConfidenceLabel(),
                                    ),
                                )
                            } else if (classNameExpression != null) {
                                relations += unresolvedRuntimeReflectionRelation(
                                    sourceClass = sourceClass,
                                    methodSymbol = methodSymbol,
                                    expression = expression,
                                    kind = "CLASS_FOR_NAME",
                                )
                            }
                        } else if (methodName == "getMethod" || methodName == "getDeclaredMethod") {
                            val qualifierClassName = reflectedClassName(
                                expression.methodExpression.qualifierExpression,
                                classVariables,
                            ) ?: expression.methodExpression.qualifierExpression?.type
                                ?.let { type -> com.charmnight.linkgraph.jvm.index.canonicalTypeText(type) }
                            val qualifierClass = qualifierClassName?.let(context.symbolIndex::classByQualifiedName)
                            val methodNameExpression = expression.argumentList.expressions.firstOrNull()
                            val methodNameLiteral = methodNameExpression?.staticString()
                            if (qualifierClass != null && methodNameLiteral != null && qualifierClass.id != sourceClass.id) {
                                val targetMethod = targetMethodSymbol(
                                    ownerClassName = qualifierClass.qualifiedName,
                                    methodName = methodNameLiteral,
                                    parameterTypes = expression.argumentList.expressions
                                        .drop(1)
                                        .mapNotNull(::classObjectTypeName),
                                    context = context,
                                )
                                relations += relation(
                                    kind = JvmRelationKind.REFLECTS_TO,
                                    from = sourceClass,
                                    to = targetMethod ?: qualifierClass,
                                    confidence = JvmRelationConfidence.PROVEN,
                                    source = JvmRelationSource.PSI,
                                    evidence = expression.evidence("constant reflection method $methodNameLiteral on ${qualifierClass.qualifiedName}", methodSymbol.source),
                                    qualifier = methodNameLiteral,
                                    metadata = mapOf(
                                        "reflect.kind" to methodName.uppercase(),
                                        "reflect.sourceMethod" to methodSymbol.signature,
                                        "reflect.method" to methodNameLiteral,
                                        "reflect.targetClass" to qualifierClass.qualifiedName,
                                        "confidence.label" to methodNameExpression.staticConfidenceLabel(),
                                    ),
                                )
                            } else if (methodNameExpression != null || expression.methodExpression.qualifierExpression != null) {
                                relations += unresolvedRuntimeReflectionRelation(
                                    sourceClass = sourceClass,
                                    methodSymbol = methodSymbol,
                                    expression = expression,
                                    kind = methodName.uppercase(),
                                )
                            }
                        }
                        super.visitMethodCallExpression(expression)
                    }
                },
            )
        }
        return relations + configAndAnnotationReflectionRelations(context)
    }

    private fun classLiteralRelation(
        sourceClass: com.charmnight.linkgraph.jvm.index.JvmClassSymbol,
        target: com.charmnight.linkgraph.jvm.index.JvmClassSymbol,
        methodSymbol: com.charmnight.linkgraph.jvm.index.JvmMethodSymbol,
        expression: PsiClassObjectAccessExpression,
    ): JvmRelation =
        relation(
            kind = JvmRelationKind.REFLECTS_TO,
            from = sourceClass,
            to = target,
            confidence = JvmRelationConfidence.PROVEN,
            source = JvmRelationSource.PSI,
            evidence = expression.evidence("${target.qualifiedName}.class static class literal", methodSymbol.source),
            qualifier = "${methodSymbol.signature}:CLASS_LITERAL:${expression.textRange.startOffset}",
            metadata = mapOf(
                "reflect.kind" to "CLASS_LITERAL",
                "reflect.sourceMethod" to methodSymbol.signature,
                "reflect.targetClass" to target.qualifiedName,
                "confidence.label" to "STATIC_CLASS_LITERAL",
            ),
        )

    private fun configAndAnnotationReflectionRelations(context: JvmResolutionContext): List<JvmRelation> {
        val classNames = context.symbolIndex.classesByQualifiedName.keys.toList()
        if (classNames.isEmpty()) {
            return emptyList()
        }
        val relations = mutableListOf<JvmRelation>()
        projectClasses(context.symbolIndex).forEach { classSymbol ->
            val psiClass = context.findPsiClass(classSymbol) ?: return@forEach
            psiClass.annotations.forEach { annotation ->
                annotation.text.classNameCandidates(classNames).forEach { targetName ->
                    val target = context.symbolIndex.classByQualifiedName(targetName) ?: return@forEach
                    val annotationClassName = annotation.resolveAnnotationType()?.qualifiedName
                    if (target.id != classSymbol.id && target.qualifiedName != annotationClassName) {
                        relations += relation(
                            kind = JvmRelationKind.REFLECTS_TO,
                            from = classSymbol,
                            to = target,
                            confidence = JvmRelationConfidence.RULE_INFERRED,
                            source = JvmRelationSource.FRAMEWORK_RULE,
                            evidence = annotation.evidence("annotation value references ${target.qualifiedName}", classSymbol.source),
                            qualifier = "${classSymbol.qualifiedName}:${annotation.textRange.startOffset}:${target.qualifiedName}",
                            metadata = mapOf(
                                "reflect.kind" to "ANNOTATION_VALUE",
                                "reflect.targetClass" to target.qualifiedName,
                                "confidence.label" to "ANNOTATION_LITERAL",
                            ),
                        )
                    }
                }
            }
            psiClass.fields.forEach { field ->
                field.enumClassNameCandidate(context)?.let { targetName ->
                    val target = context.symbolIndex.classByQualifiedName(targetName) ?: return@let
                    if (target.id != classSymbol.id) {
                        relations += relation(
                            kind = JvmRelationKind.REFLECTS_TO,
                            from = classSymbol,
                            to = target,
                            confidence = JvmRelationConfidence.AMBIGUOUS,
                            source = JvmRelationSource.FRAMEWORK_RULE,
                            evidence = field.evidence("enum constant may encode class name ${target.qualifiedName}", classSymbol.source),
                            qualifier = "${classSymbol.qualifiedName}:${field.name}:${target.qualifiedName}",
                            metadata = mapOf(
                                "reflect.kind" to "ENUM_CONSTANT_NAME",
                                "reflect.targetClass" to target.qualifiedName,
                                "relation.confidence.reason" to "Enum/constant name matches a known class simple name; static runtime binding is not proven.",
                            ),
                        )
                    }
                }
            }
        }
        context.symbolIndex.resourcesByPath.values.forEach { resource ->
            if (resource.source == null || resource.kind !in setOf(
                    com.charmnight.linkgraph.jvm.index.JvmResourceKind.XML,
                    com.charmnight.linkgraph.jvm.index.JvmResourceKind.YAML,
                    com.charmnight.linkgraph.jvm.index.JvmResourceKind.PROPERTIES,
                )
            ) {
                return@forEach
            }
            val content = context.sourceResolver.readSnippetByPath(
                resource.source.virtualFileUrl ?: resource.source.displayPath,
                null,
                null,
            ) ?: return@forEach
            content.text.classNameCandidates(classNames).forEach { targetName ->
                val target = context.symbolIndex.classByQualifiedName(targetName) ?: return@forEach
                relations += relation(
                    kind = JvmRelationKind.REFLECTS_TO,
                    from = resource,
                    to = target,
                    confidence = JvmRelationConfidence.RULE_INFERRED,
                    source = JvmRelationSource.RESOURCE_FILE,
                    evidence = resource.source.evidence("configuration references ${target.qualifiedName}"),
                    qualifier = "${resource.path}:${target.qualifiedName}",
                    metadata = mapOf(
                        "reflect.kind" to "CONFIG_VALUE",
                        "reflect.targetClass" to target.qualifiedName,
                        "resource.path" to resource.path,
                        "confidence.label" to "CONFIG_LITERAL",
                    ),
                )
            }
        }
        return relations
    }

    private fun com.intellij.psi.PsiExpression.constantString(): String? =
        (this as? PsiLiteralExpression)?.value as? String

    private fun PsiExpression.staticString(): String? {
        constantString()?.let { return it }
        if (this is PsiPolyadicExpression) {
            val parts = operands.map { operand -> operand.staticString() }
            if (parts.size == operands.size && parts.all { part -> part != null }) {
                return parts.joinToString("")
            }
        }
        if (this is PsiReferenceExpression) {
            val variable = resolve() as? PsiVariable
            return variable?.initializer?.staticString()
        }
        return null
    }

    private fun PsiExpression.staticReflectionConfidence(): JvmRelationConfidence =
        if (staticString() != null) JvmRelationConfidence.PROVEN else JvmRelationConfidence.RUNTIME_REQUIRED

    private fun PsiExpression?.staticConfidenceLabel(): String =
        when {
            this == null -> "UNKNOWN"
            constantString() != null -> "STATIC_CONSTANT"
            staticString() != null -> "STATIC_DERIVED_CONSTANT"
            else -> "RUNTIME_VALUE"
        }

    private fun unresolvedRuntimeReflectionRelation(
        sourceClass: com.charmnight.linkgraph.jvm.index.JvmClassSymbol,
        methodSymbol: com.charmnight.linkgraph.jvm.index.JvmMethodSymbol,
        expression: PsiMethodCallExpression,
        kind: String,
    ): JvmRelation {
        return relation(
            kind = JvmRelationKind.REFLECTS_TO,
            from = sourceClass,
            to = sourceClass,
            confidence = JvmRelationConfidence.RUNTIME_REQUIRED,
            source = JvmRelationSource.FRAMEWORK_RULE,
            evidence = expression.evidence("reflection target for $kind requires runtime value", methodSymbol.source),
            qualifier = "${methodSymbol.signature}:$kind:${expression.textRange.startOffset}",
            metadata = mapOf(
                "reflect.kind" to kind,
                "reflect.sourceMethod" to methodSymbol.signature,
                "relation.requiredEvidence" to "Runtime class/method name value or configuration evidence is required.",
                "relation.runtimePlaceholder" to "true",
            ),
        )
    }

    private fun classForNameVariables(body: com.intellij.psi.PsiCodeBlock?): Map<String, String> {
        if (body == null) {
            return emptyMap()
        }
        val variables = linkedMapOf<String, String>()
        PsiTreeUtil.collectElementsOfType(body, PsiDeclarationStatement::class.java).forEach { statement ->
            statement.declaredElements.filterIsInstance<PsiLocalVariable>().forEach { variable ->
                val initializer = variable.initializer
                val className = (initializer as? PsiMethodCallExpression)?.let(::classForNameLiteral)
                    ?: (initializer as? PsiClassObjectAccessExpression)?.let(::classObjectTypeName)
                    ?: return@forEach
                variables[variable.name] = className
            }
        }
        return variables
    }

    private fun reflectedClassName(
        qualifier: PsiExpression?,
        variables: Map<String, String>,
    ): String? {
        val call = qualifier as? PsiMethodCallExpression
        if (call != null) {
            return classForNameLiteral(call)
        }
        return qualifier?.text?.let(variables::get)
    }

    private fun classForNameLiteral(call: PsiMethodCallExpression): String? {
        if (call.methodExpression.referenceName != "forName") {
            return null
        }
        val qualifierText = call.methodExpression.qualifierExpression?.text
        if (qualifierText != "Class" && qualifierText != "java.lang.Class") {
            return null
        }
        return call.argumentList.expressions.firstOrNull()?.staticString()
    }

    private fun classObjectTypeName(expression: PsiExpression): String? {
        val classObject = expression as? PsiClassObjectAccessExpression ?: return null
        return com.charmnight.linkgraph.jvm.index.canonicalTypeText(classObject.operand.type)
    }

    private fun classObjectTarget(
        context: JvmResolutionContext,
        expression: PsiClassObjectAccessExpression,
    ): com.charmnight.linkgraph.jvm.index.JvmClassSymbol? {
        val operandType = expression.operand.type
        context.symbolIndex.classByType(operandType)?.let { return it }
        val resolvedClass = PsiTreeUtil.getChildOfType(expression.operand, com.intellij.psi.PsiJavaCodeReferenceElement::class.java)
            ?.resolve() as? PsiClass
        context.symbolIndex.classByQualifiedName(resolvedClass?.qualifiedName)?.let { return it }
        return classObjectTypeName(expression)
            ?.let { typeName ->
                context.symbolIndex.classByQualifiedName(typeName)
                    ?: context.symbolIndex.classByQualifiedName(
                        typeName
                            .takeIf { candidate -> !candidate.contains('.') }
                            ?.let { simpleName ->
                                PsiTreeUtil.getParentOfType(expression, PsiClass::class.java)
                                    ?.qualifiedName
                                    ?.substringBeforeLast('.', missingDelimiterValue = "")
                                    ?.takeIf(String::isNotBlank)
                                    ?.let { packageName -> "$packageName.$simpleName" }
                            },
                    )
            }
    }

    private fun targetMethodSymbol(
        ownerClassName: String,
        methodName: String,
        parameterTypes: List<String>,
        context: JvmResolutionContext,
    ): com.charmnight.linkgraph.jvm.index.JvmMethodSymbol? {
        val candidates = context.symbolIndex.methodsBySignature.values
            .filter { method ->
                method.ownerClassName == ownerClassName &&
                    method.simpleName == methodName &&
                    method.parameterTypes == parameterTypes
            }
        return candidates.singleOrNull()
    }

    private fun String.classNameCandidates(knownClassNames: List<String>): List<String> {
        val exact = CLASS_NAME_PATTERN.findAll(this)
            .map { match -> match.value }
            .filter { value -> value in knownClassNames }
        val simpleNames = knownClassNames.associateBy { name -> name.substringAfterLast('.') }
        val simple = SIMPLE_CLASS_NAME_PATTERN.findAll(this)
            .mapNotNull { match -> simpleNames[match.value.removeSuffix(".class")] }
        return (exact + simple).distinct().toList()
    }

    private fun PsiField.enumClassNameCandidate(context: JvmResolutionContext): String? {
        if (!hasModifierProperty(PsiModifier.STATIC) || !hasModifierProperty(PsiModifier.FINAL)) {
            return null
        }
        val constantText = (initializer as? PsiLiteralExpression)?.value as? String ?: name
        val simple = constantText
            .split('.', '_', '-')
            .joinToString("") { part -> part.lowercase().replaceFirstChar { char -> char.uppercaseChar() } }
            .takeIf(String::isNotBlank)
            ?: return null
        return context.symbolIndex.classesByQualifiedName.keys.firstOrNull { className ->
            className.substringAfterLast('.').equals(simple, ignoreCase = true)
        }
    }

    private companion object {
        private val CLASS_NAME_PATTERN = Regex("""\b[a-zA-Z_$][\w$]*(?:\.[a-zA-Z_$][\w$]*)+\b""")
        private val SIMPLE_CLASS_NAME_PATTERN = Regex("""\b[A-Z][A-Za-z0-9_$]*(?:\.class)?\b""")
    }
}
