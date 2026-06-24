package com.charmnight.linkgraph.architecture

import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmStereotype
import com.charmnight.linkgraph.source.SourceOrigin

/**
 * 架构投影目标的种类，用于区分架构图中的节点属于哪一类边界。
 */
enum class ArchitectureProjectionTargetKind {
    /** 项目内的服务边界，通常对应一组同源业务能力。 */
    PROJECT_SERVICE_BOUNDARY,
    /** 项目内的功能组件分组。 */
    PROJECT_COMPONENT,
    /** 项目按 Java/Kotlin 包聚合的分组。 */
    PROJECT_PACKAGE,
    /** 项目按分层（API、Service、Data 等）聚合的分组。 */
    PROJECT_LAYER,
    /** 外部资源（HTTP/MQ 等）聚合的分组。 */
    PROJECT_RESOURCE,
    /** 外部依赖库的分组。 */
    EXTERNAL_LIBRARY_GROUP,
    /** JDK 自带类的分组。 */
    JDK_GROUP,
}

/**
 * 架构图中的投影目标节点，包含图节点标识、限定名、展示标题以及种类。
 */
data class ArchitectureProjectionTarget(
    /** 图节点唯一标识。 */
    val nodeId: String,
    /** 边界或分组的限定名。 */
    val qualifiedName: String,
    /** 用于界面展示的标题文本。 */
    val title: String,
    /** 投影目标种类。 */
    val kind: ArchitectureProjectionTargetKind,
)

/**
 * 架构边界分类器，负责把 JVM 类符号映射成不同种类的架构投影目标。
 * 主要依据包路径、 stereotype、来源信息判断当前类归属于服务边界、组件、外部库或 JDK。
 */
class ArchitectureBoundaryClassifier {
    /**
     * 给定类符号与全部源码类集合，决定该类在架构图上的投影节点。
     * 优先级为 JDK → 外部库 → 服务边界 → 组件分组。
     */
    fun projectNodeForClass(
        cls: JvmClassSymbol,
        sourceClasses: Collection<JvmClassSymbol> = listOf(cls),
        serviceBoundaryNames: Map<String, String> = trustedServiceBoundaryNames(sourceClasses),
    ): ArchitectureProjectionTarget =
        when {
            cls.isJdkClass() -> jdkGroupFor(cls)
            cls.isExternalLibraryClass() -> dependencyGroupFor(cls)
            else -> serviceBoundaryFor(cls, serviceBoundaryNames) ?: componentGroupFor(cls, sourceClasses)
        }

    /**
     * 计算类所属的服务边界节点，若不属于任何可信服务边界则返回 null。
     * 内部基于源码集合重新推导服务边界名称。
     */
    fun serviceBoundaryFor(
        cls: JvmClassSymbol,
        sourceClasses: Collection<JvmClassSymbol> = listOf(cls),
    ): ArchitectureProjectionTarget? = serviceBoundaryFor(cls, trustedServiceBoundaryNames(sourceClasses))

    /**
     * 基于预先计算的服务边界映射判断该类是否落入服务边界。
     */
    fun serviceBoundaryFor(
        cls: JvmClassSymbol,
        serviceBoundaryNames: Map<String, String>,
    ): ArchitectureProjectionTarget? {
        if (!cls.isTrustedProjectSourceClass()) {
            return null
        }
        val boundaryName = serviceBoundaryNames[cls.qualifiedName] ?: return null
        return ArchitectureProjectionTarget(
            nodeId = serviceNodeId(boundaryName),
            qualifiedName = boundaryName,
            title = boundaryName.substringAfterLast('.').ifBlank { boundaryName },
            kind = ArchitectureProjectionTargetKind.PROJECT_SERVICE_BOUNDARY,
        )
    }

    /**
     * 将类聚合到其所属的功能组件分组，返回对应的投影目标节点。
     */
    fun componentGroupFor(
        cls: JvmClassSymbol,
        sourceClasses: Collection<JvmClassSymbol> = listOf(cls),
    ): ArchitectureProjectionTarget {
        val componentName = componentNameFor(cls, sourceClasses)
        return ArchitectureProjectionTarget(
            nodeId = componentNodeId(componentName),
            qualifiedName = componentName,
            title = componentName.substringAfterLast('.').ifBlank { componentName },
            kind = ArchitectureProjectionTargetKind.PROJECT_COMPONENT,
        )
    }

    /**
     * 批量计算所有项目内可信源码类对应的组件投影目标，返回按类限定名索引的映射。
     */
    fun componentTargetsForProjectClasses(
        sourceClasses: Collection<JvmClassSymbol>,
    ): Map<String, ArchitectureProjectionTarget> {
        val context = ComponentNamingContext(sourceClasses.filter { cls -> cls.isTrustedProjectSourceClass() })
        return context.projectClasses.associate { cls ->
            val componentName = context.componentNameFor(cls)
            cls.qualifiedName to ArchitectureProjectionTarget(
                nodeId = componentNodeId(componentName),
                qualifiedName = componentName,
                title = componentName.substringAfterLast('.').ifBlank { componentName },
                kind = ArchitectureProjectionTargetKind.PROJECT_COMPONENT,
            )
        }
    }

    /** 按类所在 Java/Kotlin 包进行聚合，得到包级投影节点。 */
    fun packageGroupFor(cls: JvmClassSymbol): ArchitectureProjectionTarget {
        val packageName = cls.packageName.ifBlank { "(default)" }
        return ArchitectureProjectionTarget(
            nodeId = "arch:package:$packageName",
            qualifiedName = packageName,
            title = packageName,
            kind = ArchitectureProjectionTargetKind.PROJECT_PACKAGE,
        )
    }

    /** 根据资源路径（HTTP/MQ/文件路径）生成资源分组投影节点。 */
    fun resourceGroupFor(
        path: String,
        title: String = path.substringAfterLast('/'),
    ): ArchitectureProjectionTarget =
        ArchitectureProjectionTarget(
            nodeId = "arch:resource:${resourceGroupName(path)}",
            qualifiedName = resourceGroupName(path),
            title = resourceGroupTitle(path, title),
            kind = ArchitectureProjectionTargetKind.PROJECT_RESOURCE,
        )

    /** 按分层（API/SERVICE/DATA 等）对类进行归类，返回分层投影节点。 */
    fun layerFor(cls: JvmClassSymbol): ArchitectureProjectionTarget {
        val layer = layerNameFor(cls)
        return ArchitectureProjectionTarget(
            nodeId = "arch:layer:${layer.lowercase()}",
            qualifiedName = layer,
            title = layer,
            kind = ArchitectureProjectionTargetKind.PROJECT_LAYER,
        )
    }

    /** 把外部依赖类聚合到其所属的库分组并返回投影节点。 */
    fun dependencyGroupFor(cls: JvmClassSymbol): ArchitectureProjectionTarget {
        val groupName = externalGroupName(cls)
        return ArchitectureProjectionTarget(
            nodeId = "arch:library:$groupName",
            qualifiedName = groupName,
            title = groupName,
            kind = ArchitectureProjectionTargetKind.EXTERNAL_LIBRARY_GROUP,
        )
    }

    /** JDK 类的固定聚合节点。 */
    fun jdkGroupFor(cls: JvmClassSymbol): ArchitectureProjectionTarget =
        ArchitectureProjectionTarget(
            nodeId = "arch:jdk:JDK",
            qualifiedName = "JDK",
            title = "JDK",
            kind = ArchitectureProjectionTargetKind.JDK_GROUP,
        )

    /** 判断给定类是否为可信的项目内源码类。 */
    fun isProjectSourceClass(cls: JvmClassSymbol): Boolean = cls.isTrustedProjectSourceClass()

    /** 仅当类位于项目源码、非测试、非外部/库/JDK 时视为可信项目源码。 */
    private fun JvmClassSymbol.isTrustedProjectSourceClass(): Boolean =
        !external &&
            !library &&
            !jdk &&
            !testSource &&
            origin == SourceOrigin.PROJECT_SOURCE

    /** 判断类是否属于 JDK（基于标记、来源或限定名前缀）。 */
    private fun JvmClassSymbol.isJdkClass(): Boolean =
        jdk || origin in setOf(SourceOrigin.JDK_SOURCE, SourceOrigin.JDK_CLASS) || qualifiedName.isJdkQualifiedName()

    /** 判断类是否来自外部依赖库。 */
    private fun JvmClassSymbol.isExternalLibraryClass(): Boolean =
        external ||
            library ||
            origin in setOf(
                SourceOrigin.LIBRARY_SOURCE_JAR,
                SourceOrigin.LIBRARY_CLASS_JAR,
                SourceOrigin.USER_ATTACHED_SOURCE_JAR,
                SourceOrigin.USER_ATTACHED_CLASS_JAR,
                SourceOrigin.DECOMPILED,
            )

    /** 根据模块名或包名推导外部依赖库的分组名称。 */
    private fun externalGroupName(cls: JvmClassSymbol): String {
        cls.moduleName
            ?.takeIf { moduleName -> moduleName.startsWith("library:") || moduleName.startsWith("attached:") }
            ?.let { return it }
        val parts = cls.packageName.split('.').filter(String::isNotBlank)
        return when {
            parts.size >= 2 -> parts.take(2).joinToString(".")
            parts.size == 1 -> parts.first()
            else -> cls.qualifiedName.substringBefore('.', missingDelimiterValue = cls.qualifiedName)
        }
    }

    /** 通过限定名前缀（java./javax./jdk./sun./com.sun.）判断是否为 JDK 类。 */
    private fun String.isJdkQualifiedName(): Boolean =
        startsWith("java.") ||
            startsWith("javax.") ||
            startsWith("jdk.") ||
            startsWith("sun.") ||
            startsWith("com.sun.")

    companion object {
        /** 包路径中用于识别分层的关键字集合。 */
        private val layerMarkers = setOf(
            "controller",
            "web",
            "api",
            "service",
            "domain",
            "repository",
            "dao",
            "mapper",
            "infra",
            "infrastructure",
            "config",
        )
        /** 组织根段集合，用于判断包前缀是否仅是组织命名（com/org/net 等）。 */
        private val organizationRootMarkers = setOf(
            "com",
            "org",
            "net",
            "io",
            "dev",
            "app",
            "application",
            "project",
        )
        /** 形成服务边界的最小类证据数量。 */
        private const val MIN_SERVICE_EVIDENCE_CLASSES = 2

        /** 根据边界名构造服务节点 ID。 */
        fun serviceNodeId(boundaryName: String): String = "arch:service:$boundaryName"

        /** 根据组件名构造组件节点 ID。 */
        fun componentNodeId(componentName: String): String = "arch:component:$componentName"

        /** 依据包路径与 stereotype 推导分层名（API/SERVICE/DATA/CONFIG 等）。 */
        fun layerNameFor(cls: JvmClassSymbol): String {
            val packageText = cls.packageName.lowercase()
            val simpleText = cls.simpleName.lowercase()
            return when {
                cls.stereotype == JvmStereotype.CONTROLLER || ".controller" in packageText || ".web" in packageText || ".api" in packageText -> "API"
                cls.stereotype == JvmStereotype.SERVICE || ".service" in packageText || simpleText.endsWith("service") -> "SERVICE"
                cls.stereotype == JvmStereotype.REPOSITORY || ".repository" in packageText || ".dao" in packageText || ".mapper" in packageText -> "DATA"
                cls.stereotype == JvmStereotype.CONFIGURATION || ".config" in packageText -> "CONFIG"
                ".domain" in packageText || ".model" in packageText -> "DOMAIN"
                ".infra" in packageText || ".infrastructure" in packageText -> "INFRA"
                else -> "CORE"
            }
        }

        /** 推导所有可信的服务边界映射：类限定名 -> 边界名。 */
        fun trustedServiceBoundaryNames(sourceClasses: Collection<JvmClassSymbol>): Map<String, String> {
            val projectClasses = sourceClasses.filter { cls -> cls.isTrustedProjectSourceForBoundary() }
            val projectRootParts = commonRootParts(projectClasses.map(JvmClassSymbol::packageName).filter(String::isNotBlank))
            val candidates = projectClasses.mapNotNull { cls -> serviceBoundaryCandidateFor(cls)?.let { it to cls } }
            val classesByBoundary = candidates.groupBy({ (boundary, _) -> boundary }, { (_, cls) -> cls })
            val trustedBoundaries = classesByBoundary
                .filter { (boundaryName, classes) ->
                    !boundaryName.isOrganizationRoot() &&
                        !boundaryName.isProjectRootBoundary(projectRootParts) &&
                        !boundaryName.isMultiRootProductNamespace(projectClasses) &&
                        classes.hasServiceEvidence()
                }
                .keys
            return candidates
                .asSequence()
                .filter { (boundaryName, _) -> boundaryName in trustedBoundaries }
                .associate { (_, cls) -> cls.qualifiedName to serviceBoundaryCandidateFor(cls)!! }
        }

        /** 服务边界推导时使用的可信项目源码判断（与外部使用的判断保持一致）。 */
        private fun JvmClassSymbol.isTrustedProjectSourceForBoundary(): Boolean =
            !external &&
                !library &&
                !jdk &&
                !testSource &&
                origin == SourceOrigin.PROJECT_SOURCE

        /** 根据类所在包路径中的分层关键字提取服务边界候选名。 */
        private fun serviceBoundaryCandidateFor(cls: JvmClassSymbol): String? {
            val parts = cls.packageName.split('.').filter(String::isNotBlank)
            val markerIndex = parts.indexOfFirst { part -> part.lowercase() in layerMarkers }
            if (markerIndex <= 1) {
                return null
            }
            return parts.take(markerIndex).joinToString(".").takeIf(String::isNotBlank)
        }

        /** 判断一组类是否具备足够证据构成一个服务边界（多样化分层/stereotype）。 */
        private fun List<JvmClassSymbol>.hasServiceEvidence(): Boolean {
            if (size < MIN_SERVICE_EVIDENCE_CLASSES) {
                return false
            }
            val layers = mapTo(linkedSetOf(), ArchitectureBoundaryClassifier.Companion::layerNameFor)
            val stereotypes = mapTo(linkedSetOf(), JvmClassSymbol::stereotype)
            val layerEvidenceCount = layers.count { layer -> layer != "CORE" }
            val stereotypeEvidenceCount = stereotypes.count { stereotype -> stereotype != JvmStereotype.UNKNOWN }
            return layers.size >= 2 ||
                layerEvidenceCount >= 2 ||
                stereotypeEvidenceCount >= 2 ||
                any { cls -> cls.stereotype in setOf(JvmStereotype.CONTROLLER, JvmStereotype.SERVICE) } &&
                    any { cls -> cls.stereotype in setOf(JvmStereotype.REPOSITORY, JvmStereotype.CONFIGURATION) }
        }

        /** 判断包名是否仅停留在组织根（如 com.example 这种短前缀）。 */
        private fun String.isOrganizationRoot(): Boolean {
            val parts = split('.').filter(String::isNotBlank)
            if (parts.size < 3) {
                return true
            }
            val last = parts.last().lowercase()
            if (last in organizationRootMarkers) {
                return true
            }
            return false
        }

        /** 判断包名是否与项目根前缀完全相同（不应作为子边界）。 */
        private fun String.isProjectRootBoundary(projectRootParts: List<String>): Boolean {
            if (projectRootParts.isEmpty()) {
                return false
            }
            return split('.').filter(String::isNotBlank) == projectRootParts
        }

        /** 判断边界名是否属于多根产品命名空间（看起来是公共祖先但缺少实际类）。 */
        private fun String.isMultiRootProductNamespace(projectClasses: List<JvmClassSymbol>): Boolean {
            val boundaryParts = split('.').filter(String::isNotBlank)
            if (boundaryParts.size < 3) {
                return false
            }
            val hasClassesInsideBoundary = projectClasses.any { cls ->
                cls.packageName == this
            }
            if (hasClassesInsideBoundary) {
                return false
            }
            val childSegments = projectClasses
                .asSequence()
                .map(JvmClassSymbol::packageName)
                .filter { packageName -> packageName.startsWith("$this.") }
                .map { packageName -> packageName.removePrefix("$this.").substringBefore('.') }
                .filter(String::isNotBlank)
                .toSet()
            val siblingRoots = projectClasses
                .asSequence()
                .map(JvmClassSymbol::packageName)
                .filter(String::isNotBlank)
                .map { packageName -> packageName.split('.').filter(String::isNotBlank) }
                .filter { parts -> parts.firstOrNull() != boundaryParts.firstOrNull() }
                .mapNotNull { parts -> parts.firstOrNull() }
                .toSet()
            return childSegments.size >= 2 && siblingRoots.isNotEmpty()
        }

        /** 为给定类计算所属组件名（结合包路径根、分层标记与穿透包折叠）。 */
        private fun componentNameFor(
            cls: JvmClassSymbol,
            sourceClasses: Collection<JvmClassSymbol>,
        ): String {
            val projectClasses = sourceClasses.filter { sourceClass -> sourceClass.isTrustedProjectSourceForBoundary() }
            val packages = projectClasses.map(JvmClassSymbol::packageName).filter(String::isNotBlank)
            val packageParts = cls.packageName.split('.').filter(String::isNotBlank)
            if (packageParts.isEmpty()) {
                return cls.moduleName ?: "(default)"
            }
            val rootParts = componentRootPartsFor(packageParts, packages)
            val layerIndex = packageParts.indexOfFirst { part -> part.lowercase() in layerMarkers }
            val rootName = rootParts.joinToString(".")
            if (layerIndex == rootParts.size && rootName.isMultiRootProductNamespace(projectClasses)) {
                return packageParts.take(layerIndex + 1).joinToString(".")
            }
            if (layerIndex == rootParts.size && rootParts.lastOrNull()?.lowercase() in organizationRootMarkers) {
                return packageParts.take(layerIndex + 1).joinToString(".")
            }
            if (layerIndex > 0 && layerIndex >= rootParts.size) {
                return packageParts.take(layerIndex).joinToString(".")
            }
            val depth = componentDepth(packageParts, rootParts.size, projectClasses)
            return packageParts.take(depth).joinToString(".")
        }

        /** 在同一首段包前缀的范围内寻找公共根段，找不到则回退到全局公共根。 */
        private fun componentRootPartsFor(
            packageParts: List<String>,
            packages: List<String>,
        ): List<String> {
            val firstPart = packageParts.firstOrNull() ?: return emptyList()
            val sameFirstPackages = packages.filter { packageName ->
                packageName.split('.').firstOrNull() == firstPart
            }
            val groupedRoot = commonRootParts(sameFirstPackages)
            if (groupedRoot.isNotEmpty()) {
                return groupedRoot
            }
            return commonRootParts(packages)
        }

        /** 计算组件的取包深度，必要时折叠穿过性的中间包段。 */
        private fun componentDepth(
            packageParts: List<String>,
            rootSize: Int,
            sourceClasses: Collection<JvmClassSymbol>,
        ): Int {
            val baseDepth = (rootSize + 1).coerceAtMost(packageParts.size).coerceAtLeast(1)
            if (shouldCollapsePassthroughPackage(packageParts, baseDepth, sourceClasses)) {
                return (baseDepth + 1).coerceAtMost(packageParts.size)
            }
            return baseDepth
        }

        /** 判断基础深度处的包段是否为语义穿透包（如 coordinator/streams/connect），如是则需向下延展一层。 */
        private fun shouldCollapsePassthroughPackage(
            packageParts: List<String>,
            baseDepth: Int,
            sourceClasses: Collection<JvmClassSymbol>,
        ): Boolean {
            if (baseDepth >= packageParts.size) {
                return false
            }
            val baseSegment = packageParts.getOrNull(baseDepth - 1)?.lowercase()
            if (baseSegment !in semanticPassthroughSegments) {
                return false
            }
            val basePackage = packageParts.take(baseDepth).joinToString(".")
            if (sourceClasses.any { cls -> cls.packageName == basePackage }) {
                return false
            }
            val childSegments = sourceClasses
                .asSequence()
                .map(JvmClassSymbol::packageName)
                .filter { packageName -> packageName.startsWith("$basePackage.") }
                .map { packageName -> packageName.removePrefix("$basePackage.").substringBefore('.') }
                .filter(String::isNotBlank)
                .toSet()
            return childSegments.size == 1
        }

        /** 批量组件命名上下文，缓存包结构信息以加速重复计算。 */
        private class ComponentNamingContext(
            val projectClasses: List<JvmClassSymbol>,
        ) {
            private val packagePartsByName = projectClasses
                .map(JvmClassSymbol::packageName)
                .filter(String::isNotBlank)
                .distinct()
                .associateWith { packageName -> packageName.split('.').filter(String::isNotBlank) }
            private val packageNames = packagePartsByName.keys.toList()
            private val packageNameSet = packageNames.toSet()
            private val commonRootParts = commonRootParts(packageNames)
            private val commonRootPartsByFirstSegment = packageNames
                .groupBy { packageName -> packagePartsByName.getValue(packageName).firstOrNull().orEmpty() }
                .mapValues { (_, names) -> commonRootParts(names) }
            private val multiRootNamespaceCache = mutableMapOf<String, Boolean>()
            private val passthroughCollapseCache = mutableMapOf<String, Boolean>()

            /** 计算单个类的组件名，逻辑与全局版本一致但使用缓存。 */
            fun componentNameFor(cls: JvmClassSymbol): String {
                val packageParts = cls.packageName.split('.').filter(String::isNotBlank)
                if (packageParts.isEmpty()) {
                    return cls.moduleName ?: "(default)"
                }
                val rootParts = componentRootPartsFor(packageParts)
                val layerIndex = packageParts.indexOfFirst { part -> part.lowercase() in layerMarkers }
                val rootName = rootParts.joinToString(".")
                if (layerIndex == rootParts.size && isMultiRootProductNamespace(rootName)) {
                    return packageParts.take(layerIndex + 1).joinToString(".")
                }
                if (layerIndex == rootParts.size && rootParts.lastOrNull()?.lowercase() in organizationRootMarkers) {
                    return packageParts.take(layerIndex + 1).joinToString(".")
                }
                if (layerIndex > 0 && layerIndex >= rootParts.size) {
                    return packageParts.take(layerIndex).joinToString(".")
                }
                val depth = componentDepth(packageParts, rootParts.size)
                return packageParts.take(depth).joinToString(".")
            }

            /** 在缓存中按首段查询公共根段，缺省回退全局公共根。 */
            private fun componentRootPartsFor(packageParts: List<String>): List<String> {
                val firstPart = packageParts.firstOrNull() ?: return emptyList()
                val groupedRoot = commonRootPartsByFirstSegment[firstPart].orEmpty()
                if (groupedRoot.isNotEmpty()) {
                    return groupedRoot
                }
                return commonRootParts
            }

            /** 计算组件取包深度，带缓存的穿透段折叠逻辑。 */
            private fun componentDepth(
                packageParts: List<String>,
                rootSize: Int,
            ): Int {
                val baseDepth = (rootSize + 1).coerceAtMost(packageParts.size).coerceAtLeast(1)
                if (shouldCollapsePassthroughPackage(packageParts, baseDepth)) {
                    return (baseDepth + 1).coerceAtMost(packageParts.size)
                }
                return baseDepth
            }

            /** 判断穿透段是否需要折叠（缓存版本）。 */
            private fun shouldCollapsePassthroughPackage(
                packageParts: List<String>,
                baseDepth: Int,
            ): Boolean {
                if (baseDepth >= packageParts.size) {
                    return false
                }
                val baseSegment = packageParts.getOrNull(baseDepth - 1)?.lowercase()
                if (baseSegment !in semanticPassthroughSegments) {
                    return false
                }
                val basePackage = packageParts.take(baseDepth).joinToString(".")
                return passthroughCollapseCache.getOrPut(basePackage) {
                    if (basePackage in packageNameSet) {
                        return@getOrPut false
                    }
                    packageNames
                        .asSequence()
                        .filter { packageName -> packageName.startsWith("$basePackage.") }
                        .map { packageName -> packageName.removePrefix("$basePackage.").substringBefore('.') }
                        .filter(String::isNotBlank)
                        .toSet()
                        .size == 1
                }
            }

            /** 缓存版本的多根产品命名空间判断。 */
            private fun isMultiRootProductNamespace(boundaryName: String): Boolean {
                return multiRootNamespaceCache.getOrPut(boundaryName) {
                    val boundaryParts = boundaryName.split('.').filter(String::isNotBlank)
                    if (boundaryParts.size < 3) {
                        return@getOrPut false
                    }
                    if (boundaryName in packageNameSet) {
                        return@getOrPut false
                    }
                    val childSegments = packageNames
                        .asSequence()
                        .filter { packageName -> packageName.startsWith("$boundaryName.") }
                        .map { packageName -> packageName.removePrefix("$boundaryName.").substringBefore('.') }
                        .filter(String::isNotBlank)
                        .toSet()
                    val siblingRoots = packagePartsByName.values
                        .asSequence()
                        .filter { parts -> parts.firstOrNull() != boundaryParts.firstOrNull() }
                        .mapNotNull { parts -> parts.firstOrNull() }
                        .toSet()
                    childSegments.size >= 2 && siblingRoots.isNotEmpty()
                }
            }
        }

        /** 语义穿透包段集合，遇到这些段时组件命名会向下延展一层。 */
        private val semanticPassthroughSegments = setOf(
            "coordinator",
            "streams",
            "connect",
        )

        /** 求一组包名的公共前缀段（去掉以分层关键字结尾的部分）。 */
        private fun commonRootParts(packages: List<String>): List<String> {
            if (packages.isEmpty()) {
                return emptyList()
            }
            val splitPackages = packages.map { pkg -> pkg.split('.').filter(String::isNotBlank) }
            val first = splitPackages.first()
            val root = mutableListOf<String>()
            for (index in first.indices) {
                val part = first[index]
                if (splitPackages.all { parts -> parts.getOrNull(index) == part }) {
                    root += part
                } else {
                    break
                }
            }
            return root.dropLastWhile { part -> part.lowercase() in layerMarkers }
        }

        /** 从资源路径推导资源分组名（mq 类、首段目录或默认 project-resources）。 */
        private fun resourceGroupName(path: String): String {
            if (path.startsWith("mq:")) {
                return "mq"
            }
            val normalized = path.substringBefore('!', missingDelimiterValue = path)
            val firstSegment = normalized.substringBefore('/', missingDelimiterValue = "")
            return firstSegment.ifBlank {
                path.substringBeforeLast('/', missingDelimiterValue = "").ifBlank { "project-resources" }
            }
        }

        /** 把资源分组名翻译为人类可读的展示标题。 */
        private fun resourceGroupTitle(path: String, fallbackTitle: String): String {
            if (path.startsWith("mq:")) {
                return "MQ Topics"
            }
            val groupName = resourceGroupName(path)
            return when (groupName) {
                "src" -> "Project Resources"
                "META-INF" -> "META-INF Resources"
                "project-resources" -> "Project Resources"
                else -> groupName.substringAfterLast('/').ifBlank { fallbackTitle }
            }
        }
    }
}
