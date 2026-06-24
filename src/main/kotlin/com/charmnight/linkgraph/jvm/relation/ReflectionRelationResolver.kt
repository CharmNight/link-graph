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

/** 反射关系解析器：识别 Class.forName、字段访问、方法调用等反射式类型引用。 */
class ReflectionRelationResolver : JvmRelationResolver {
    /** 解析器在索引中的唯一标识，用于关联持久化结果。 */
    override val id: String = "jvm.reflection"

    /**
     * 扫描项目中所有方法体与配置文件，识别以反射形式出现的类型引用，
     * 包括 Class.forName、Class.class 字面量、getMethod/getDeclaredMethod 调用，
     * 以及注解与配置文件中以字符串形式出现的类名。
     */
    override fun resolve(context: JvmResolutionContext): List<JvmRelation> {
        val relations = mutableListOf<JvmRelation>()
        projectMethods(context.symbolIndex).forEach { methodSymbol ->
            val psiMethod = context.findPsiMethod(methodSymbol) ?: return@forEach
            val sourceClass = context.symbolIndex.classByQualifiedName(methodSymbol.ownerClassName) ?: return@forEach
            // 提前收集方法体内由 Class.forName 或 .class 字面量赋值的局部变量，
            // 供后续 getMethod/getDeclaredMethod 的 qualifier 推断时使用
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
                            // 处理 Class.forName("xxx"):优先取常量参数，命中索引则产出 PROVEN 关系，否则记为运行时占位
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
                            // 处理反射方法获取调用：尝试解析 qualifier 上的目标类与方法名，命中后绑定到方法符号或类
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

    /**
     * 构造一条由 `SomeClass.class` 字面量产生的反射关系，
     * 来源被记为 PROVEN，并通过唯一 qualifier 区分同方法内的多处出现。
     */
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

    /**
     * 扫描注解值、枚举常量与配置文件，识别以字符串形式出现的类名引用。
     * 这些引用虽非 Java 反射 API 调用，但在运行时同样会产生动态加载关系，
     * 因此作为规则推断/资源来源的弱关系产出。
     */
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

    /** 仅当表达式是字符串字面量时才返回其值，否则返回 null。 */
    private fun com.intellij.psi.PsiExpression.constantString(): String? =
        (this as? PsiLiteralExpression)?.value as? String

    /**
     * 尝试把一个表达式静态求值为字符串常量：支持字面量、字符串拼接，
     * 以及指向局部常量变量的引用，常用于推断 Class.forName 的目标类名。
     */
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

    /** 根据表达式能否静态求值决定置信度：可求值为 PROVEN，否则依赖运行时信息记为 RUNTIME_REQUIRED。 */
    private fun PsiExpression.staticReflectionConfidence(): JvmRelationConfidence =
        if (staticString() != null) JvmRelationConfidence.PROVEN else JvmRelationConfidence.RUNTIME_REQUIRED

    /**
     * 把表达式求值能力映射成可读的置信度标签，用于结果元数据，
     * 区分未知、字面量常量、派生常量、运行时值四种情况。
     */
    private fun PsiExpression?.staticConfidenceLabel(): String =
        when {
            this == null -> "UNKNOWN"
            constantString() != null -> "STATIC_CONSTANT"
            staticString() != null -> "STATIC_DERIVED_CONSTANT"
            else -> "RUNTIME_VALUE"
        }

    /**
     * 当反射目标无法静态解析（如参数来自外部配置或运行时变量）时，
     * 产出一条指向源类自身的占位关系，标记为需要运行时补强证据。
     */
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

    /**
     * 扫描方法体内的局部变量声明，收集那些由 Class.forName 或 `.class` 字面量
     * 初始化的变量，建立变量名到类名的映射，供后续 qualifier 推断使用。
     */
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

    /** 推断反射方法调用的 qualifier 所指向的目标类名：优先解析 Class.forName 调用，再退化到局部变量映射。 */
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

    /** 校验方法调用确实是 Class.forName 形态，并返回其首个参数的静态字符串值。 */
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

/** 从 `.class` 字面量表达式中提取其规范的类型名称，便于在索引中匹配。 */
    private fun classObjectTypeName(expression: PsiExpression): String? {
        val classObject = expression as? PsiClassObjectAccessExpression ?: return null
        return com.charmnight.linkgraph.jvm.index.canonicalTypeText(classObject.operand.type)
    }

    /**
     * 解析 `.class` 字面量所指向的目标类符号：先尝试按类型查询索引，
     * 再回退到 PSI 引用解析，最后处理同包简写情况，确保能命中源码外的类。
     */
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

    /** 根据归属类、方法名与参数类型签名在索引中精确查找方法符号，仅当唯一匹配时返回。 */
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

    /** 从任意文本中提取潜在类名候选：先匹配全限定名，再补充出现在文本中的简单类名，去重后返回。 */
    private fun String.classNameCandidates(knownClassNames: List<String>): List<String> {
        val exact = CLASS_NAME_PATTERN.findAll(this)
            .map { match -> match.value }
            .filter { value -> value in knownClassNames }
        val simpleNames = knownClassNames.associateBy { name -> name.substringAfterLast('.') }
        val simple = SIMPLE_CLASS_NAME_PATTERN.findAll(this)
            .mapNotNull { match -> simpleNames[match.value.removeSuffix(".class")] }
        return (exact + simple).distinct().toList()
    }

    /**
     * 判断一个静态 final 字段（或枚举常量）是否通过其字面值或名称编码了某个类名，
     * 用于发现框架约定的类名映射约定（例如按字段名匹配类）。
     */
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
        // 匹配全限定类名（至少包含一个点号）
        private val CLASS_NAME_PATTERN = Regex("""\b[a-zA-Z_$][\w$]*(?:\.[a-zA-Z_$][\w$]*)+\b""")
        // 匹配简单类名（首字母大写），可选附带 .class 后缀
        private val SIMPLE_CLASS_NAME_PATTERN = Regex("""\b[A-Z][A-Za-z0-9_$]*(?:\.class)?\b""")
    }
}
