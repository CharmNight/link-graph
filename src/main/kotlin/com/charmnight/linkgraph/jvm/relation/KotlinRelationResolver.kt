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

class KotlinRelationResolver : JvmRelationResolver {
    override val id: String = "jvm.kotlin-psi"

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

    private fun KtClassOrObject.secondaryConstructorsForInjection(): List<KtSecondaryConstructor> =
        when (this) {
            is KtClass -> secondaryConstructors.filter { constructor ->
                constructor.annotationEntries.any { entry -> entry.shortName?.asString() in injectionAnnotationNames }
            }
            else -> emptyList()
        }

    private fun KtClassOrObject.jvmClassSymbol(index: JvmSymbolIndex): JvmClassSymbol? =
        toLightClass()?.qualifiedName?.let(index.classesByQualifiedName::get)

    private fun KtCallExpression.constructorTargetClass(index: JvmSymbolIndex): JvmClassSymbol? =
        calleeExpression
            ?.collectDescendantsOfType<KtConstructorCalleeExpression>()
            ?.firstOrNull()
            ?.typeReference
            ?.resolveReferencedClass(index)

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

    private fun lightMethodSignature(element: PsiElement): String? =
        when (element) {
            is KtNamedFunction,
            is KtConstructor<*>,
            is KtProperty,
            is KtCallableDeclaration,
            -> element.toLightMethods().firstOrNull()?.let(::methodSignature)
            else -> null
        }

    private fun JvmMethodSymbol.ownerClass(index: JvmSymbolIndex): JvmClassSymbol? =
        index.classByQualifiedName(ownerClassName)

    private companion object {
        private val injectionAnnotationNames = setOf("Autowired", "Inject", "Resource")
    }
}
