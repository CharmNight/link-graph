package com.charmnight.linkgraph.jvm.index

import com.charmnight.linkgraph.source.SourceOrigin

sealed interface JvmSymbol {
    val id: String
    val qualifiedName: String
    val simpleName: String
    val source: JvmSourceRef?
    val origin: SourceOrigin
}

enum class JvmClassKind {
    CLASS,
    INTERFACE,
    ENUM,
    ANNOTATION,
    RECORD,
    OBJECT,
}

enum class JvmStereotype {
    CONTROLLER,
    SERVICE,
    REPOSITORY,
    COMPONENT,
    CONFIGURATION,
    RESOURCE,
    UNKNOWN,
}

data class JvmSourceRef(
    val displayPath: String,
    val virtualFileUrl: String?,
    val startLine: Int?,
    val endLine: Int?,
    val decompiled: Boolean,
)

data class JvmModuleSymbol(
    override val id: String,
    override val qualifiedName: String,
    override val simpleName: String,
    override val source: JvmSourceRef?,
    override val origin: SourceOrigin,
) : JvmSymbol

data class JvmPackageSymbol(
    override val id: String,
    override val qualifiedName: String,
    override val simpleName: String,
    val moduleName: String?,
    override val source: JvmSourceRef?,
    override val origin: SourceOrigin,
) : JvmSymbol

data class JvmClassSymbol(
    override val id: String,
    override val qualifiedName: String,
    override val simpleName: String,
    val packageName: String,
    val moduleName: String?,
    val kind: JvmClassKind,
    val stereotype: JvmStereotype = JvmStereotype.UNKNOWN,
    val external: Boolean = false,
    val library: Boolean = false,
    val jdk: Boolean = false,
    val testSource: Boolean = false,
    val abstract: Boolean = false,
    override val source: JvmSourceRef?,
    override val origin: SourceOrigin,
    val superClassName: String? = null,
    val interfaceNames: List<String> = emptyList(),
    val docComment: String? = null,
) : JvmSymbol

data class JvmMethodSymbol(
    override val id: String,
    override val qualifiedName: String,
    override val simpleName: String,
    val ownerClassName: String,
    val signature: String,
    val parameterTypes: List<String>,
    val returnType: String?,
    val abstract: Boolean = false,
    override val source: JvmSourceRef?,
    override val origin: SourceOrigin,
) : JvmSymbol

data class JvmFieldSymbol(
    override val id: String,
    override val qualifiedName: String,
    override val simpleName: String,
    val ownerClassName: String,
    val typeName: String?,
    override val source: JvmSourceRef?,
    override val origin: SourceOrigin,
) : JvmSymbol

enum class JvmResourceKind {
    SPI_SERVICE_FILE,
    XML,
    YAML,
    PROPERTIES,
    SQL,
    MARKDOWN,
    MQ_TOPIC,
    OTHER,
}

data class JvmResourceSymbol(
    override val id: String,
    val path: String,
    val kind: JvmResourceKind,
    override val source: JvmSourceRef?,
    override val origin: SourceOrigin,
) : JvmSymbol {
    override val qualifiedName: String = path
    override val simpleName: String = path.substringAfterLast('/')
}

data class JvmServiceProviderFile(
    val serviceInterfaceName: String,
    val providerClassNames: List<String>,
    val resource: JvmResourceSymbol,
    val origin: SourceOrigin,
)

class JvmServiceProviderIndex(
    val filesByInterfaceName: Map<String, List<JvmServiceProviderFile>> = emptyMap(),
) {
    fun providersFor(interfaceName: String): List<JvmServiceProviderFile> =
        filesByInterfaceName[interfaceName].orEmpty()
}
