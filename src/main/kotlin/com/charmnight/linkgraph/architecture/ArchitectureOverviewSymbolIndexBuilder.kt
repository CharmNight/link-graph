package com.charmnight.linkgraph.architecture

import com.charmnight.linkgraph.jvm.index.JvmClassKind
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmFieldSymbol
import com.charmnight.linkgraph.jvm.index.JvmFieldTypeReference
import com.charmnight.linkgraph.jvm.index.JvmFieldTypeRole
import com.charmnight.linkgraph.jvm.index.JvmModuleSymbol
import com.charmnight.linkgraph.jvm.index.JvmPackageSymbol
import com.charmnight.linkgraph.jvm.index.JvmResourceKind
import com.charmnight.linkgraph.jvm.index.JvmResourceSymbol
import com.charmnight.linkgraph.jvm.index.JvmServiceProviderFile
import com.charmnight.linkgraph.jvm.index.JvmServiceProviderIndex
import com.charmnight.linkgraph.jvm.index.JvmSourceRef
import com.charmnight.linkgraph.jvm.index.JvmStereotype
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex
import com.charmnight.linkgraph.jvm.index.stableJvmId
import com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget
import com.charmnight.linkgraph.source.SourceOrigin
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal class ArchitectureOverviewSymbolIndexBuilder(
    private val project: Project,
    private val trace: ((stage: String, startedAtNanos: Long, details: () -> List<String>) -> Unit)? = null,
) {
    fun build(budget: JvmResolutionBudget): JvmSymbolIndex {
        val modules = linkedMapOf<String, JvmModuleSymbol>()
        val packages = linkedMapOf<String, JvmPackageSymbol>()
        val classes = linkedMapOf<String, JvmClassSymbol>()
        val fields = linkedMapOf<String, JvmFieldSymbol>()
        val resources = linkedMapOf<String, JvmResourceSymbol>()
        val serviceFiles = linkedMapOf<String, MutableList<JvmServiceProviderFile>>()
        val root = project.basePath?.let(Path::of)
        val startedAt = System.nanoTime()
        var visitedFiles = 0
        val diskRootState = when {
            root == null -> "missing"
            Files.exists(root) -> "exists"
            else -> "not_found"
        }
        if (root != null && Files.exists(root)) {
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        checkCanceled()
                        if (dir != root && dir.fileName.toString() in excludedDirectoryNames) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        checkCanceled()
                        visitedFiles += 1
                        if (classes.size >= budget.maxProjectClasses) {
                            return FileVisitResult.TERMINATE
                        }
                        val relativePath = root.relativize(file).toString().replace('\\', '/')
                        if (!budget.includeTests && relativePath.contains("/src/test/")) {
                            return FileVisitResult.CONTINUE
                        }
                        when (file.fileName.toString().substringAfterLast('.', "").lowercase()) {
                            "java" -> indexJavaFile(root, file, relativePath, modules, packages, classes, fields)
                            "kt", "kts" -> indexKotlinFile(root, file, relativePath, modules, packages, classes, fields)
                            else -> indexResourceFile(file, relativePath, resources, serviceFiles)
                        }
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }
        val classesBeforeVirtualRoots = classes.size
        val shouldIndexVirtualContentRoots = classes.isEmpty() || hasNonFileContentRoots()
        if (shouldIndexVirtualContentRoots) {
            indexVirtualContentRoots(budget, modules, packages, classes, fields, resources, serviceFiles)
        }
        trace?.invoke("architectureOverview.symbolIndex.fileTree", startedAt) {
            listOf(
                "diskRoot=$diskRootState",
                "indexedVirtualContentRoots=$shouldIndexVirtualContentRoots",
                "virtualClassDelta=${classes.size - classesBeforeVirtualRoots}",
                "visitedFiles=$visitedFiles",
                "modules=${modules.size}",
                "packages=${packages.size}",
                "classes=${classes.size}",
                "fields=${fields.size}",
                "resources=${resources.size}",
                "serviceFiles=${serviceFiles.values.sumOf { it.size }}",
            )
        }
        return JvmSymbolIndex(
            modulesByName = modules,
            packagesByName = packages,
            classesByQualifiedName = classes,
            fieldsByQualifiedName = fields,
            resourcesByPath = resources,
            serviceProviderIndex = JvmServiceProviderIndex(serviceFiles),
        )
    }

    private fun hasNonFileContentRoots(): Boolean =
        ProjectRootManager.getInstance(project).contentRoots.any { root ->
            root.fileSystem.protocol != "file"
        }

    private fun indexVirtualContentRoots(
        budget: JvmResolutionBudget,
        modules: MutableMap<String, JvmModuleSymbol>,
        packages: MutableMap<String, JvmPackageSymbol>,
        classes: MutableMap<String, JvmClassSymbol>,
        fields: MutableMap<String, JvmFieldSymbol>,
        resources: MutableMap<String, JvmResourceSymbol>,
        serviceFiles: MutableMap<String, MutableList<JvmServiceProviderFile>>,
    ) {
        val fileIndex = ProjectFileIndex.getInstance(project)
        ProjectRootManager.getInstance(project).contentRoots.forEach { root ->
            VfsUtilCore.iterateChildrenRecursively(root, null) { file ->
                checkCanceled()
                if (file.isDirectory) {
                    return@iterateChildrenRecursively file == root || file.name !in excludedDirectoryNames
                }
                if (classes.size >= budget.maxProjectClasses) {
                    return@iterateChildrenRecursively false
                }
                if (!budget.includeTests && fileIndex.isInTestSourceContent(file)) {
                    return@iterateChildrenRecursively true
                }
                val relativePath = VfsUtilCore.getRelativePath(file, root, '/') ?: file.path.replace('\\', '/')
                when (file.extension?.lowercase()) {
                    "java" -> readVirtualSmallText(file)?.let { text ->
                        indexJavaText(
                            text = text,
                            rootName = root.name,
                            relativePath = relativePath,
                            modules = modules,
                            packages = packages,
                            classes = classes,
                            fields = fields,
                            virtualFileUrl = file.url,
                        )
                    }
                    "kt", "kts" -> readVirtualSmallText(file)?.let { text ->
                        indexKotlinText(
                            text = text,
                            rootName = root.name,
                            relativePath = relativePath,
                            modules = modules,
                            packages = packages,
                            classes = classes,
                            fields = fields,
                            virtualFileUrl = file.url,
                        )
                    }
                    else -> indexResourceText(
                        relativePath = relativePath,
                        textProvider = { readVirtualSmallText(file) },
                        resources = resources,
                        serviceFiles = serviceFiles,
                        virtualFileUrl = file.url,
                    )
                }
                true
            }
        }
    }

    private fun indexJavaFile(
        root: Path,
        file: Path,
        relativePath: String,
        modules: MutableMap<String, JvmModuleSymbol>,
        packages: MutableMap<String, JvmPackageSymbol>,
        classes: MutableMap<String, JvmClassSymbol>,
        fields: MutableMap<String, JvmFieldSymbol>,
    ) {
        val text = readSmallText(file) ?: return
        indexJavaText(
            text = text,
            rootName = root.fileName?.toString(),
            relativePath = relativePath,
            modules = modules,
            packages = packages,
            classes = classes,
            fields = fields,
            virtualFileUrl = file.toUri().toString(),
        )
    }

    private fun indexJavaText(
        text: String,
        rootName: String?,
        relativePath: String,
        modules: MutableMap<String, JvmModuleSymbol>,
        packages: MutableMap<String, JvmPackageSymbol>,
        classes: MutableMap<String, JvmClassSymbol>,
        fields: MutableMap<String, JvmFieldSymbol>,
        virtualFileUrl: String?,
    ) {
        val packageName = javaPackageRegex.find(text)?.groupValues?.getOrNull(1).orEmpty()
        val imports = javaImports(text)
        val moduleName = moduleName(rootName, relativePath)
        putModuleAndPackage(moduleName, packageName, modules, packages)
        javaClassRegex.findAll(text).forEach { match ->
            val simpleName = match.groupValues[2].takeIf(String::isNotBlank) ?: return@forEach
            val qualifiedName = listOf(packageName, simpleName).filter(String::isNotBlank).joinToString(".")
            val header = match.value
            classes.putIfAbsent(
                qualifiedName,
                classSymbol(
                    qualifiedName = qualifiedName,
                    simpleName = simpleName,
                    packageName = packageName,
                    moduleName = moduleName,
                    kind = javaClassKind(match.groupValues[1]),
                    stereotype = stereotypeFromNearbyAnnotations(text, match.range.first),
                    abstract = header.contains(" abstract "),
                    source = sourceRef(relativePath, virtualFileUrl),
                    superClassName = javaExtendsRegex.find(header)?.groupValues?.getOrNull(1)?.resolveTypeName(packageName, imports),
                    interfaceNames = javaImplementsRegex.find(header)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.split(',')
                        ?.mapNotNull { name -> name.resolveTypeName(packageName, imports) }
                        .orEmpty(),
                    testSource = relativePath.contains("/src/test/"),
                ),
            )
        }
        fieldRegex.findAll(text.withoutMethodBodies()).forEach { match ->
            val typeName = match.groupValues[1].resolveTypeName(packageName, imports) ?: return@forEach
            val fieldName = match.groupValues[2].takeIf(String::isNotBlank) ?: return@forEach
            val ownerClass = classes.values
                .filter { cls -> cls.source?.displayPath == relativePath }
                .lastOrNull { cls -> match.range.first > text.indexOf(cls.simpleName).coerceAtLeast(0) }
                ?: return@forEach
            fields.putIfAbsent(
                "${ownerClass.qualifiedName}.$fieldName",
                fieldSymbol(ownerClass.qualifiedName, fieldName, typeName, ownerClass.source),
            )
        }
    }

    private fun indexKotlinFile(
        root: Path,
        file: Path,
        relativePath: String,
        modules: MutableMap<String, JvmModuleSymbol>,
        packages: MutableMap<String, JvmPackageSymbol>,
        classes: MutableMap<String, JvmClassSymbol>,
        fields: MutableMap<String, JvmFieldSymbol>,
    ) {
        val text = readSmallText(file) ?: return
        indexKotlinText(
            text = text,
            rootName = root.fileName?.toString(),
            relativePath = relativePath,
            modules = modules,
            packages = packages,
            classes = classes,
            fields = fields,
            virtualFileUrl = file.toUri().toString(),
        )
    }

    private fun indexKotlinText(
        text: String,
        rootName: String?,
        relativePath: String,
        modules: MutableMap<String, JvmModuleSymbol>,
        packages: MutableMap<String, JvmPackageSymbol>,
        classes: MutableMap<String, JvmClassSymbol>,
        fields: MutableMap<String, JvmFieldSymbol>,
        virtualFileUrl: String?,
    ) {
        val packageName = kotlinPackageRegex.find(text)?.groupValues?.getOrNull(1).orEmpty()
        val imports = kotlinImports(text)
        val moduleName = moduleName(rootName, relativePath)
        putModuleAndPackage(moduleName, packageName, modules, packages)
        kotlinClassRegex.findAll(text).forEach { match ->
            val simpleName = match.groupValues[2].takeIf(String::isNotBlank) ?: return@forEach
            val qualifiedName = listOf(packageName, simpleName).filter(String::isNotBlank).joinToString(".")
            val declarations = match.groupValues.getOrNull(3).orEmpty()
                .split(',')
                .mapNotNull { declaration ->
                    declaration.trim().substringBefore('(').substringBefore('<').resolveTypeName(packageName, imports)
                }
            classes.putIfAbsent(
                qualifiedName,
                classSymbol(
                    qualifiedName = qualifiedName,
                    simpleName = simpleName,
                    packageName = packageName,
                    moduleName = moduleName,
                    kind = kotlinClassKind(match.groupValues[1]),
                    stereotype = stereotypeFromNearbyAnnotations(text, match.range.first),
                    abstract = match.value.contains("abstract "),
                    source = sourceRef(relativePath, virtualFileUrl),
                    superClassName = declarations.firstOrNull { name -> name.substringAfterLast('.').firstOrNull()?.isUpperCase() == true },
                    interfaceNames = declarations.drop(1),
                    testSource = relativePath.contains("/src/test/"),
                ),
            )
        }
        kotlinPropertyRegex.findAll(text).forEach { match ->
            val ownerClass = classes.values
                .filter { cls -> cls.source?.displayPath == relativePath }
                .lastOrNull { cls -> match.range.first > text.indexOf(cls.simpleName).coerceAtLeast(0) }
                ?: return@forEach
            val fieldName = match.groupValues[1]
            val typeName = match.groupValues[2].resolveTypeName(ownerClass.packageName, imports) ?: return@forEach
            fields.putIfAbsent(
                "${ownerClass.qualifiedName}.$fieldName",
                fieldSymbol(ownerClass.qualifiedName, fieldName, typeName, ownerClass.source),
            )
        }
    }

    private fun indexResourceFile(
        file: Path,
        relativePath: String,
        resources: MutableMap<String, JvmResourceSymbol>,
        serviceFiles: MutableMap<String, MutableList<JvmServiceProviderFile>>,
    ) {
        indexResourceText(
            relativePath = relativePath,
            textProvider = { readSmallText(file) },
            resources = resources,
            serviceFiles = serviceFiles,
            virtualFileUrl = file.toUri().toString(),
        )
    }

    private fun indexResourceText(
        relativePath: String,
        textProvider: () -> String?,
        resources: MutableMap<String, JvmResourceSymbol>,
        serviceFiles: MutableMap<String, MutableList<JvmServiceProviderFile>>,
        virtualFileUrl: String?,
    ) {
        val kind = resourceKind(relativePath)
        if (kind == JvmResourceKind.OTHER) {
            return
        }
        val resource = JvmResourceSymbol(
            id = stableJvmId("resource", relativePath),
            path = relativePath,
            kind = kind,
            source = sourceRef(relativePath, virtualFileUrl),
            origin = SourceOrigin.CONTENT_ROOT,
        )
        resources.putIfAbsent(relativePath, resource)
        if (kind == JvmResourceKind.SPI_SERVICE_FILE) {
            val providers = textProvider()
                ?.lineSequence()
                ?.map { line -> line.substringBefore('#').trim() }
                ?.filter(String::isNotBlank)
                ?.distinct()
                ?.toList()
                .orEmpty()
            serviceFiles.getOrPut(relativePath.substringAfter("META-INF/services/")) { mutableListOf() } +=
                JvmServiceProviderFile(
                    serviceInterfaceName = relativePath.substringAfter("META-INF/services/"),
                    providerClassNames = providers,
                    resource = resource,
                    origin = SourceOrigin.CONTENT_ROOT,
                )
        }
    }

    private fun putModuleAndPackage(
        moduleName: String?,
        packageName: String,
        modules: MutableMap<String, JvmModuleSymbol>,
        packages: MutableMap<String, JvmPackageSymbol>,
    ) {
        moduleName?.let { name ->
            modules.putIfAbsent(
                name,
                JvmModuleSymbol(
                    id = stableJvmId("module", name),
                    qualifiedName = name,
                    simpleName = name,
                    source = null,
                    origin = SourceOrigin.PROJECT_SOURCE,
                ),
            )
        }
        if (packageName.isNotBlank()) {
            packages.putIfAbsent(
                packageName,
                JvmPackageSymbol(
                    id = stableJvmId("package", packageName),
                    qualifiedName = packageName,
                    simpleName = packageName.substringAfterLast('.'),
                    moduleName = moduleName,
                    source = null,
                    origin = SourceOrigin.PROJECT_SOURCE,
                ),
            )
        }
    }

    private fun classSymbol(
        qualifiedName: String,
        simpleName: String,
        packageName: String,
        moduleName: String?,
        kind: JvmClassKind,
        stereotype: JvmStereotype,
        abstract: Boolean,
        source: JvmSourceRef,
        superClassName: String?,
        interfaceNames: List<String>,
        testSource: Boolean,
    ): JvmClassSymbol =
        JvmClassSymbol(
            id = stableJvmId("class", qualifiedName),
            qualifiedName = qualifiedName,
            simpleName = simpleName,
            packageName = packageName,
            moduleName = moduleName,
            kind = kind,
            stereotype = stereotype,
            testSource = testSource,
            abstract = abstract,
            source = source,
            origin = SourceOrigin.PROJECT_SOURCE,
            superClassName = superClassName,
            interfaceNames = interfaceNames,
        )

    private fun fieldSymbol(
        ownerClassName: String,
        fieldName: String,
        typeName: String,
        source: JvmSourceRef?,
    ): JvmFieldSymbol {
        val qualifiedName = "$ownerClassName.$fieldName"
        return JvmFieldSymbol(
            id = stableJvmId("field", qualifiedName),
            qualifiedName = qualifiedName,
            simpleName = fieldName,
            ownerClassName = ownerClassName,
            typeName = typeName,
            source = source,
            origin = SourceOrigin.PROJECT_SOURCE,
            typeReferences = listOf(JvmFieldTypeReference(typeName, JvmFieldTypeRole.DIRECT_VALUE)),
        )
    }

    private fun moduleName(rootName: String?, relativePath: String): String? {
        val parts = relativePath.split('/')
        val sourceIndex = parts.indexOf("src")
        if (sourceIndex > 0) {
            return parts.take(sourceIndex).joinToString(":")
        }
        return rootName
    }

    private fun readSmallText(file: Path): String? {
        checkCanceled()
        if (Files.size(file) > MAX_TEXT_FILE_BYTES) {
            return null
        }
        return runCatching { Files.readString(file, StandardCharsets.UTF_8) }.getOrNull()
    }

    private fun readVirtualSmallText(file: VirtualFile): String? {
        checkCanceled()
        if (file.length > MAX_TEXT_FILE_BYTES) {
            return null
        }
        return runCatching { String(file.contentsToByteArray(), StandardCharsets.UTF_8) }.getOrNull()
    }

    private fun String.withoutMethodBodies(): String =
        replace(Regex("""(?s)\([^)]*\)\s*\{.*?\}"""), "(){}")

    private fun String.resolveTypeName(
        packageName: String,
        imports: Map<String, String> = emptyMap(),
    ): String? {
        val cleaned = trim()
            .removePrefix("private ")
            .removePrefix("protected ")
            .removePrefix("public ")
            .removePrefix("internal ")
            .removePrefix("open ")
            .removePrefix("final ")
            .removePrefix("abstract ")
            .substringBefore('<')
            .substringBefore('?')
            .substringBefore('[')
            .trim()
            .takeIf(String::isNotBlank)
            ?: return null
        if (cleaned in primitiveNames || cleaned.firstOrNull()?.isUpperCase() != true && !cleaned.contains('.')) {
            return null
        }
        imports[cleaned]?.let { return it }
        return if (cleaned.contains('.')) cleaned else "$packageName.$cleaned"
    }

    private fun javaImports(text: String): Map<String, String> =
        javaImportRegex.findAll(text)
            .mapNotNull { match ->
                val qualifiedName = match.groupValues[1].takeUnless { name -> name.endsWith(".*") } ?: return@mapNotNull null
                qualifiedName.substringAfterLast('.') to qualifiedName
            }
            .toMap()

    private fun kotlinImports(text: String): Map<String, String> =
        kotlinImportRegex.findAll(text)
            .mapNotNull { match ->
                val qualifiedName = match.groupValues[1].takeUnless { name -> name.endsWith(".*") } ?: return@mapNotNull null
                val alias = match.groupValues.getOrNull(2)?.takeIf(String::isNotBlank)
                (alias ?: qualifiedName.substringAfterLast('.')) to qualifiedName
            }
            .toMap()

    private fun stereotypeFromNearbyAnnotations(text: String, startOffset: Int): JvmStereotype {
        val nearby = text.substring(0, startOffset.coerceAtMost(text.length)).takeLast(300)
        return when {
            nearby.contains("@RestController") || nearby.contains("@Controller") -> JvmStereotype.CONTROLLER
            nearby.contains("@Service") -> JvmStereotype.SERVICE
            nearby.contains("@Repository") -> JvmStereotype.REPOSITORY
            nearby.contains("@Configuration") -> JvmStereotype.CONFIGURATION
            nearby.contains("@Component") || nearby.contains("@Named") || nearby.contains("@Singleton") -> JvmStereotype.COMPONENT
            else -> JvmStereotype.UNKNOWN
        }
    }

    private fun javaClassKind(keyword: String): JvmClassKind =
        when (keyword) {
            "interface" -> JvmClassKind.INTERFACE
            "enum" -> JvmClassKind.ENUM
            "record" -> JvmClassKind.RECORD
            "@interface" -> JvmClassKind.ANNOTATION
            else -> JvmClassKind.CLASS
        }

    private fun kotlinClassKind(keyword: String): JvmClassKind =
        when (keyword) {
            "interface" -> JvmClassKind.INTERFACE
            "enum class" -> JvmClassKind.ENUM
            "annotation class" -> JvmClassKind.ANNOTATION
            "object" -> JvmClassKind.OBJECT
            else -> JvmClassKind.CLASS
        }

    private fun resourceKind(path: String): JvmResourceKind =
        when {
            path.startsWith("mq:") -> JvmResourceKind.MQ_TOPIC
            path.contains("META-INF/services/") -> JvmResourceKind.SPI_SERVICE_FILE
            path.endsWith(".xml", ignoreCase = true) -> JvmResourceKind.XML
            path.endsWith(".yml", ignoreCase = true) || path.endsWith(".yaml", ignoreCase = true) -> JvmResourceKind.YAML
            path.endsWith(".properties", ignoreCase = true) -> JvmResourceKind.PROPERTIES
            path.endsWith(".sql", ignoreCase = true) -> JvmResourceKind.SQL
            else -> JvmResourceKind.OTHER
        }

    private fun sourceRef(
        relativePath: String,
        virtualFileUrl: String?,
    ): JvmSourceRef =
        JvmSourceRef(
            displayPath = relativePath,
            virtualFileUrl = virtualFileUrl,
            startLine = 1,
            endLine = null,
            decompiled = false,
        )

    private fun checkCanceled() {
        ProgressManager.checkCanceled()
    }

    private companion object {
        private const val MAX_TEXT_FILE_BYTES = 512 * 1024L
        private val excludedDirectoryNames = setOf(
            ".git",
            ".gradle",
            ".idea",
            ".kotlin",
            "build",
            "build-idea-sandbox",
            "dist",
            "node_modules",
            "out",
            "target",
        )
        private val primitiveNames = setOf(
            "boolean",
            "byte",
            "char",
            "double",
            "float",
            "int",
            "long",
            "short",
            "void",
            "Boolean",
            "Byte",
            "Char",
            "Double",
            "Float",
            "Int",
            "Long",
            "Short",
            "String",
        )
        private val javaPackageRegex = Regex("""(?m)^\s*package\s+([A-Za-z_][\w.]*)\s*;""")
        private val kotlinPackageRegex = Regex("""(?m)^\s*package\s+([A-Za-z_][\w.]*)""")
        private val javaImportRegex = Regex("""(?m)^\s*import\s+(?:static\s+)?([A-Za-z_][\w.]*)(?:\s*;|;)""")
        private val kotlinImportRegex = Regex("""(?m)^\s*import\s+([A-Za-z_][\w.]*)(?:\s+as\s+([A-Za-z_][\w]*))?""")
        private val javaClassRegex = Regex(
            """(?m)(?:^|\s)(?:(?:public|protected|private|abstract|final|static|sealed|non-sealed)\s+)*(class|interface|enum|record|@interface)\s+([A-Za-z_][\w$]*)\s*([^;\{]*)""",
        )
        private val javaExtendsRegex = Regex("""\bextends\s+([A-Za-z_][\w.]*)""")
        private val javaImplementsRegex = Regex("""\bimplements\s+([A-Za-z_][\w.,\s<>?]+)""")
        private val kotlinClassRegex = Regex(
            """(?m)(?:^|\s)(class|interface|object|enum class|annotation class)\s+([A-Za-z_][\w]*)\s*(?::\s*([^{\n]+))?""",
        )
        private val fieldRegex = Regex(
            """(?m)^\s*(?:(?:private|protected|public|static|final|volatile|transient)\s+)*([A-Z][A-Za-z0-9_.$<>?,\s]*)\s+([a-zA-Z_][\w]*)\s*(?:=|;)""",
        )
        private val kotlinPropertyRegex = Regex(
            """(?m)^\s*(?:private|protected|public|internal)?\s*(?:val|var)\s+([a-zA-Z_][\w]*)\s*:\s*([A-Z][A-Za-z0-9_.$<>?]*)""",
        )
    }
}
