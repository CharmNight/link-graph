package com.charmnight.linkgraph.jvm.index

import com.charmnight.linkgraph.source.SourceOrigin

/** JVM 符号的统一抽象，描述符号的标识、限定名、简单名、源位置与来源。 */
sealed interface JvmSymbol {
    val id: String
    val qualifiedName: String
    val simpleName: String
    val source: JvmSourceRef?
    val origin: SourceOrigin
}

/** JVM 类种类：普通类/接口/枚举/注解/记录/Object。 */
enum class JvmClassKind {
    CLASS,
    INTERFACE,
    ENUM,
    ANNOTATION,
    RECORD,
    OBJECT,
}

/** 类的 stereotype（基于框架约定识别的角色，如 Controller、Service 等）。 */
enum class JvmStereotype {
    CONTROLLER,
    SERVICE,
    REPOSITORY,
    COMPONENT,
    CONFIGURATION,
    RESOURCE,
    UNKNOWN,
}

/** 符号在源文件中的位置引用信息。 */
data class JvmSourceRef(
    val displayPath: String,
    val virtualFileUrl: String?,
    val startLine: Int?,
    val endLine: Int?,
    val decompiled: Boolean,
)

/** 模块符号。 */
data class JvmModuleSymbol(
    override val id: String,
    override val qualifiedName: String,
    override val simpleName: String,
    override val source: JvmSourceRef?,
    override val origin: SourceOrigin,
) : JvmSymbol

/** 包符号。 */
data class JvmPackageSymbol(
    override val id: String,
    override val qualifiedName: String,
    override val simpleName: String,
    val moduleName: String?,
    override val source: JvmSourceRef?,
    override val origin: SourceOrigin,
) : JvmSymbol

/** 类符号，包含种类、stereotype、源/库/JDK 标记、父类与接口、文档注释等。 */
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

/** 方法符号，包含所属类、签名、参数类型与返回类型。 */
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

/** 字段类型引用在字段类型表达式中的角色。 */
enum class JvmFieldTypeRole {
    DIRECT_VALUE,
    COLLECTION_ELEMENT,
    MAP_KEY,
    MAP_VALUE,
    WRAPPER_VALUE,
    TYPE_ARGUMENT,
    PROVIDER_RETURN,
    FUNCTION_PARAMETER,
    FUNCTION_RETURN,
}

/** 字段的类型引用项。 */
data class JvmFieldTypeReference(
    val typeName: String,
    val role: JvmFieldTypeRole,
)

/** 字段符号。 */
data class JvmFieldSymbol(
    override val id: String,
    override val qualifiedName: String,
    override val simpleName: String,
    val ownerClassName: String,
    val typeName: String?,
    override val source: JvmSourceRef?,
    override val origin: SourceOrigin,
    val typeReferences: List<JvmFieldTypeReference> = typeName
        ?.let { type -> listOf(JvmFieldTypeReference(type, JvmFieldTypeRole.DIRECT_VALUE)) }
        .orEmpty(),
) : JvmSymbol

/** 返回字段的有效类型引用，缺省时基于类型名构造一个直接引用。 */
fun JvmFieldSymbol.effectiveTypeReferences(): List<JvmFieldTypeReference> =
    typeReferences.takeIf(List<JvmFieldTypeReference>::isNotEmpty)
        ?: typeName?.let { type -> listOf(JvmFieldTypeReference(type, JvmFieldTypeRole.DIRECT_VALUE)) }
        ?: emptyList()

/** 资源种类：SPI 服务文件、XML、YAML、PROPERTIES、SQL、Markdown、MQ Topic 等。 */
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

/** 资源符号。 */
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

/** SPI 服务提供者文件条目。 */
data class JvmServiceProviderFile(
    val serviceInterfaceName: String,
    val providerClassNames: List<String>,
    val resource: JvmResourceSymbol,
    val origin: SourceOrigin,
)

/** SPI 服务提供者索引，按服务接口名聚合其实现文件。 */
class JvmServiceProviderIndex(
    val filesByInterfaceName: Map<String, List<JvmServiceProviderFile>> = emptyMap(),
) {
    /** 查询指定服务接口的所有提供者文件。 */
    fun providersFor(interfaceName: String): List<JvmServiceProviderFile> =
        filesByInterfaceName[interfaceName].orEmpty()
}
