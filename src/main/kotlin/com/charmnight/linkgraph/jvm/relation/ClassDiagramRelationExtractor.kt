package com.charmnight.linkgraph.jvm.relation

import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmFieldSymbol
import com.charmnight.linkgraph.jvm.index.JvmFieldTypeReference
import com.charmnight.linkgraph.jvm.index.JvmFieldTypeRole
import com.charmnight.linkgraph.jvm.index.JvmMethodSymbol
import com.charmnight.linkgraph.jvm.index.JvmSymbol
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex
import com.charmnight.linkgraph.jvm.index.canonicalTypeText
import com.charmnight.linkgraph.jvm.index.effectiveTypeReferences
import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiAssignmentExpression
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeElement
import com.intellij.openapi.progress.ProgressManager

/** 类图关系角色种类，标注关系出现在源码中的具体形式及其基础权重。 */
enum class ClassDiagramRelationRole(
    val label: String,
    val baseWeight: Int,
) {
    // 当前类通过 extends 直接继承某个父类，权重最高
    EXTENDS("extends", 100),
    // 当前类实现了某个接口的契约，关系强度仅次于继承
    IMPLEMENTS("implements", 95),
    // 类的字段引用了目标类型，构成稳定持有关系
    FIELD("field", 90),
    // 构造函数形参引入的类型，常用于依赖注入或组装
    CONSTRUCTOR_PARAMETER("ctor", 80),
    // 方法体中调用了目标类型的方法
    METHOD_CALL("call", 70),
    // 方法内部声明的局部变量涉及的目标类型
    LOCAL_TYPE("local", 65),
    // 方法返回值类型为目标类型，反映对外暴露的依赖
    METHOD_RETURN("return", 60),
    // 普通方法的形参类型为目标类型
    METHOD_PARAMETER("param", 45),
    // 方法抛出或捕获的异常类型
    THROWS("throws", 25),
}

/** 类图结构关系提取器：基于符号索引与 PSI 提取类之间的继承、字段、调用等关系。 */
object ClassDiagramRelationExtractor {
    // 关系 metadata 中记录角色标识所用的键名，对应 ClassDiagramRelationRole 枚举的 name
    const val ROLE_KEY = "classDiagram.relation.role"
    // 计算后的权重数值所用的键名，前端根据该值对关系线进行视觉强调
    const val WEIGHT_KEY = "classDiagram.relation.weight"
    // 关系的人类可读证据说明所用的键名，用于校验面板展示溯源信息
    const val EVIDENCE_KEY = "classDiagram.relation.evidence"
    // 触发该关系的成员（字段或参数）名称所用的键名，便于精确定位
    const val MEMBER_NAME_KEY = "classDiagram.relation.memberName"
    // 触发该关系的方法名称所用的键名，用于标注调用与签名上下文
    const val METHOD_NAME_KEY = "classDiagram.relation.methodName"
    // 标识目标类型是否在方法体内被真正使用，影响权重评估
    const val USED_IN_BODY_KEY = "classDiagram.relation.usedInBody"
    // 关系在 UI 上展示给用户的综合标签所用的键名
    const val LABEL_KEY = "classDiagram.relation.label"
    // 角色短标签（如 extends、field）所用的键名，用于图例或缩略展示
    const val ROLE_LABEL_KEY = "classDiagram.relation.roleLabel"
    // 标识类型是否以字段形式被长期持有，区别于仅在形参中短暂出现
    const val HELD_BY_FIELD_KEY = "classDiagram.relation.heldByField"
    // 标识构造函数形参最终是否赋值给了字段，反映依赖的稳定性
    const val FIELD_ASSIGNED_KEY = "classDiagram.relation.assignedToField"

    /**
     * 汇总所有结构化关系，组合继承关系与符号级别类型关系（字段、方法签名等）。
     * 用于在不依赖 PSI 解析的情况下快速生成关系全貌。
     */
    fun extractStructureRelations(
        symbolIndex: JvmSymbolIndex,
        extraMetadata: Map<String, String> = emptyMap(),
    ): List<JvmRelation> =
        extractInheritanceRelations(symbolIndex, extraMetadata) +
            extractSymbolTypeRelations(symbolIndex, extraMetadata)

    /**
     * 仅遍历索引中的类符号，提取 extends/implements 关系。
     * 接口对接口的继承会被识别为 EXTENDS，类对接口的实现识别为 IMPLEMENTS。
     */
    fun extractInheritanceRelations(
        symbolIndex: JvmSymbolIndex,
        extraMetadata: Map<String, String> = emptyMap(),
    ): List<JvmRelation> {
        val relations = mutableListOf<JvmRelation>()
        symbolIndex.classesByQualifiedName.values.forEach { classSymbol ->
            // 优先通过索引解析父类名，忽略自引用与 java.lang.Object
            classSymbol.superClassName
                ?.takeIf(String::isNotBlank)
                ?.let { superClassName -> symbolIndex.findClassByTypeName(superClassName, classSymbol.packageName) }
                ?.takeUnless { target -> target.id == classSymbol.id || target.qualifiedName == "java.lang.Object" }
                ?.let { superClass ->
                    relations += relationFor(
                        role = ClassDiagramRelationRole.EXTENDS,
                        kind = JvmRelationKind.EXTENDS,
                        from = classSymbol,
                        to = superClass,
                        confidence = JvmRelationConfidence.PROVEN,
                        source = relationSourceFor(classSymbol),
                        evidence = classSymbol.evidence("extends ${superClass.qualifiedName}"),
                        memberName = classSymbol.simpleName,
                        usedInBody = false,
                        extraMetadata = extraMetadata,
                    )
                }
            // 接口列表需去重，再判断当前类是接口（用 EXTENDS）还是普通类（用 IMPLEMENTS）
            classSymbol.interfaceNames
                .filter(String::isNotBlank)
                .distinct()
                .forEach { interfaceName ->
                    val interfaceSymbol = symbolIndex.findClassByTypeName(interfaceName, classSymbol.packageName) ?: return@forEach
                    if (interfaceSymbol.id == classSymbol.id) {
                        return@forEach
                    }
                    relations += relationFor(
                        role = if (classSymbol.kind == com.charmnight.linkgraph.jvm.index.JvmClassKind.INTERFACE) {
                            ClassDiagramRelationRole.EXTENDS
                        } else {
                            ClassDiagramRelationRole.IMPLEMENTS
                        },
                        kind = if (classSymbol.kind == com.charmnight.linkgraph.jvm.index.JvmClassKind.INTERFACE) {
                            JvmRelationKind.EXTENDS
                        } else {
                            JvmRelationKind.IMPLEMENTS
                        },
                        from = classSymbol,
                        to = interfaceSymbol,
                        confidence = JvmRelationConfidence.PROVEN,
                        source = relationSourceFor(classSymbol),
                        evidence = classSymbol.evidence(
                            if (classSymbol.kind == com.charmnight.linkgraph.jvm.index.JvmClassKind.INTERFACE) {
                                "extends ${interfaceSymbol.qualifiedName}"
                            } else {
                                "implements ${interfaceSymbol.qualifiedName}"
                            },
                        ),
                        memberName = classSymbol.simpleName,
                        usedInBody = false,
                        extraMetadata = extraMetadata,
                    )
                }
        }
        return relations
    }

    /**
     * 在已具备 PSI 的项目类范围内，结合符号索引与 PSI 提取字段、方法签名、方法体内局部变量等关系。
     * 通过 [JvmRelation.id] 去重，保证 PSI 与符号索引重复产生的关系不会叠加。
     */
    fun extractPsiTypeRelations(
        context: JvmResolutionContext,
        extraMetadata: Map<String, String> = emptyMap(),
    ): List<JvmRelation> {
        val relations = mutableListOf<JvmRelation>()
        // 以所有者为键建立字段索引，便于按类快速查找其拥有的全部字段符号
        val fieldsByOwnerClassName = context.symbolIndex.fieldsByQualifiedName.values
            .groupBy(JvmFieldSymbol::ownerClassName)
        projectClasses(context.symbolIndex).forEach { classSymbol ->
            val psiClass = context.findPsiClass(classSymbol) ?: return@forEach
            // 从 PSI 字段中提取关系，能拿到精确类型与位置信息
            psiClass.fields.forEach { field ->
                relations += fieldRelations(context.symbolIndex, classSymbol, field, extraMetadata)
            }
            // 索引中记录的字段（例如带有泛型或集合元素角色）可能比 PSI 解析更全，需补充
            fieldsByOwnerClassName[classSymbol.qualifiedName].orEmpty()
                .flatMap { field -> symbolFieldRelations(context.symbolIndex, classSymbol, field, extraMetadata) }
                .forEach(relations::add)
            // 方法签名与局部变量分别提供声明期与执行期的类型依赖
            psiClass.methods.forEach { method ->
                relations += methodSignatureRelations(context.symbolIndex, classSymbol, method, extraMetadata)
                relations += localTypeRelations(context.symbolIndex, classSymbol, method, extraMetadata)
            }
        }
        return relations.distinctBy(JvmRelation::id)
    }

    /**
     * 通过遍历方法体 PSI 抽取类与类之间的调用关系（包括方法调用与 new 表达式）。
     * 结果按 (源类, 目标类) 维度聚合，附带采样、签名、是否需要运行时分派等上下文信息。
     */
    fun extractMethodCallRelations(context: JvmResolutionContext): List<JvmRelation> {
        // 按源类到目标类聚合调用证据样本
        val calls = linkedMapOf<Pair<String, String>, MutableList<JvmEvidenceRef>>()
        // 按 (源类, 目标类) 聚合关系 metadata，多值用 LinkedHashSet 去重
        val metadata = linkedMapOf<Pair<String, String>, MutableMap<String, LinkedHashSet<String>>>()
        // 已解析的方法调用表达式计数，超出预算后停止解析以控制成本
        var resolvedCallExpressions = 0
        projectMethodsForBodyRelations(context.symbolIndex, context.budget).forEach { methodSymbol ->
            ProgressManager.checkCanceled()
            val psiMethod = context.findPsiMethod(methodSymbol) ?: return@forEach
            val sourceClass = context.symbolIndex.classByQualifiedName(methodSymbol.ownerClassName) ?: return@forEach
            val ownerPackageName = sourceClass.packageName
            psiMethod.accept(
                object : JavaRecursiveElementVisitor() {
                    // 处理形如 foo.bar() 的方法调用表达式
                    override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                        ProgressManager.checkCanceled()
                        if (resolvedCallExpressions >= context.budget.maxMethodCallExpressionsResolved) {
                            return
                        }
                        resolvedCallExpressions += 1
                        val targetMethod = expression.resolveMethod()
                        val resolvedTargetClass = targetMethod?.containingClass
                        // 优先使用 PSI 解析结果，解析失败时回退到启发式推断
                        val targetClass = resolvedTargetClass?.ownerClassSymbol(context.symbolIndex)
                            ?: targetClassFromUnresolvedCall(expression, context, ownerPackageName)
                        if (targetClass != null && targetClass.id != sourceClass.id) {
                            val key = sourceClass.id to targetClass.id
                            val methodName = targetMethod?.name ?: expression.methodExpression.referenceName.orEmpty()
                            calls.getOrPut(key) { mutableListOf() } +=
                                expression.evidence("calls $methodName", methodSymbol.source)
                            metadata.putValue(key, "call.sourceMethodSignatures", methodSymbol.signature)
                            metadata.putValue(key, MEMBER_NAME_KEY, methodName)
                            metadata.putValue(key, METHOD_NAME_KEY, methodSymbol.simpleName)
                            // 抽象或接口方法必须等到运行期才能确定真实实现，需要降低置信度
                            val runtimeDispatch = resolvedTargetClass?.needsRuntimeDispatch() == true || targetClass.needsRuntimeDispatch()
                            val confidence = if (runtimeDispatch) {
                                JvmRelationConfidence.RUNTIME_REQUIRED
                            } else {
                                JvmRelationConfidence.PROVEN
                            }
                            metadata.putValue(key, "call.confidences", confidence.name)
                            if (runtimeDispatch) {
                                metadata.putValue(
                                    key,
                                    "jvm.dispatch.kind",
                                    if (resolvedTargetClass?.isInterface == true || targetClass.kind == com.charmnight.linkgraph.jvm.index.JvmClassKind.INTERFACE) {
                                        "INTERFACE_DISPATCH"
                                    } else {
                                        "ABSTRACT_DISPATCH"
                                    },
                                )
                            }
                            val targetMethodSymbol = targetMethod?.ownerMethodSymbol(context.symbolIndex)
                                ?: targetMethodFromUnresolvedCall(expression, targetClass, context)
                            targetMethodSymbol?.signature?.let { signature ->
                                metadata.putValue(key, "call.targetMethodSignatures", signature)
                            }
                            if (sourceClass.testSource) {
                                metadata.putValue(key, "test.framework", "true")
                            }
                        }
                        super.visitMethodCallExpression(expression)
                    }

                    // 处理 new SomeClass() 形式的对象构造，视为对目标类型的强依赖
                    override fun visitNewExpression(expression: PsiNewExpression) {
                        val targetClass = expression.classReference?.resolve() as? PsiClass
                        // 解析失败时退化为按包名 + 简单名查找，覆盖内部类等场景
                        val targetSymbol = targetClass?.ownerClassSymbol(context.symbolIndex)
                            ?: expression.classReference?.referenceName
                                ?.let { simpleName -> context.symbolIndex.classByQualifiedName("$ownerPackageName.$simpleName") }
                        if (targetSymbol != null && targetSymbol.id != sourceClass.id) {
                            val key = sourceClass.id to targetSymbol.id
                            calls.getOrPut(key) { mutableListOf() } +=
                                expression.evidence("constructs ${targetSymbol.qualifiedName}", methodSymbol.source)
                            metadata.putValue(key, "call.sourceMethodSignatures", methodSymbol.signature)
                            metadata.putValue(key, MEMBER_NAME_KEY, targetSymbol.simpleName)
                            metadata.putValue(key, METHOD_NAME_KEY, methodSymbol.simpleName)
                            metadata.putValue(key, "call.confidences", JvmRelationConfidence.PROVEN.name)
                            if (sourceClass.testSource) {
                                metadata.putValue(key, "test.framework", "true")
                            }
                        }
                        super.visitNewExpression(expression)
                    }
                },
            )
        }
        return calls.mapNotNull { (key, samples) ->
            val from = context.symbolIndex.findSymbol(key.first) ?: return@mapNotNull null
            val to = context.symbolIndex.findSymbol(key.second) ?: return@mapNotNull null
            // 多值字段在最终输出时合并为分号分隔的字符串
            val relationMetadata = metadata[key].orEmpty()
                .mapValues { (_, values) -> values.sorted().joinToString(";") }
            // 多次调用可能记录了不同的置信度，取最严格（ordinal 最大）的作为关系整体置信度
            val confidence = relationMetadata["call.confidences"]
                ?.split(';')
                ?.mapNotNull { value -> runCatching { JvmRelationConfidence.valueOf(value) }.getOrNull() }
                ?.maxByOrNull(JvmRelationConfidence::ordinal)
                ?: JvmRelationConfidence.PROVEN
            JvmRelation(
                id = jvmRelationId(JvmRelationKind.CALLS, from.id, to.id),
                kind = JvmRelationKind.CALLS,
                fromSymbolId = from.id,
                toSymbolId = to.id,
                confidence = confidence,
                source = JvmRelationSource.PSI,
                count = samples.size,
                samples = samples.distinct().take(context.budget.maxSamplesPerRelation),
                metadata = mapOf("call.count" to samples.size.toString()) +
                    relationMetadata - "call.confidences" +
                    metadataFor(
                        role = ClassDiagramRelationRole.METHOD_CALL,
                        evidence = samples.firstOrNull()?.claim ?: "calls ${(to as? JvmClassSymbol)?.qualifiedName ?: to.qualifiedName}",
                        memberName = relationMetadata[MEMBER_NAME_KEY],
                        methodName = relationMetadata[METHOD_NAME_KEY],
                        usedInBody = true,
                    ).let { metadata ->
                        // 对嵌套类调用降低权重，避免在图中喧宾夺主
                        val targetClass = to as? JvmClassSymbol
                        if (targetClass?.isNestedClassName() == true) {
                            metadata + (WEIGHT_KEY to "42")
                        } else {
                            metadata
                        }
                    },
            )
        }
    }

    /**
     * 组装关系的 metadata 集合，统一覆盖角色、权重、展示标签与各类布尔标志，
     * 并允许调用方通过 [extraMetadata] 注入额外的扩展字段。
     */
    fun metadataFor(
        role: ClassDiagramRelationRole,
        evidence: String,
        memberName: String?,
        methodName: String? = null,
        usedInBody: Boolean,
        assignedToField: Boolean = false,
        heldByField: Boolean = false,
        extraMetadata: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val weight = roleWeight(role, usedInBody, assignedToField, heldByField)
        val displayLabel = displayLabelFor(role, memberName, methodName)
        return extraMetadata + mapOf(
            ROLE_KEY to role.name,
            ROLE_LABEL_KEY to role.label,
            LABEL_KEY to displayLabel,
            WEIGHT_KEY to weight.toString(),
            EVIDENCE_KEY to evidence,
            MEMBER_NAME_KEY to memberName.orEmpty(),
            METHOD_NAME_KEY to methodName.orEmpty(),
            USED_IN_BODY_KEY to usedInBody.toString(),
            FIELD_ASSIGNED_KEY to assignedToField.toString(),
            HELD_BY_FIELD_KEY to heldByField.toString(),
        )
    }

    /**
     * 根据角色与成员名拼接供 UI 展示的标签。
     * 不同角色对成员/方法的依赖程度不同：继承只显示角色，方法参数会带方法上下文。
     */
    private fun displayLabelFor(
        role: ClassDiagramRelationRole,
        memberName: String?,
        methodName: String?,
    ): String {
        val member = memberName?.trim().orEmpty()
        val method = methodName?.trim().orEmpty()
        return when (role) {
            // 继承与实现关系无需附加成员信息
            ClassDiagramRelationRole.EXTENDS,
            ClassDiagramRelationRole.IMPLEMENTS,
            -> role.label
            // 字段、构造参数、方法调用：以"角色 + 成员名"形式呈现
            ClassDiagramRelationRole.FIELD,
            ClassDiagramRelationRole.CONSTRUCTOR_PARAMETER,
            ClassDiagramRelationRole.METHOD_CALL,
            -> listOf(role.label, member).filter(String::isNotBlank).joinToString(" ")
            // 方法参数与局部类型需附带方法上下文，便于定位
            ClassDiagramRelationRole.METHOD_PARAMETER,
            ClassDiagramRelationRole.LOCAL_TYPE,
            -> if (method.isNotBlank() && member.isNotBlank()) {
                "${role.label} $method.$member"
            } else {
                listOf(role.label, member.ifBlank { method }).filter(String::isNotBlank).joinToString(" ")
            }
            // 返回值与异常仅关注方法签名
            ClassDiagramRelationRole.METHOD_RETURN,
            ClassDiagramRelationRole.THROWS,
            -> listOf(role.label, method.ifBlank { member }).filter(String::isNotBlank).joinToString(" ")
        }
    }

    /**
     * 根据角色与使用模式动态计算关系权重。
     * 字段持有、构造参数赋值给字段、参数在方法体中被使用等都会显著影响权重，
     * 用于在类图中突出真实结构性依赖。
     */
    fun roleWeight(
        role: ClassDiagramRelationRole,
        usedInBody: Boolean,
        assignedToField: Boolean = false,
        heldByField: Boolean = false,
    ): Int =
        when (role) {
            // 字段若被长期持有则使用基础权重，否则降为较弱关联
            ClassDiagramRelationRole.FIELD -> if (heldByField) role.baseWeight else 58
            // 构造参数赋给字段权重最高，方法体使用次之，仅声明最弱
            ClassDiagramRelationRole.CONSTRUCTOR_PARAMETER -> if (assignedToField) role.baseWeight else if (usedInBody) 52 else 12
            // 方法参数权重取决于是否在方法体内被实际使用
            ClassDiagramRelationRole.METHOD_PARAMETER -> if (usedInBody) 62 else 10
            else -> role.baseWeight
        }

    /**
     * 仅依赖符号索引而非 PSI 提取字段、方法签名（参数与返回值）所引入的类型关系。
     * 用于在不加载完整 PSI 的情况下也能快速构建一份较为完整的关系网络。
     */
    private fun extractSymbolTypeRelations(
        symbolIndex: JvmSymbolIndex,
        extraMetadata: Map<String, String>,
    ): List<JvmRelation> {
        val relations = mutableListOf<JvmRelation>()
        // 遍历所有字段符号，由索引中记录的字段类型角色推断与目标类型的关系
        symbolIndex.fieldsByQualifiedName.values.forEach { field ->
            val owner = symbolIndex.findClass(field.ownerClassName) ?: return@forEach
            relations += symbolFieldRelations(symbolIndex, owner, field, extraMetadata)
        }
        symbolIndex.methodsBySignature.values.forEach { method ->
            val owner = symbolIndex.findClass(method.ownerClassName) ?: return@forEach
            method.parameterTypes.forEachIndexed { index, parameterType ->
                val target = symbolIndex.findClassByTypeName(parameterType, owner.packageName) ?: return@forEachIndexed
                // 构造函数参数与普通方法参数使用不同的角色，权重逻辑会做区分
                val role = if (method.isConstructorOf(owner)) {
                    ClassDiagramRelationRole.CONSTRUCTOR_PARAMETER
                } else {
                    ClassDiagramRelationRole.METHOD_PARAMETER
                }
                relations += relationFor(
                    role = role,
                    kind = JvmRelationKind.USES_TYPE,
                    from = owner,
                    to = target,
                    confidence = JvmRelationConfidence.PROVEN,
                    source = JvmRelationSource.PROJECT_MODEL,
                    evidence = method.evidence("${role.label} ${method.simpleName} parameter ${index + 1}: ${target.qualifiedName}"),
                    memberName = "${method.simpleName}#${index + 1}",
                    methodName = method.simpleName,
                    usedInBody = false,
                    qualifier = "${role.name}:${method.signature}:$index:${target.qualifiedName}",
                    extraMetadata = extraMetadata,
                )
            }
            method.returnType
                ?.takeUnless { typeName -> typeName == "void" || method.isConstructorOf(owner) }
                ?.let { returnType ->
                    val target = symbolIndex.findClassByTypeName(returnType, owner.packageName) ?: return@let
                    relations += relationFor(
                        role = ClassDiagramRelationRole.METHOD_RETURN,
                        kind = JvmRelationKind.USES_TYPE,
                        from = owner,
                        to = target,
                        confidence = JvmRelationConfidence.PROVEN,
                        source = JvmRelationSource.PROJECT_MODEL,
                        evidence = method.evidence("return ${method.simpleName}: ${target.qualifiedName}"),
                        memberName = method.simpleName,
                        methodName = method.simpleName,
                        usedInBody = false,
                        qualifier = "${ClassDiagramRelationRole.METHOD_RETURN.name}:${method.signature}:${target.qualifiedName}",
                        extraMetadata = extraMetadata,
                    )
                }
        }
        return relations.filterNot { relation -> relation.fromSymbolId == relation.toSymbolId }
    }

    /**
     * 通过 PSI 字段节点提取字段类型关系。
     * PSI 字段被视为"被字段长期持有"，因此 heldByField 为 true，权重较高。
     */
    private fun fieldRelations(
        symbolIndex: JvmSymbolIndex,
        owner: JvmClassSymbol,
        field: PsiField,
        extraMetadata: Map<String, String>,
    ): List<JvmRelation> {
        val target = symbolIndex.classByTypeNear(field.type, owner.packageName) ?: return emptyList()
        if (target.id == owner.id) {
            return emptyList()
        }
        return listOf(
            relationFor(
                role = ClassDiagramRelationRole.FIELD,
                kind = JvmRelationKind.USES_TYPE,
                from = owner,
                to = target,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.PSI,
                evidence = field.evidence("field ${field.name}: ${target.qualifiedName}", owner.source),
                memberName = field.name,
                usedInBody = false,
                heldByField = true,
                qualifier = "${ClassDiagramRelationRole.FIELD.name}:${field.name}:${target.qualifiedName}",
                extraMetadata = extraMetadata,
            ),
        )
    }

    /**
     * 通过符号索引中的字段符号（可能含有泛型、集合元素等角色信息）提取字段类型关系。
     * 索引中的角色信息能区分直接持有、集合元素、Map 值等不同持有形式。
     */
    private fun symbolFieldRelations(
        symbolIndex: JvmSymbolIndex,
        owner: JvmClassSymbol,
        field: JvmFieldSymbol,
        extraMetadata: Map<String, String>,
    ): List<JvmRelation> =
        field.effectiveTypeReferences().mapNotNull { reference ->
            val target = symbolIndex.findClassByTypeName(reference.typeName, owner.packageName) ?: return@mapNotNull null
            if (target.id == owner.id) {
                return@mapNotNull null
            }
            // 角色是否落在 heldFieldRoles 决定该字段是否被视为长期持有
            val heldByField = reference.role in heldFieldRoles
            relationFor(
                role = ClassDiagramRelationRole.FIELD,
                kind = JvmRelationKind.USES_TYPE,
                from = owner,
                to = target,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.PROJECT_MODEL,
                evidence = field.source.evidence("field ${field.simpleName} ${reference.role.name.lowercase()} uses ${target.qualifiedName}"),
                memberName = field.simpleName,
                usedInBody = false,
                heldByField = heldByField,
                qualifier = "${ClassDiagramRelationRole.FIELD.name}:${field.qualifiedName}:${reference.role}:${target.qualifiedName}",
                extraMetadata = extraMetadata,
            )
        }

    /**
     * 提取方法签名级别的类型关系：形参类型、返回类型与抛出异常类型。
     * 同时检测构造函数参数是否被赋值给字段、是否被方法体使用，进而动态调整权重。
     */
    private fun methodSignatureRelations(
        symbolIndex: JvmSymbolIndex,
        owner: JvmClassSymbol,
        method: PsiMethod,
        extraMetadata: Map<String, String>,
    ): List<JvmRelation> {
        val relations = mutableListOf<JvmRelation>()
        // 方法名为空时退化为使用类简单名（通常出现在构造函数场景）
        val methodName = method.name.takeIf(String::isNotBlank) ?: owner.simpleName
        method.parameterList.parameters.forEachIndexed { index, parameter ->
            val target = symbolIndex.classByTypeNear(parameter.type, owner.packageName) ?: return@forEachIndexed
            if (target.id == owner.id) {
                return@forEachIndexed
            }
            // 通过方法体扫描判断参数是否被赋值给字段或在方法体中使用
            val assignedToField = method.isConstructor && parameterAssignedToOwnerField(method, parameter)
            val usedInBody = assignedToField || parameterUsedInBody(method, parameter)
            val role = if (method.isConstructor) {
                ClassDiagramRelationRole.CONSTRUCTOR_PARAMETER
            } else {
                ClassDiagramRelationRole.METHOD_PARAMETER
            }
            relations += relationFor(
                role = role,
                kind = JvmRelationKind.USES_TYPE,
                from = owner,
                to = target,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.PSI,
                evidence = parameter.evidence("${role.label} $methodName ${parameter.name}: ${target.qualifiedName}", owner.source),
                memberName = parameter.name ?: "$methodName#${index + 1}",
                methodName = methodName,
                usedInBody = usedInBody,
                assignedToField = assignedToField,
                qualifier = "${role.name}:${methodName}:${index}:${parameter.name}:${target.qualifiedName}",
                extraMetadata = extraMetadata,
            )
        }
        // 返回类型作为弱依赖（仅声明性），构造函数无返回值跳过
        symbolIndex.classByTypeNear(method.returnType, owner.packageName)
            ?.takeUnless { target -> target.id == owner.id || method.isConstructor }
            ?.let { target ->
                relations += relationFor(
                    role = ClassDiagramRelationRole.METHOD_RETURN,
                    kind = JvmRelationKind.USES_TYPE,
                    from = owner,
                    to = target,
                    confidence = JvmRelationConfidence.PROVEN,
                    source = JvmRelationSource.PSI,
                    evidence = method.returnTypeElement
                        ?.evidence("return $methodName: ${target.qualifiedName}", owner.source)
                        ?: method.evidence("return $methodName: ${target.qualifiedName}", owner.source),
                    memberName = methodName,
                    methodName = methodName,
                    usedInBody = false,
                    qualifier = "${ClassDiagramRelationRole.METHOD_RETURN.name}:$methodName:${target.qualifiedName}",
                    extraMetadata = extraMetadata,
                )
            }
        // throws 声明视为最弱的依赖信号
        method.throwsList.referencedTypes.forEach { thrownType ->
            val target = symbolIndex.classByTypeNear(thrownType, owner.packageName) ?: return@forEach
            if (target.id == owner.id) {
                return@forEach
            }
            relations += relationFor(
                role = ClassDiagramRelationRole.THROWS,
                kind = JvmRelationKind.USES_TYPE,
                from = owner,
                to = target,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.PSI,
                evidence = method.throwsList.evidence("throws ${target.qualifiedName}", owner.source),
                memberName = methodName,
                methodName = methodName,
                usedInBody = false,
                qualifier = "${ClassDiagramRelationRole.THROWS.name}:$methodName:${target.qualifiedName}",
                extraMetadata = extraMetadata,
            )
        }
        return relations
    }

    /**
     * 扫描方法体内声明的局部变量，提取其类型引入的弱依赖。
     * 局部类型通常反映方法内部的临时协作，权重低于字段与参数。
     */
    private fun localTypeRelations(
        symbolIndex: JvmSymbolIndex,
        owner: JvmClassSymbol,
        method: PsiMethod,
        extraMetadata: Map<String, String>,
    ): List<JvmRelation> {
        val relations = mutableListOf<JvmRelation>()
        val methodName = method.name.takeIf(String::isNotBlank) ?: owner.simpleName
        method.body?.accept(
            object : JavaRecursiveElementVisitor() {
                // 拦截方法体内声明的局部变量，将其类型作为关系来源
                override fun visitLocalVariable(variable: PsiLocalVariable) {
                    addLocalType(variable.type, variable.typeElement, variable.name)
                    super.visitLocalVariable(variable)
                }

                private fun addLocalType(
                    type: PsiType?,
                    evidenceElement: PsiElement?,
                    memberName: String?,
                ) {
                    val target = symbolIndex.classByTypeNear(type, owner.packageName) ?: return
                    if (target.id == owner.id) {
                        return
                    }
                    relations += relationFor(
                        role = ClassDiagramRelationRole.LOCAL_TYPE,
                        kind = JvmRelationKind.USES_TYPE,
                        from = owner,
                        to = target,
                        confidence = JvmRelationConfidence.PROVEN,
                        source = JvmRelationSource.PSI,
                        evidence = evidenceElement
                            ?.evidence("local $methodName ${memberName.orEmpty()}: ${target.qualifiedName}", owner.source)
                            ?: method.evidence("local $methodName ${memberName.orEmpty()}: ${target.qualifiedName}", owner.source),
                        memberName = memberName ?: methodName,
                        methodName = methodName,
                        usedInBody = true,
                        qualifier = "${ClassDiagramRelationRole.LOCAL_TYPE.name}:$methodName:${memberName.orEmpty()}:${target.qualifiedName}",
                        extraMetadata = extraMetadata,
                    )
                }
            },
        )
        return relations
    }

    /**
     * 关系构造的统一入口：根据传入的各项上下文产出最终的 [JvmRelation]，
     * metadata 由 [metadataFor] 统一组装，qualifier 用于在不同出现位置间去重。
     */
    private fun relationFor(
        role: ClassDiagramRelationRole,
        kind: JvmRelationKind,
        from: JvmSymbol,
        to: JvmSymbol,
        confidence: JvmRelationConfidence,
        source: JvmRelationSource,
        evidence: JvmEvidenceRef,
        memberName: String?,
        methodName: String? = null,
        usedInBody: Boolean,
        assignedToField: Boolean = false,
        heldByField: Boolean = false,
        qualifier: String = "${role.name}:${memberName.orEmpty()}:${to.qualifiedName}",
        extraMetadata: Map<String, String> = emptyMap(),
    ): JvmRelation =
        relation(
            kind = kind,
            from = from,
            to = to,
            confidence = confidence,
            source = source,
            evidence = evidence,
            qualifier = qualifier,
            metadata = metadataFor(
                role = role,
                evidence = evidence.claim,
                memberName = memberName,
                methodName = methodName,
                usedInBody = usedInBody,
                assignedToField = assignedToField,
                heldByField = heldByField,
                extraMetadata = extraMetadata,
            ),
        )

    /**
     * 根据符号来源将关系标注为用户附加 jar 或项目模型，便于在 UI 上区分依赖类型。
     */
    private fun relationSourceFor(classSymbol: JvmClassSymbol): JvmRelationSource =
        when (classSymbol.origin) {
            com.charmnight.linkgraph.source.SourceOrigin.USER_ATTACHED_CLASS_JAR,
            com.charmnight.linkgraph.source.SourceOrigin.USER_ATTACHED_SOURCE_JAR,
            -> JvmRelationSource.USER_ATTACHED_JAR
            else -> JvmRelationSource.PROJECT_MODEL
        }

    /**
     * 判断方法符号是否归属给定类作为构造函数。
     * 兼容 <init>、与类同名、返回类型等于自身全限定名三种 JVM 上的构造函数标识。
     */
    private fun JvmMethodSymbol.isConstructorOf(owner: JvmClassSymbol): Boolean =
        simpleName == "<init>" || simpleName == owner.simpleName || returnType == owner.qualifiedName

    /**
     * 通过遍历方法体的赋值表达式判断给定参数是否最终被赋值给字段。
     * 主要用于构造函数依赖注入的判定，赋给字段说明依赖是稳定持有的。
     */
    private fun parameterAssignedToOwnerField(method: PsiMethod, parameter: PsiParameter): Boolean {
        var assigned = false
        method.body?.accept(
            object : JavaRecursiveElementVisitor() {
                override fun visitAssignmentExpression(expression: PsiAssignmentExpression) {
                    val rhs = expression.rExpression as? PsiReferenceExpression
                    val lhs = expression.lExpression as? PsiReferenceExpression
                    // 右侧引用的是该参数，左侧是某个字段时认定为赋值给字段
                    if (rhs?.resolve() == parameter && lhs?.resolve() is PsiField) {
                        assigned = true
                    }
                    super.visitAssignmentExpression(expression)
                }
            },
        )
        return assigned
    }

    /**
     * 判断参数在方法体内是否被引用过（即被真正使用），用于调整方法参数关系的权重。
     */
    private fun parameterUsedInBody(method: PsiMethod, parameter: PsiParameter): Boolean {
        var used = false
        method.body?.accept(
            object : JavaRecursiveElementVisitor() {
                override fun visitReferenceExpression(expression: PsiReferenceExpression) {
                    if (expression.resolve() == parameter) {
                        used = true
                    }
                    super.visitReferenceExpression(expression)
                }
            },
        )
        return used
    }

    /**
     * 为 (源类, 目标类) 维度下的多值 metadata 添加条目，使用 LinkedHashSet 保留插入顺序并去重。
     */
    private fun MutableMap<Pair<String, String>, MutableMap<String, LinkedHashSet<String>>>.putValue(
        key: Pair<String, String>,
        name: String,
        value: String,
    ) {
        getOrPut(key) { linkedMapOf() }
            .getOrPut(name) { linkedSetOf() }
            .add(value)
    }

    /**
     * 处理 PSI 解析失败的方法调用：通过调用表达式的限定符类型或文本启发式推断目标类。
     * 通常覆盖链式调用、内部类、变量前缀等无法被直接 resolve 的场景。
     */
    private fun targetClassFromUnresolvedCall(
        expression: PsiMethodCallExpression,
        context: JvmResolutionContext,
        ownerPackageName: String,
    ): JvmClassSymbol? {
        // 优先使用限定符的实际类型，例如 someVar.foo() 中 someVar 的静态类型
        val qualifierType = expression.methodExpression.qualifierExpression?.type
        qualifierType?.let { type ->
            context.symbolIndex.classByTypeNear(type, ownerPackageName)?.let { return it }
        }
        // 退化方案：将限定符文本作为可能的内部类或字段名拼接出全限定名
        val qualifierText = expression.methodExpression.qualifierExpression?.text?.takeIf(String::isNotBlank)
            ?: return null
        val constructorOwner = expression.text.substringBefore(".$qualifierText", missingDelimiterValue = "")
        return context.symbolIndex.classByQualifiedName("$ownerPackageName.$constructorOwner")
    }

    /**
     * 在目标类已知时，根据方法名回查符号索引中的方法符号。
     * 用于补充未通过 PSI 解析到的目标方法签名信息。
     */
    private fun targetMethodFromUnresolvedCall(
        expression: PsiMethodCallExpression,
        targetClass: JvmClassSymbol,
        context: JvmResolutionContext,
    ): JvmMethodSymbol? {
        val methodName = expression.methodExpression.referenceName ?: return null
        // 通过 owner + 方法名过滤，只有唯一匹配时才认定方法签名可靠
        return context.symbolIndex.methodsBySignature.values
            .filter { method -> method.ownerClassName == targetClass.qualifiedName && method.simpleName == methodName }
            .singleOrNull()
    }

    /** 判断 PSI 类是否需要运行时分派（接口或抽象类），用于降低调用关系的置信度。 */
    private fun PsiClass.needsRuntimeDispatch(): Boolean =
        isInterface || hasModifierProperty(PsiModifier.ABSTRACT)

    /** 判断索引类是否需要运行时分派（接口或抽象类）。 */
    private fun JvmClassSymbol.needsRuntimeDispatch(): Boolean =
        kind == com.charmnight.linkgraph.jvm.index.JvmClassKind.INTERFACE || abstract

    /** 判断当前类的全限定名在去掉包前缀后仍包含点，常见于嵌套/内部类场景。 */
    private fun JvmClassSymbol.isNestedClassName(): Boolean {
        val localName = qualifiedName.removePrefix("$packageName.")
        return localName.contains('.')
    }

    // 被视为"长期持有"的字段类型角色集合：直接值、集合元素、Map 值都会作为字段持有的形式
    private val heldFieldRoles = setOf(
        JvmFieldTypeRole.DIRECT_VALUE,
        JvmFieldTypeRole.COLLECTION_ELEMENT,
        JvmFieldTypeRole.MAP_VALUE,
    )
}
