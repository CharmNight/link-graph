package com.charmnight.linkgraph.architecture.memory

import com.charmnight.linkgraph.jvm.index.JvmClassKind
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmFieldTypeReference
import com.charmnight.linkgraph.jvm.index.JvmFieldTypeRole
import com.charmnight.linkgraph.jvm.index.JvmFieldSymbol
import com.charmnight.linkgraph.jvm.index.JvmMethodSymbol
import com.charmnight.linkgraph.jvm.index.JvmModuleSymbol
import com.charmnight.linkgraph.jvm.index.JvmPackageSymbol
import com.charmnight.linkgraph.jvm.index.JvmResourceKind
import com.charmnight.linkgraph.jvm.index.JvmResourceSymbol
import com.charmnight.linkgraph.jvm.index.JvmServiceProviderFile
import com.charmnight.linkgraph.jvm.index.JvmServiceProviderIndex
import com.charmnight.linkgraph.jvm.index.JvmSourceRef
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex
import com.charmnight.linkgraph.jvm.index.JvmStereotype
import com.charmnight.linkgraph.jvm.index.stableJvmId
import com.charmnight.linkgraph.jvm.relation.JvmRelation
import com.charmnight.linkgraph.jvm.relation.JvmRelationConfidence
import com.charmnight.linkgraph.jvm.relation.JvmRelationIndex
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind
import com.charmnight.linkgraph.jvm.relation.JvmRelationSource
import com.charmnight.linkgraph.source.SourceOrigin

data class ArchitectureIndexMergedFragment(
    val symbolIndex: JvmSymbolIndex,
    val relationIndex: JvmRelationIndex,
)

class ArchitectureIndexFragmentMerger {
    fun merge(fragments: List<ArchitectureIndexSliceFragment>): ArchitectureIndexMergedFragment {
        val classes = fragments
            .flatMap(ArchitectureIndexSliceFragment::symbols)
            .filter { symbol -> symbol.kind == "CLASS" }
            .mapNotNull { symbol ->
                val origin = sourceOriginOrNull(symbol.origin) ?: return@mapNotNull null
                val classKind = symbol.classKind
                    ?.let { value -> enumValueOrNull<JvmClassKind>(value) ?: return@mapNotNull null }
                    ?: JvmClassKind.CLASS
                symbol.qualifiedName to JvmClassSymbol(
                    id = symbol.id,
                    qualifiedName = symbol.qualifiedName,
                    simpleName = symbol.simpleName,
                    packageName = symbol.packageName ?: symbol.qualifiedName.substringBeforeLast('.', missingDelimiterValue = ""),
                    moduleName = symbol.moduleName,
                    kind = classKind,
                    stereotype = symbol.stereotype?.let { value -> runCatching { JvmStereotype.valueOf(value) }.getOrNull() }
                        ?: JvmStereotype.UNKNOWN,
                    external = symbol.external,
                    library = symbol.library,
                    jdk = symbol.jdk,
                    testSource = symbol.testSource,
                    abstract = symbol.abstract,
                    source = sourceRef(symbol),
                    origin = origin,
                    superClassName = symbol.superClassName,
                    interfaceNames = symbol.interfaceNames,
                    docComment = symbol.docComment,
                )
            }
            .distinctBy { (qualifiedName, _) -> qualifiedName }
            .toMap()
        val methods = fragments
            .flatMap(ArchitectureIndexSliceFragment::symbols)
            .filter { symbol -> symbol.kind == "METHOD" }
            .mapNotNull { symbol ->
                val origin = sourceOriginOrNull(symbol.origin) ?: return@mapNotNull null
                val signature = symbol.signature ?: symbol.qualifiedName
                signature to JvmMethodSymbol(
                    id = symbol.id,
                    qualifiedName = symbol.qualifiedName,
                    simpleName = symbol.simpleName,
                    ownerClassName = symbol.ownerClassName ?: symbol.qualifiedName.substringBeforeLast('.', missingDelimiterValue = ""),
                    signature = signature,
                    parameterTypes = symbol.parameterTypes,
                    returnType = symbol.returnType,
                    abstract = symbol.abstract,
                    source = sourceRef(symbol),
                    origin = origin,
                )
            }
            .distinctBy { (signature, _) -> signature }
            .toMap()
        val fields = fragments
            .flatMap(ArchitectureIndexSliceFragment::symbols)
            .filter { symbol -> symbol.kind == "FIELD" }
            .mapNotNull { symbol ->
                val origin = sourceOriginOrNull(symbol.origin) ?: return@mapNotNull null
                val typeReferences = fieldTypeReferences(symbol)
                val typeName = symbol.typeName.takeUnless { symbol.typeReferences.isNotEmpty() && typeReferences.isEmpty() }
                symbol.qualifiedName to JvmFieldSymbol(
                    id = symbol.id,
                    qualifiedName = symbol.qualifiedName,
                    simpleName = symbol.simpleName,
                    ownerClassName = symbol.ownerClassName ?: symbol.qualifiedName.substringBeforeLast('.', missingDelimiterValue = ""),
                    typeName = typeName,
                    source = sourceRef(symbol),
                    origin = origin,
                    typeReferences = typeReferences,
                )
            }
            .distinctBy { (qualifiedName, _) -> qualifiedName }
            .toMap()
        val resources = fragments
            .flatMap(ArchitectureIndexSliceFragment::resources)
            .mapNotNull { resource ->
                val origin = sourceOriginOrNull(resource.origin) ?: return@mapNotNull null
                val path = resource.path.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val kind = resourceKindOrNull(resource.kind) ?: return@mapNotNull null
                path to JvmResourceSymbol(
                    id = resource.id,
                    path = path,
                    kind = kind,
                    source = sourceRef(resource),
                    origin = origin,
                )
            }
            .distinctBy { (path, _) -> path }
            .toMap()
        val serviceProviderFiles = fragments
            .flatMap(ArchitectureIndexSliceFragment::serviceProviders)
            .mapNotNull { serviceProvider ->
                val origin = sourceOriginOrNull(serviceProvider.origin) ?: return@mapNotNull null
                val serviceInterfaceName = serviceProvider.serviceInterfaceName.trim().takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val providerClassNames = serviceProvider.providerClassNames
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .distinct()
                    .takeIf(List<String>::isNotEmpty)
                    ?: return@mapNotNull null
                val resourcePath = serviceProvider.resourcePath.trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
                JvmServiceProviderFile(
                    serviceInterfaceName = serviceInterfaceName,
                    providerClassNames = providerClassNames,
                    resource = resources[resourcePath] ?: JvmResourceSymbol(
                        id = serviceProvider.resourceId,
                        path = resourcePath,
                        kind = resourceKindOrNull(serviceProvider.resourceKind) ?: return@mapNotNull null,
                        source = sourceRef(resourcePath),
                        origin = origin,
                    ),
                    origin = origin,
                )
            }
            .groupBy(JvmServiceProviderFile::serviceInterfaceName)
            .mapValues { (_, files) ->
                files
                    .groupBy { file -> file.resource.path }
                    .values
                    .map { resourceFiles ->
                        val firstFile = resourceFiles.first()
                        firstFile.copy(
                            providerClassNames = resourceFiles
                                .flatMap(JvmServiceProviderFile::providerClassNames)
                                .distinct(),
                        )
                    }
            }
        val modules = classes.values
            .mapNotNull(JvmClassSymbol::moduleName)
            .distinct()
            .associateWith { moduleName ->
                JvmModuleSymbol(
                    id = stableJvmId("module", moduleName),
                    qualifiedName = moduleName,
                    simpleName = moduleName.substringAfterLast('.'),
                    source = null,
                    origin = SourceOrigin.PROJECT_SOURCE,
                )
            }
        val packageNames = (
            classes.values.map(JvmClassSymbol::packageName) +
                resources.values.map { resource -> resource.path.substringBeforeLast('/', missingDelimiterValue = "") }
            )
            .filter(String::isNotBlank)
            .distinct()
        val packages = packageNames.associateWith { packageName ->
            JvmPackageSymbol(
                id = stableJvmId("package", packageName),
                qualifiedName = packageName,
                simpleName = packageName.substringAfterLast('.').ifBlank { packageName.substringAfterLast('/') },
                moduleName = classes.values.firstOrNull { symbol -> symbol.packageName == packageName }?.moduleName,
                source = null,
                origin = SourceOrigin.PROJECT_SOURCE,
            )
        }
        val relations = fragments
            .flatMap(ArchitectureIndexSliceFragment::relations)
            .distinctBy(RelationSliceFragment::id)
            .mapNotNull { relation ->
                JvmRelation(
                    id = relation.id,
                    kind = enumValueOrNull<JvmRelationKind>(relation.kind) ?: return@mapNotNull null,
                    fromSymbolId = relation.fromSymbolId,
                    toSymbolId = relation.toSymbolId,
                    confidence = enumValueOrNull<JvmRelationConfidence>(relation.confidence) ?: return@mapNotNull null,
                    source = enumValueOrNull<JvmRelationSource>(relation.source) ?: return@mapNotNull null,
                    count = relation.count,
                    metadata = relation.metadata,
                )
            }
        return ArchitectureIndexMergedFragment(
            symbolIndex = JvmSymbolIndex(
                modulesByName = modules,
                packagesByName = packages,
                classesByQualifiedName = classes,
                methodsBySignature = methods,
                fieldsByQualifiedName = fields,
                resourcesByPath = resources,
                serviceProviderIndex = JvmServiceProviderIndex(serviceProviderFiles),
            ),
            relationIndex = JvmRelationIndex(relations),
        )
    }

    private fun sourceRef(symbol: SymbolSliceFragment): JvmSourceRef? =
        symbol.sourcePath?.takeIf(String::isNotBlank)?.let { path ->
            JvmSourceRef(
                displayPath = path,
                virtualFileUrl = symbol.sourceVirtualFileUrl,
                startLine = symbol.sourceStartLine,
                endLine = symbol.sourceEndLine,
                decompiled = symbol.sourceDecompiled,
            )
        }

    private fun sourceRef(resource: ResourceSliceFragment): JvmSourceRef =
        JvmSourceRef(
            displayPath = resource.path,
            virtualFileUrl = resource.sourceVirtualFileUrl,
            startLine = resource.sourceStartLine,
            endLine = resource.sourceEndLine,
            decompiled = resource.sourceDecompiled,
        )

    private fun sourceRef(path: String): JvmSourceRef? =
        path.takeIf(String::isNotBlank)?.let { displayPath ->
            JvmSourceRef(
                displayPath = displayPath,
                virtualFileUrl = null,
                startLine = null,
                endLine = null,
                decompiled = false,
            )
        }

    private fun fieldTypeReferences(symbol: SymbolSliceFragment): List<JvmFieldTypeReference> =
        if (symbol.typeReferences.isNotEmpty()) {
            symbol.typeReferences.mapNotNull { reference ->
                JvmFieldTypeReference(
                    typeName = reference.typeName,
                    role = enumValueOrNull<JvmFieldTypeRole>(reference.role) ?: return@mapNotNull null,
                )
            }
        } else {
            symbol.typeName
                ?.let { typeName -> listOf(JvmFieldTypeReference(typeName, JvmFieldTypeRole.DIRECT_VALUE)) }
                .orEmpty()
        }

    private fun resourceKindOrNull(value: String): JvmResourceKind? =
        enumValueOrNull<JvmResourceKind>(value)

    private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
        runCatching { enumValueOf<T>(value) }.getOrNull()

    private fun sourceOriginOrNull(value: String): SourceOrigin? =
        enumValueOrNull<SourceOrigin>(value)
}
