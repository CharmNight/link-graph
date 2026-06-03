package com.charmnight.linkgraph.jvm.relation

import com.charmnight.linkgraph.jvm.index.JvmResourceKind
import com.charmnight.linkgraph.jvm.index.JvmResourceSymbol
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiLiteralExpression

class SpringConfigBindingRelationResolver : JvmRelationResolver {
    override val id: String = "jvm.spring-config-binding"

    override fun resolve(context: JvmResolutionContext): List<JvmRelation> {
        val configResources = context.symbolIndex.resourcesByPath.values
            .filter(::isSpringApplicationConfigResource)
        if (configResources.isEmpty()) {
            return emptyList()
        }
        val relations = mutableListOf<JvmRelation>()
        projectClasses(context.symbolIndex).forEach { classSymbol ->
            val psiClass = context.findPsiClass(classSymbol) ?: return@forEach
            psiClass.annotations
                .mapNotNull { annotation -> configurationPropertiesPrefix(annotation)?.let { prefix -> annotation to prefix } }
                .forEach { (annotation, prefix) ->
                    configResources
                        .filter { resource -> resourceDeclaresPrefix(context, resource, prefix) }
                        .forEach { resource ->
                            relations += JvmRelation(
                                id = jvmRelationId(JvmRelationKind.RESOURCE_BINDS, classSymbol.id, resource.id, prefix),
                                kind = JvmRelationKind.RESOURCE_BINDS,
                                fromSymbolId = classSymbol.id,
                                toSymbolId = resource.id,
                                confidence = JvmRelationConfidence.PROVEN,
                                source = JvmRelationSource.FRAMEWORK_RULE,
                                samples = listOf(annotation.evidence("Spring configuration properties bind prefix $prefix", classSymbol.source)),
                                metadata = mapOf(
                                    "framework" to "spring-boot",
                                    "config.prefix" to prefix,
                                    "config.resourcePath" to resource.path,
                                    "spring.configurationClass" to classSymbol.qualifiedName,
                                    "spring.annotation" to annotation.qualifiedName.orEmpty(),
                                    "spring.annotationEvidence" to "ANNOTATION_LITERAL",
                                ),
                            )
                        }
                }
        }
        return relations
    }

    private fun configurationPropertiesPrefix(annotation: PsiAnnotation): String? {
        val qualifiedName = annotation.qualifiedName.orEmpty()
        val simpleName = qualifiedName.substringAfterLast('.').ifBlank { annotation.nameReferenceElement?.referenceName.orEmpty() }
        if (simpleName != "ConfigurationProperties" &&
            qualifiedName != "org.springframework.boot.context.properties.ConfigurationProperties"
        ) {
            return null
        }
        return listOf("prefix", "value")
            .firstNotNullOfOrNull { name -> annotation.annotationStringValue(name) }
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }

    private fun PsiAnnotation.annotationStringValue(name: String): String? =
        findDeclaredAttributeValue(name)?.annotationString()
            ?: if (name == "value") {
                findDeclaredAttributeValue(null)?.annotationString()
            } else {
                null
            }

    private fun PsiAnnotationMemberValue.annotationString(): String? {
        if (this is PsiArrayInitializerMemberValue) {
            return initializers.firstOrNull()?.annotationString()
        }
        if (this is PsiLiteralExpression) {
            return value as? String
        }
        return null
    }

    private fun isSpringApplicationConfigResource(resource: JvmResourceSymbol): Boolean =
        resource.kind in setOf(JvmResourceKind.YAML, JvmResourceKind.PROPERTIES) &&
            resource.path.substringAfterLast('/') in setOf(
                "application.yml",
                "application.yaml",
                "application.properties",
            )

    private fun resourceDeclaresPrefix(
        context: JvmResolutionContext,
        resource: JvmResourceSymbol,
        prefix: String,
    ): Boolean {
        val text = context.sourceResolver.readResourceByPath(resource.path)?.text ?: return false
        return when (resource.kind) {
            JvmResourceKind.YAML -> Regex("""(?m)^\s*${Regex.escape(prefix)}\s*:""").containsMatchIn(text)
            JvmResourceKind.PROPERTIES -> Regex("""(?m)^\s*${Regex.escape(prefix)}[.\[]""").containsMatchIn(text)
            else -> false
        }
    }
}
