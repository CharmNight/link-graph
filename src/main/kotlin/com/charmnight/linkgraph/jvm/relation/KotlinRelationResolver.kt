package com.charmnight.linkgraph.jvm.relation

import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmMethodSymbol
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex
import com.charmnight.linkgraph.jvm.index.methodSignature
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.asJava.toLightMethods
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtConstructorCalleeExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/** Kotlin 关系解析器：基于 Kotlin PSI 识别类继承、构造、调用等关系。 */
class KotlinRelationResolver : JvmRelationResolver {
    /** 解析器在注册表中的唯一标识，使用基于 PSI 的 Kotlin 解析路径作为标识。 */
    override val id: String = "jvm.kotlin-psi"

    /**
     * 扫描缓存中的 Kotlin 文件，逐个顶层类收集调用、类型引用、构造注入三类关系。
     *
     * @param context 解析过程中需要复用的索引、预算等运行时上下文。
     * @return 本次扫描得到的关系列表。
     */
    override fun resolve(context: JvmResolutionContext): List<JvmRelation> {
        val relations = mutableListOf<JvmRelation>()
        context.cachedKotlinFiles().forEach { file ->
            file.collectDescendantsOfType<KtClassOrObject>()
                .filter { ktClass -> ktClass.parent is KtFile }
                .forEach { ktClass ->
                    val sourceClass = ktClass.jvmClassSymbol(context.symbolIndex) ?: return@forEach
                    relations += resolveCalls(context, ktClass, sourceClass)
                    relations += resolveTypeUsages(context, ktClass, sourceClass)
                    relations += resolveConstructorInjection(context, ktClass, sourceClass)
                }
        }
        return relations
    }

    /**
     * 识别当前类内部对其它类的方法调用关系。
     * 通过 PSI 调用表达式解析被调方法，进而推断出所属类，并将同类证据聚合后输出。
     */
    private fun resolveCalls(
        context: JvmResolutionContext,
        ktClass: KtClassOrObject,
        sourceClass: JvmClassSymbol,
    ): List<JvmRelation> {
        val calls = linkedMapOf<String, MutableList<JvmEvidenceRef>>()
        ktClass.collectDescendantsOfType<KtCallExpression>().forEach { call ->
            val targetClass = call.calleeExpression
                ?.mainReference
                ?.resolve()
                ?.let(::lightMethodSignature)
                ?.let(context.symbolIndex.methodsBySignature::get)
                ?.ownerClass(context.symbolIndex)
                ?: call.constructorTargetClass(context.symbolIndex)
                ?: return@forEach
            if (targetClass.id != sourceClass.id) {
                calls.getOrPut(targetClass.id) { mutableListOf() } +=
                    call.evidence("kotlin calls ${targetClass.qualifiedName}", sourceClass.source)
            }
        }
        return calls.mapNotNull { (targetId, samples) ->
            val target = context.symbolIndex.findSymbol(targetId) ?: return@mapNotNull null
            relation(
                kind = JvmRelationKind.CALLS,
                from = sourceClass,
                to = target,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.PSI,
                evidence = samples.first(),
                count = samples.size,
                metadata = mapOf("kotlin.call.count" to samples.size.toString()),
            ).copy(samples = samples.distinct().take(context.budget.maxSamplesPerRelation))
        }
    }

    /**
     * 识别当前类对其它类的类型引用关系（如变量声明、返回值、泛型实参等）。
     * 把每个类型引用作为证据聚合，得到"使用类型"关系。
     */
    private fun resolveTypeUsages(
        context: JvmResolutionContext,
        ktClass: KtClassOrObject,
        sourceClass: JvmClassSymbol,
    ): List<JvmRelation> {
        val targets = linkedMapOf<String, MutableList<JvmEvidenceRef>>()
        ktClass.collectDescendantsOfType<KtTypeReference>().forEach { typeReference ->
            val targetClass = typeReference.resolveReferencedClass(context.symbolIndex) ?: return@forEach
            if (targetClass.id != sourceClass.id) {
                targets.getOrPut(targetClass.id) { mutableListOf() } +=
                    typeReference.evidence("kotlin uses type ${targetClass.qualifiedName}", sourceClass.source)
            }
        }
        return targets.mapNotNull { (targetId, samples) ->
            val target = context.symbolIndex.findSymbol(targetId) ?: return@mapNotNull null
            relation(
                kind = JvmRelationKind.USES_TYPE,
                from = sourceClass,
                to = target,
                confidence = JvmRelationConfidence.PROVEN,
                source = JvmRelationSource.PSI,
                evidence = samples.first(),
                count = samples.size,
                metadata = mapOf("kotlin.usage.count" to samples.size.toString()),
            ).copy(samples = samples.distinct().take(context.budget.maxSamplesPerRelation))
        }
    }

    /**
     * 识别通过构造函数注入的依赖关系。
     * 根据注入相关注解（如 Autowired/Inject/Resource）或唯一构造函数的启发式规则，
     * 把构造参数类型视为被注入的依赖项。
     */
    private fun resolveConstructorInjection(
        context: JvmResolutionContext,
        ktClass: KtClassOrObject,
        sourceClass: JvmClassSymbol,
    ): List<JvmRelation> {
        val constructorParameters = ktClass.primaryConstructorParametersForInjection() +
            ktClass.secondaryConstructorsForInjection().flatMap(KtSecondaryConstructor::getValueParameters)
        val targets = constructorParameters
            .mapNotNull { parameter ->
                val targetClass = parameter.typeReference?.resolveReferencedClass(context.symbolIndex) ?: return@mapNotNull null
                if (targetClass.id == sourceClass.id) return@mapNotNull null
                targetClass to parameter
            }
            .groupBy({ it.first.id }, { it })
        return targets.mapNotNull { (targetId, grouped) ->
            val target = context.symbolIndex.findSymbol(targetId) ?: return@mapNotNull null
            relation(
                kind = JvmRelationKind.INJECTS,
                from = sourceClass,
                to = target,
                confidence = JvmRelationConfidence.RULE_INFERRED,
                source = JvmRelationSource.FRAMEWORK_RULE,
                evidence = grouped.first().second.evidence("kotlin constructor injects ${target.qualifiedName}", sourceClass.source),
                count = grouped.size,
                metadata = mapOf("kotlin.injection" to "constructor"),
            )
        }
    }

    /**
     * 判断当前类是否应将主构造参数视为可注入依赖。
     * 当主构造带有注入注解，或类只有这一个构造函数时，返回主构造参数列表。
     */
    private fun KtClassOrObject.primaryConstructorParametersForInjection(): List<KtParameter> {
        if (this !is KtClass) {
            return emptyList()
        }
        val primary = primaryConstructor ?: return emptyList()
        val constructors = listOfNotNull(primary) + secondaryConstructors
        if (primary.annotationEntries.any { entry -> entry.shortName?.asString() in injectionAnnotationNames } || constructors.size == 1) {
            return primary.valueParameters
        }
        return emptyList()
    }

    /** 筛选次级构造中带有注入注解的构造函数，作为依赖注入的候选来源。 */
    private fun KtClassOrObject.secondaryConstructorsForInjection(): List<KtSecondaryConstructor> =
        when (this) {
            is KtClass -> secondaryConstructors.filter { constructor ->
                constructor.annotationEntries.any { entry -> entry.shortName?.asString() in injectionAnnotationNames }
            }
            else -> emptyList()
        }

    /** 把 Kotlin 类/对象转换为对应的 JVM 符号，便于在索引中查找。 */
    private fun KtClassOrObject.jvmClassSymbol(index: JvmSymbolIndex): JvmClassSymbol? =
        toLightClass()?.qualifiedName?.let(index.classesByQualifiedName::get)

    /** 解析构造调用表达式中被调构造函数对应的类，用于补足调用关系的构造场景。 */
    private fun KtCallExpression.constructorTargetClass(index: JvmSymbolIndex): JvmClassSymbol? =
        calleeExpression
            ?.collectDescendantsOfType<KtConstructorCalleeExpression>()
            ?.firstOrNull()
            ?.typeReference
            ?.resolveReferencedClass(index)

    /** 解析类型引用指向的类。优先使用 PSI 引用解析结果，缺失时回退到简单类名匹配。 */
    private fun KtTypeReference.resolveReferencedClass(index: JvmSymbolIndex): JvmClassSymbol? {
        val userType = typeElement as? KtUserType ?: return null
        val resolved = userType.referenceExpression
            ?.mainReference
            ?.resolve()
        val qualifiedName = when (resolved) {
            is KtClassOrObject -> resolved.toLightClass()?.qualifiedName
            else -> null
        } ?: userType.referencedName?.takeIf { name -> name.isNotBlank() }
        return index.classesByQualifiedName[qualifiedName]
    }

    /** 把函数/构造/属性等 PSI 元素转换为方法签名，便于在方法索引中查询。 */
    private fun lightMethodSignature(element: PsiElement): String? =
        when (element) {
            is KtNamedFunction,
            is KtConstructor<*>,
            is KtProperty,
            is KtCallableDeclaration,
            -> element.toLightMethods().firstOrNull()?.let(::methodSignature)
            else -> null
        }

    /** 通过方法的所属类名查回它所在的类符号。 */
    private fun JvmMethodSymbol.ownerClass(index: JvmSymbolIndex): JvmClassSymbol? =
        index.classByQualifiedName(ownerClassName)

    private companion object {
        /** 视为依赖注入标记的注解短名集合。 */
        private val injectionAnnotationNames = setOf("Autowired", "Inject", "Resource")
    }
}
