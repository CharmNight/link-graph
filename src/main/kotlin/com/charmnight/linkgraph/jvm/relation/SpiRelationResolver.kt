package com.charmnight.linkgraph.jvm.relation

class SpiRelationResolver : JvmRelationResolver {
    override val id: String = "jvm.spi"

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
