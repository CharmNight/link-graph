package com.charmnight.linkgraph.jvm.relation

/**
 * JVM 继承与包含关系解析器。
 *
 * 解析三类结构关系：
 * - MODULE_CONTAINS_PACKAGE：模块包含包
 * - PACKAGE_CONTAINS_CLASS：包包含类
 * - 类继承关系（委托给 [ClassDiagramRelationExtractor]）
 *
 * 这些关系共同构成项目结构的骨架，是架构图等视图的基础数据。
 */
class InheritanceRelationResolver : JvmRelationResolver {
    /** 解析器唯一标识。 */
    override val id: String = "jvm.inheritance"

    /**
     * 在 JVM 解析上下文中提取结构关系。
     *
     * @param context 提供 PSI 访问与符号索引
     * @return 提取到的关系列表
     */
    override fun resolve(context: JvmResolutionContext): List<JvmRelation> {
        val relations = mutableListOf<JvmRelation>()
        // 第一类：模块 → 包
        context.symbolIndex.modulesByName.values.forEach { module ->
            context.symbolIndex.packagesByName.values
                // 只处理属于当前模块的包
                .filter { pkg -> pkg.moduleName == module.qualifiedName }
                .forEach { pkg ->
                    relations += relation(
                        kind = JvmRelationKind.MODULE_CONTAINS_PACKAGE,
                        from = module,
                        to = pkg,
                        confidence = JvmRelationConfidence.PROVEN,
                        // 来源是项目模型（模块/包结构），属于权威数据
                        source = JvmRelationSource.PROJECT_MODEL,
                        evidence = pkg.source.evidence("module contains package"),
                    )
                }
        }
        // 第二类：类继承关系（委托给类图关系提取器）
        relations += ClassDiagramRelationExtractor.extractInheritanceRelations(context.symbolIndex)
        // 第三类：包 → 类
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
