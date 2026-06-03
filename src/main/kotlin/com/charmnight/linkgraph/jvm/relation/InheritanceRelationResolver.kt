package com.charmnight.linkgraph.jvm.relation

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
        relations += ClassDiagramRelationExtractor.extractInheritanceRelations(context.symbolIndex)
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
        }
        return relations
    }
}
