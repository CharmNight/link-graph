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

enum class ClassDiagramRelationRole(
    val label: String,
    val baseWeight: Int,
) {
    EXTENDS("extends", 100),
    IMPLEMENTS("implements", 95),
    FIELD("field", 90),
    CONSTRUCTOR_PARAMETER("ctor", 80),
    METHOD_CALL("call", 70),
    LOCAL_TYPE("local", 65),
    METHOD_RETURN("return", 60),
    METHOD_PARAMETER("param", 45),
    THROWS("throws", 25),
}

object ClassDiagramRelationExtractor {
    const val ROLE_KEY = "classDiagram.relation.role"
    const val WEIGHT_KEY = "classDiagram.relation.weight"
    const val EVIDENCE_KEY = "classDiagram.relation.evidence"
    const val MEMBER_NAME_KEY = "classDiagram.relation.memberName"
    const val METHOD_NAME_KEY = "classDiagram.relation.methodName"
    const val USED_IN_BODY_KEY = "classDiagram.relation.usedInBody"
    const val LABEL_KEY = "classDiagram.relation.label"
    const val ROLE_LABEL_KEY = "classDiagram.relation.roleLabel"
    const val HELD_BY_FIELD_KEY = "classDiagram.relation.heldByField"
    const val FIELD_ASSIGNED_KEY = "classDiagram.relation.assignedToField"

    fun extractStructureRelations(
        symbolIndex: JvmSymbolIndex,
        extraMetadata: Map<String, String> = emptyMap(),
    ): List<JvmRelation> =
        extractInheritanceRelations(symbolIndex, extraMetadata) +
            extractSymbolTypeRelations(symbolIndex, extraMetadata)

    fun extractInheritanceRelations(
        symbolIndex: JvmSymbolIndex,
        extraMetadata: Map<String, String> = emptyMap(),
    ): List<JvmRelation> {
        val relations = mutableListOf<JvmRelation>()
        symbolIndex.classesByQualifiedName.values.forEach { classSymbol ->
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

    fun extractPsiTypeRelations(
        context: JvmResolutionContext,
        extraMetadata: Map<String, String> = emptyMap(),
    ): List<JvmRelation> {
        val relations = mutableListOf<JvmRelation>()
        val fieldsByOwnerClassName = context.symbolIndex.fieldsByQualifiedName.values
            .groupBy(JvmFieldSymbol::ownerClassName)
        projectClasses(context.symbolIndex).forEach { classSymbol ->
            val psiClass = context.findPsiClass(classSymbol) ?: return@forEach
            psiClass.fields.forEach { field ->
                relations += fieldRelations(context.symbolIndex, classSymbol, field, extraMetadata)
            }
            fieldsByOwnerClassName[classSymbol.qualifiedName].orEmpty()
                .flatMap { field -> symbolFieldRelations(context.symbolIndex, classSymbol, field, extraMetadata) }
                .forEach(relations::add)
            psiClass.methods.forEach { method ->
                relations += methodSignatureRelations(context.symbolIndex, classSymbol, method, extraMetadata)
                relations += localTypeRelations(context.symbolIndex, classSymbol, method, extraMetadata)
            }
        }
        return relations.distinctBy(JvmRelation::id)
    }

    fun extractMethodCallRelations(context: JvmResolutionContext): List<JvmRelation> {
        val calls = linkedMapOf<Pair<String, String>, MutableList<JvmEvidenceRef>>()
        val metadata = linkedMapOf<Pair<String, String>, MutableMap<String, LinkedHashSet<String>>>()
        var resolvedCallExpressions = 0
        projectMethodsForBodyRelations(context.symbolIndex, context.budget).forEach { methodSymbol ->
            ProgressManager.checkCanceled()
            val psiMethod = context.findPsiMethod(methodSymbol) ?: return@forEach
            val sourceClass = context.symbolIndex.classByQualifiedName(methodSymbol.ownerClassName) ?: return@forEach
            val ownerPackageName = sourceClass.packageName
            psiMethod.accept(
                object : JavaRecursiveElementVisitor() {
                    override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                        ProgressManager.checkCanceled()
                        if (resolvedCallExpressions >= context.budget.maxMethodCallExpressionsResolved) {
                            return
                        }
                        resolvedCallExpressions += 1
                        val targetMethod = expression.resolveMethod()
                        val resolvedTargetClass = targetMethod?.containingClass
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

                    override fun visitNewExpression(expression: PsiNewExpression) {
                        val targetClass = expression.classReference?.resolve() as? PsiClass
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
            val relationMetadata = metadata[key].orEmpty()
                .mapValues { (_, values) -> values.sorted().joinToString(";") }
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

    private fun displayLabelFor(
        role: ClassDiagramRelationRole,
        memberName: String?,
        methodName: String?,
    ): String {
        val member = memberName?.trim().orEmpty()
        val method = methodName?.trim().orEmpty()
        return when (role) {
            ClassDiagramRelationRole.EXTENDS,
            ClassDiagramRelationRole.IMPLEMENTS,
            -> role.label
            ClassDiagramRelationRole.FIELD,
            ClassDiagramRelationRole.CONSTRUCTOR_PARAMETER,
            ClassDiagramRelationRole.METHOD_CALL,
            -> listOf(role.label, member).filter(String::isNotBlank).joinToString(" ")
            ClassDiagramRelationRole.METHOD_PARAMETER,
            ClassDiagramRelationRole.LOCAL_TYPE,
            -> if (method.isNotBlank() && member.isNotBlank()) {
                "${role.label} $method.$member"
            } else {
                listOf(role.label, member.ifBlank { method }).filter(String::isNotBlank).joinToString(" ")
            }
            ClassDiagramRelationRole.METHOD_RETURN,
            ClassDiagramRelationRole.THROWS,
            -> listOf(role.label, method.ifBlank { member }).filter(String::isNotBlank).joinToString(" ")
        }
    }

    fun roleWeight(
        role: ClassDiagramRelationRole,
        usedInBody: Boolean,
        assignedToField: Boolean = false,
        heldByField: Boolean = false,
    ): Int =
        when (role) {
            ClassDiagramRelationRole.FIELD -> if (heldByField) role.baseWeight else 58
            ClassDiagramRelationRole.CONSTRUCTOR_PARAMETER -> if (assignedToField) role.baseWeight else if (usedInBody) 52 else 12
            ClassDiagramRelationRole.METHOD_PARAMETER -> if (usedInBody) 62 else 10
            else -> role.baseWeight
        }

    private fun extractSymbolTypeRelations(
        symbolIndex: JvmSymbolIndex,
        extraMetadata: Map<String, String>,
    ): List<JvmRelation> {
        val relations = mutableListOf<JvmRelation>()
        symbolIndex.fieldsByQualifiedName.values.forEach { field ->
            val owner = symbolIndex.findClass(field.ownerClassName) ?: return@forEach
            relations += symbolFieldRelations(symbolIndex, owner, field, extraMetadata)
        }
        symbolIndex.methodsBySignature.values.forEach { method ->
            val owner = symbolIndex.findClass(method.ownerClassName) ?: return@forEach
            method.parameterTypes.forEachIndexed { index, parameterType ->
                val target = symbolIndex.findClassByTypeName(parameterType, owner.packageName) ?: return@forEachIndexed
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

    private fun methodSignatureRelations(
        symbolIndex: JvmSymbolIndex,
        owner: JvmClassSymbol,
        method: PsiMethod,
        extraMetadata: Map<String, String>,
    ): List<JvmRelation> {
        val relations = mutableListOf<JvmRelation>()
        val methodName = method.name.takeIf(String::isNotBlank) ?: owner.simpleName
        method.parameterList.parameters.forEachIndexed { index, parameter ->
            val target = symbolIndex.classByTypeNear(parameter.type, owner.packageName) ?: return@forEachIndexed
            if (target.id == owner.id) {
                return@forEachIndexed
            }
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

    private fun relationSourceFor(classSymbol: JvmClassSymbol): JvmRelationSource =
        when (classSymbol.origin) {
            com.charmnight.linkgraph.source.SourceOrigin.USER_ATTACHED_CLASS_JAR,
            com.charmnight.linkgraph.source.SourceOrigin.USER_ATTACHED_SOURCE_JAR,
            -> JvmRelationSource.USER_ATTACHED_JAR
            else -> JvmRelationSource.PROJECT_MODEL
        }

    private fun JvmMethodSymbol.isConstructorOf(owner: JvmClassSymbol): Boolean =
        simpleName == "<init>" || simpleName == owner.simpleName || returnType == owner.qualifiedName

    private fun parameterAssignedToOwnerField(method: PsiMethod, parameter: PsiParameter): Boolean {
        var assigned = false
        method.body?.accept(
            object : JavaRecursiveElementVisitor() {
                override fun visitAssignmentExpression(expression: PsiAssignmentExpression) {
                    val rhs = expression.rExpression as? PsiReferenceExpression
                    val lhs = expression.lExpression as? PsiReferenceExpression
                    if (rhs?.resolve() == parameter && lhs?.resolve() is PsiField) {
                        assigned = true
                    }
                    super.visitAssignmentExpression(expression)
                }
            },
        )
        return assigned
    }

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

    private fun MutableMap<Pair<String, String>, MutableMap<String, LinkedHashSet<String>>>.putValue(
        key: Pair<String, String>,
        name: String,
        value: String,
    ) {
        getOrPut(key) { linkedMapOf() }
            .getOrPut(name) { linkedSetOf() }
            .add(value)
    }

    private fun targetClassFromUnresolvedCall(
        expression: PsiMethodCallExpression,
        context: JvmResolutionContext,
        ownerPackageName: String,
    ): JvmClassSymbol? {
        val qualifierType = expression.methodExpression.qualifierExpression?.type
        qualifierType?.let { type ->
            context.symbolIndex.classByTypeNear(type, ownerPackageName)?.let { return it }
        }
        val qualifierText = expression.methodExpression.qualifierExpression?.text?.takeIf(String::isNotBlank)
            ?: return null
        val constructorOwner = expression.text.substringBefore(".$qualifierText", missingDelimiterValue = "")
        return context.symbolIndex.classByQualifiedName("$ownerPackageName.$constructorOwner")
    }

    private fun targetMethodFromUnresolvedCall(
        expression: PsiMethodCallExpression,
        targetClass: JvmClassSymbol,
        context: JvmResolutionContext,
    ): JvmMethodSymbol? {
        val methodName = expression.methodExpression.referenceName ?: return null
        return context.symbolIndex.methodsBySignature.values
            .filter { method -> method.ownerClassName == targetClass.qualifiedName && method.simpleName == methodName }
            .singleOrNull()
    }

    private fun PsiClass.needsRuntimeDispatch(): Boolean =
        isInterface || hasModifierProperty(PsiModifier.ABSTRACT)

    private fun JvmClassSymbol.needsRuntimeDispatch(): Boolean =
        kind == com.charmnight.linkgraph.jvm.index.JvmClassKind.INTERFACE || abstract

    private fun JvmClassSymbol.isNestedClassName(): Boolean {
        val localName = qualifiedName.removePrefix("$packageName.")
        return localName.contains('.')
    }

    private val heldFieldRoles = setOf(
        JvmFieldTypeRole.DIRECT_VALUE,
        JvmFieldTypeRole.COLLECTION_ELEMENT,
        JvmFieldTypeRole.MAP_VALUE,
    )
}
