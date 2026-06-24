package com.charmnight.linkgraph.jvm.relation

/** SPI 关系解析器：基于 META-INF/services 文件把 SPI 提供者关联到服务接口。 */
class SpiRelationResolver : JvmRelationResolver {
    /** 解析器唯一标识，用于在符号索引中区分该关系来源。 */
    override val id: String = "jvm.spi"

    /**
     * 解析 SPI 关系：遍历索引中所有 META-INF/services 提供者文件，
     * 把声明的每个提供者类与对应服务接口建立关系，并根据是否能静态证明
     * 实现关系来标注置信度。
     *
     * @param context JVM 解析上下文，提供符号索引与 PSI 查询能力
     * @return 解析得到的 SPI 关系列表
     */
    override fun resolve(context: JvmResolutionContext): List<JvmRelation> {
        val relations = mutableListOf<JvmRelation>()
        context.symbolIndex.serviceProviderIndex.filesByInterfaceName.values.flatten().forEach { providerFile ->
            val serviceInterface = context.symbolIndex.classByQualifiedName(providerFile.serviceInterfaceName) ?: return@forEach
            providerFile.providerClassNames.forEach { providerName ->
                val providerClass = context.symbolIndex.classByQualifiedName(providerName) ?: return@forEach
                val implementsService = providerImplementsService(
                    context = context,
                    providerClass = providerClass,
                    serviceInterface = serviceInterface,
                )
                relations += relation(
                    kind = JvmRelationKind.SPI_PROVIDES,
                    from = providerClass,
                    to = serviceInterface,
                    confidence = if (implementsService) {
                        JvmRelationConfidence.PROVEN
                    } else {
                        JvmRelationConfidence.AMBIGUOUS
                    },
                    source = JvmRelationSource.RESOURCE_FILE,
                    evidence = providerFile.resource.source.evidence("SPI provider $providerName for ${serviceInterface.qualifiedName}"),
                    qualifier = providerFile.resource.path,
                    metadata = mapOf(
                        "resource.path" to providerFile.resource.path,
                        "service.interface" to serviceInterface.qualifiedName,
                        "spi.provider.implementsService" to implementsService.toString(),
                        "relation.confidence.reason" to if (implementsService) {
                            "Provider class is assignable to service interface."
                        } else {
                            "Provider is declared in META-INF/services but assignability could not be proven statically."
                        },
                    ),
                )
            }
        }
        return relations
    }

    /**
     * 判断提供者类是否能赋值给服务接口：依次按符号相等、直接接口/父类、
     * 索引中的继承链进行快速判断，再回退到 PSI 进行更精确的继承校验，
     * 最终返回是否能够静态证明实现关系。
     */
    private fun providerImplementsService(
        context: JvmResolutionContext,
        providerClass: com.charmnight.linkgraph.jvm.index.JvmClassSymbol,
        serviceInterface: com.charmnight.linkgraph.jvm.index.JvmClassSymbol,
    ): Boolean {
        if (providerClass.id == serviceInterface.id) {
            return true
        }
        if (providerClass.interfaceNames.contains(serviceInterface.qualifiedName) ||
            providerClass.superClassName == serviceInterface.qualifiedName
        ) {
            return true
        }
        if (hasIndexedInheritancePath(context, providerClass.id, serviceInterface.id)) {
            return true
        }
        val providerPsi = context.findPsiClass(providerClass) ?: return false
        val servicePsi = context.findPsiClass(serviceInterface) ?: return false
        return providerPsi == servicePsi ||
            providerPsi.isInheritor(servicePsi, true) ||
            providerPsi.implementsListTypes.any { type ->
                com.charmnight.linkgraph.jvm.index.canonicalTypeText(type) == serviceInterface.qualifiedName
            } ||
            providerPsi.implementsList?.referenceElements.orEmpty().any { reference ->
                (reference.resolve() as? com.intellij.psi.PsiClass)?.qualifiedName == serviceInterface.qualifiedName ||
                    reference.qualifiedName == serviceInterface.qualifiedName ||
                    serviceInterface.qualifiedName.endsWith(".${reference.referenceName}")
            }
    }

    /**
     * 基于索引中的继承信息进行广度优先搜索：从提供者类出发，
     * 沿父类与接口逐层查找，判断能否在符号索引范围内到达目标服务接口，
     * 用作 PSI 不可用时的快速近似。
     */
    private fun hasIndexedInheritancePath(
        context: JvmResolutionContext,
        providerId: String,
        serviceId: String,
    ): Boolean {
        val visited = linkedSetOf<String>()
        val queue = java.util.ArrayDeque<String>()
        queue.add(providerId)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) {
                continue
            }
            context.symbolIndex.findSymbol(current)
                ?.let { symbol -> symbol as? com.charmnight.linkgraph.jvm.index.JvmClassSymbol }
                ?.let { classSymbol ->
                    sequenceOf(classSymbol.superClassName)
                        .plus(classSymbol.interfaceNames.asSequence())
                        .filterNotNull()
                        .mapNotNull(context.symbolIndex::classByQualifiedName)
                        .forEach { target ->
                            if (target.id == serviceId) {
                                return true
                            }
                            queue.add(target.id)
                        }
                }
        }
        return false
    }
}
