package com.charmnight.linkgraph.jvm.relation

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiModifierListOwner

/**
 * JVM 注解关系解析器。
 *
 * 扫描项目中所有类、字段、方法、方法参数上的注解，
 * 把它们转换为 ANNOTATED_BY 关系（owner → annotationClass）。
 *
 * 这种关系让架构图能展示"哪些类被某个注解标记过"，
 * 例如所有 @Service 类、所有 @RestController 类等。
 */
class AnnotationRelationResolver : JvmRelationResolver {
    /** 解析器唯一标识。 */
    override val id: String = "jvm.annotation"

    /**
     * 在 JVM 解析上下文中提取注解关系。
     *
     * 遍历每个类的字段、方法、方法参数上的注解，
     * 把每个注解转换为一条关系（如果注解类型在索引中存在）。
     */
    override fun resolve(context: JvmResolutionContext): List<JvmRelation> {
        val relations = mutableListOf<JvmRelation>()
        projectClasses(context.symbolIndex).forEach { classSymbol ->
            val psiClass = context.findPsiClass(classSymbol) ?: return@forEach
            // 类级注解
            psiClass.annotations.forEach { annotation ->
                annotationRelation(context, classSymbol, annotation)?.let(relations::add)
            }
            // 字段注解
            psiClass.fields.forEach { field ->
                val fieldSymbol = context.symbolIndex.findField("${classSymbol.qualifiedName}.${field.name}") ?: return@forEach
                field.annotations.forEach { annotation ->
                    annotationRelation(context, fieldSymbol, annotation)?.let(relations::add)
                }
            }
            // 方法注解 + 方法参数注解
            psiClass.methods.forEach { method ->
                val methodSymbol = method.ownerMethodSymbol(context.symbolIndex) ?: return@forEach
                method.annotations.forEach { annotation ->
                    annotationRelation(context, methodSymbol, annotation)?.let(relations::add)
                }
                method.parameterList.parameters.forEach { parameter ->
                    parameter.annotations.forEach { annotation ->
                        // 参数注解额外带上参数名作为限定符，便于区分
                        annotationRelation(context, methodSymbol, annotation, qualifier = parameter.name)?.let(relations::add)
                    }
                }
            }
        }
        return relations
    }

    /**
     * 把单个注解转换为关系。
     *
     * @param context 解析上下文
     * @param owner 注解所属的符号
     * @param annotation 注解 PSI
     * @param qualifier 可选限定符（例如参数名）
     * @return 关系对象；注解类型不在索引中或指向自身时返回 null
     */
    private fun annotationRelation(
        context: JvmResolutionContext,
        owner: com.charmnight.linkgraph.jvm.index.JvmSymbol,
        annotation: PsiAnnotation,
        qualifier: String? = null,
    ): JvmRelation? {
        val annotationClass = annotation.resolveAnnotationType()
            ?.ownerClassSymbol(context.symbolIndex)
            ?: return null
        // 注解指向自身时跳过（无意义）
        if (annotationClass.id == owner.id) {
            return null
        }
        return relation(
            kind = JvmRelationKind.ANNOTATED_BY,
            from = owner,
            to = annotationClass,
            confidence = JvmRelationConfidence.PROVEN,
            source = JvmRelationSource.PSI,
            evidence = annotation.evidence("annotated by ${annotationClass.qualifiedName}", owner.source),
            // 限定符由注解全限定名 + 可选参数名组合，便于后续按注解+参数筛选
            qualifier = listOfNotNull(annotation.qualifiedName, qualifier).joinToString(":"),
            metadata = buildMap {
                put("annotation.qualifiedName", annotationClass.qualifiedName)
                if (owner is com.charmnight.linkgraph.jvm.index.JvmMethodSymbol) {
                    put("annotation.ownerMethod", owner.signature)
                }
                if (owner is com.charmnight.linkgraph.jvm.index.JvmFieldSymbol) {
                    put("annotation.ownerField", owner.qualifiedName)
                }
                qualifier?.let { put("annotation.parameterName", it) }
            },
        )
    }
}

/** PsiModifierListOwner 上的注解数组便捷访问器；无 modifierList 时返回空数组。 */
private val PsiModifierListOwner.annotations: Array<PsiAnnotation>
    get() = modifierList?.annotations ?: emptyArray()
