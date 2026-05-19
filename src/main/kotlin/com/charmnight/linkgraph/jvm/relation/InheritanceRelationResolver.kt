package com.charmnight.linkgraph.jvm.relation

import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.source.SourceOrigin

class InheritanceRelationResolver : JvmRelationResolver {
    override val id: String = "jvm.inheritance"

    override fun resolve(context: JvmResolutionContext): List<JvmRelation> {
        val relations = mutableListOf<JvmRelation>()
        context.symbolIndex.modulesByName.values.forEach { module ->
            context.symbolIndex.packagesByName.values
                .filter { pkg -> pkg.moduleName == module.qualifiedName }
                .forEach { pkg ->
                    relations += relation(
                        kind = JvmRelationKind.MODULE_CONTAINS_PACKAGE,
                        from = module,
                        to = pkg,
                        confidence = JvmRelationConfidence.PROVEN,
                        source = JvmRelationSource.PROJECT_MODEL,
                        evidence = pkg.source.evidence("module contains package"),
                )
            }
        }
        context.symbolIndex.classesByQualifiedName.values
            .filter { symbol -> symbol.external || symbol.library }
            .forEach { classSymbol ->
                classSymbol.metadataTypeNames().forEach { (kind, typeName) ->
                    val target = context.symbolIndex.classByQualifiedName(typeName) ?: return@forEach
                    if (target.id == classSymbol.id) {
                        return@forEach
                    }
                    relations += relation(
                        kind = kind,
                        from = classSymbol,
                        to = target,
                        confidence = JvmRelationConfidence.PROVEN,
                        source = if (classSymbol.origin in ATTACHED_ORIGINS) {
                            JvmRelationSource.USER_ATTACHED_JAR
                        } else {
                            JvmRelationSource.PROJECT_MODEL
                        },
                        evidence = classSymbol.evidence(
                            if (kind == JvmRelationKind.IMPLEMENTS) {
                                "attached class implements ${target.qualifiedName}"
                            } else {
                                "attached class extends ${target.qualifiedName}"
                            },
                        ),
                        metadata = mapOf(
                            "inheritance.source" to "ATTACHED_CLASS_HEADER",
                        ),
                    )
                }
            }
        projectClasses(context.symbolIndex).forEach { classSymbol ->
            val packageSymbol = context.symbolIndex.packagesByName[classSymbol.packageName]
            if (packageSymbol != null) {
                relations += relation(
                    kind = JvmRelationKind.PACKAGE_CONTAINS_CLASS,
                    from = packageSymbol,
                    to = classSymbol,
                    confidence = JvmRelationConfidence.PROVEN,
                    source = JvmRelationSource.PROJECT_MODEL,
                    evidence = classSymbol.evidence("package contains class"),
                )
            }
            val psiClass = context.findPsiClass(classSymbol) ?: return@forEach
            val superClass = psiClass.superClass
            val superClassSymbol = context.symbolIndex.classByQualifiedName(superClass?.qualifiedName)
            if (superClassSymbol != null && superClassSymbol.qualifiedName != "java.lang.Object") {
                relations += relation(
                    kind = JvmRelationKind.EXTENDS,
                    from = classSymbol,
                    to = superClassSymbol,
                    confidence = JvmRelationConfidence.PROVEN,
                    source = JvmRelationSource.PSI,
                    evidence = (psiClass.extendsList ?: psiClass).evidence("extends ${superClassSymbol.qualifiedName}", classSymbol.source),
                )
            }
            val interfaceNames = if (psiClass.isInterface) {
                psiClass.extendsListTypes.mapNotNull { type -> com.charmnight.linkgraph.jvm.index.canonicalTypeText(type) } +
                    psiClass.extendsList.interfaceReferenceNames(psiClass)
            } else {
                psiClass.implementsListTypes.mapNotNull { type -> com.charmnight.linkgraph.jvm.index.canonicalTypeText(type) } +
                    psiClass.implementsList.interfaceReferenceNames(psiClass)
            }.ifEmpty {
                psiClass.interfaces.mapNotNull { psiInterface -> psiInterface.qualifiedName }
            }
            interfaceNames.distinct().forEach { interfaceName ->
                val interfaceSymbol = context.symbolIndex.classByQualifiedName(interfaceName) ?: return@forEach
                relations += relation(
                    kind = if (psiClass.isInterface) JvmRelationKind.EXTENDS else JvmRelationKind.IMPLEMENTS,
                    from = classSymbol,
                    to = interfaceSymbol,
                    confidence = JvmRelationConfidence.PROVEN,
                    source = JvmRelationSource.PSI,
                    evidence = (psiClass.implementsList ?: psiClass.extendsList ?: psiClass)
                        .evidence("implements ${interfaceSymbol.qualifiedName}", classSymbol.source),
                )
            }
        }
        return relations
    }

    private fun com.intellij.psi.PsiReferenceList?.interfaceReferenceNames(owner: com.intellij.psi.PsiClass): List<String> {
        this ?: return emptyList()
        val packageName = owner.qualifiedName?.substringBeforeLast('.', missingDelimiterValue = "").orEmpty()
        return referenceElements.mapNotNull { reference ->
            (reference.resolve() as? com.intellij.psi.PsiClass)?.qualifiedName
                ?: reference.qualifiedName?.takeIf { name -> name.contains('.') }
                ?: reference.referenceName?.takeIf(String::isNotBlank)?.let { simpleName ->
                    "$packageName.$simpleName".takeIf { packageName.isNotBlank() }
                }
        }
    }

    private fun JvmClassSymbol.metadataTypeNames(): List<Pair<JvmRelationKind, String>> {
        return buildList {
            superClassName?.takeIf(String::isNotBlank)?.let { superName ->
                add(JvmRelationKind.EXTENDS to superName)
            }
            interfaceNames
                .filter(String::isNotBlank)
                .distinct()
                .forEach { interfaceName -> add(JvmRelationKind.IMPLEMENTS to interfaceName) }
        }
    }

    private companion object {
        private val ATTACHED_ORIGINS = setOf(
            SourceOrigin.USER_ATTACHED_CLASS_JAR,
            SourceOrigin.USER_ATTACHED_SOURCE_JAR,
        )
    }
}
