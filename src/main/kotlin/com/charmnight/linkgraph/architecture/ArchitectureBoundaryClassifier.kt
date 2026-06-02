package com.charmnight.linkgraph.architecture

import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmStereotype
import com.charmnight.linkgraph.source.SourceOrigin

enum class ArchitectureProjectionTargetKind {
    PROJECT_SERVICE_BOUNDARY,
    PROJECT_COMPONENT,
    PROJECT_PACKAGE,
    PROJECT_LAYER,
    PROJECT_RESOURCE,
    EXTERNAL_LIBRARY_GROUP,
    JDK_GROUP,
}

data class ArchitectureProjectionTarget(
    val nodeId: String,
    val qualifiedName: String,
    val title: String,
    val kind: ArchitectureProjectionTargetKind,
)

class ArchitectureBoundaryClassifier {
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

    fun serviceBoundaryFor(
        cls: JvmClassSymbol,
        sourceClasses: Collection<JvmClassSymbol> = listOf(cls),
    ): ArchitectureProjectionTarget? = serviceBoundaryFor(cls, trustedServiceBoundaryNames(sourceClasses))

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

    fun packageGroupFor(cls: JvmClassSymbol): ArchitectureProjectionTarget {
        val packageName = cls.packageName.ifBlank { "(default)" }
        return ArchitectureProjectionTarget(
            nodeId = "arch:package:$packageName",
            qualifiedName = packageName,
            title = packageName,
            kind = ArchitectureProjectionTargetKind.PROJECT_PACKAGE,
        )
    }

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

    fun layerFor(cls: JvmClassSymbol): ArchitectureProjectionTarget {
        val layer = layerNameFor(cls)
        return ArchitectureProjectionTarget(
            nodeId = "arch:layer:${layer.lowercase()}",
            qualifiedName = layer,
            title = layer,
            kind = ArchitectureProjectionTargetKind.PROJECT_LAYER,
        )
    }

    fun dependencyGroupFor(cls: JvmClassSymbol): ArchitectureProjectionTarget {
        val groupName = externalGroupName(cls)
        return ArchitectureProjectionTarget(
            nodeId = "arch:library:$groupName",
            qualifiedName = groupName,
            title = groupName,
            kind = ArchitectureProjectionTargetKind.EXTERNAL_LIBRARY_GROUP,
        )
    }

    fun jdkGroupFor(cls: JvmClassSymbol): ArchitectureProjectionTarget =
        ArchitectureProjectionTarget(
            nodeId = "arch:jdk:JDK",
            qualifiedName = "JDK",
            title = "JDK",
            kind = ArchitectureProjectionTargetKind.JDK_GROUP,
        )

    fun isProjectSourceClass(cls: JvmClassSymbol): Boolean = cls.isTrustedProjectSourceClass()

    private fun JvmClassSymbol.isTrustedProjectSourceClass(): Boolean =
        !external &&
            !library &&
            !jdk &&
            !testSource &&
            origin == SourceOrigin.PROJECT_SOURCE

    private fun JvmClassSymbol.isJdkClass(): Boolean =
        jdk || origin in setOf(SourceOrigin.JDK_SOURCE, SourceOrigin.JDK_CLASS) || qualifiedName.isJdkQualifiedName()

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

    private fun String.isJdkQualifiedName(): Boolean =
        startsWith("java.") ||
            startsWith("javax.") ||
            startsWith("jdk.") ||
            startsWith("sun.") ||
            startsWith("com.sun.")

    companion object {
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
        private const val MIN_SERVICE_EVIDENCE_CLASSES = 2

        fun serviceNodeId(boundaryName: String): String = "arch:service:$boundaryName"

        fun componentNodeId(componentName: String): String = "arch:component:$componentName"

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

        private fun JvmClassSymbol.isTrustedProjectSourceForBoundary(): Boolean =
            !external &&
                !library &&
                !jdk &&
                !testSource &&
                origin == SourceOrigin.PROJECT_SOURCE

        private fun serviceBoundaryCandidateFor(cls: JvmClassSymbol): String? {
            val parts = cls.packageName.split('.').filter(String::isNotBlank)
            val markerIndex = parts.indexOfFirst { part -> part.lowercase() in layerMarkers }
            if (markerIndex <= 1) {
                return null
            }
            return parts.take(markerIndex).joinToString(".").takeIf(String::isNotBlank)
        }

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

        private fun String.isProjectRootBoundary(projectRootParts: List<String>): Boolean {
            if (projectRootParts.isEmpty()) {
                return false
            }
            return split('.').filter(String::isNotBlank) == projectRootParts
        }

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

            private fun componentRootPartsFor(packageParts: List<String>): List<String> {
                val firstPart = packageParts.firstOrNull() ?: return emptyList()
                val groupedRoot = commonRootPartsByFirstSegment[firstPart].orEmpty()
                if (groupedRoot.isNotEmpty()) {
                    return groupedRoot
                }
                return commonRootParts
            }

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

        private val semanticPassthroughSegments = setOf(
            "coordinator",
            "streams",
            "connect",
        )

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
