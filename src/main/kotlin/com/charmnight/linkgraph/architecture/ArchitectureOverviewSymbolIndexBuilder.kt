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

/**
 * 架构概览符号索引构建器，通过遍历项目磁盘文件树与虚拟内容根，
 * 提取模块/包/类/字段/资源等符号信息，构建一个轻量的 JVM 符号索引。
 */
internal class ArchitectureOverviewSymbolIndexBuilder(
    /** 当前项目实例。 */
    private val project: Project,
    /** 可选的阶段追踪回调，用于记录构建阶段耗时与详情。 */
    private val trace: ((stage: String, startedAtNanos: Long, details: () -> List<String>) -> Unit)? = null,
) {
    /** 根据预算构建 JVM 符号索引。 */
    fun build(budget: JvmResolutionBudget): JvmSymbolIndex {
        // 各类符号容器，使用 LinkedHashMap 保留遍历顺序，便于结果稳定可重现
        val modules = linkedMapOf<String, JvmModuleSymbol>()
        val packages = linkedMapOf<String, JvmPackageSymbol>()
        val classes = linkedMapOf<String, JvmClassSymbol>()
        val fields = linkedMapOf<String, JvmFieldSymbol>()
        val resources = linkedMapOf<String, JvmResourceSymbol>()
        val serviceFiles = linkedMapOf<String, MutableList<JvmServiceProviderFile>>()
        val root = project.basePath?.let(Path::of)
        val startedAt = System.nanoTime()
        var visitedFiles = 0
        // 记录磁盘根目录状态，用于在追踪日志中诊断"找不到根"等问题
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
                        // 命中排除目录（如 build、node_modules）时直接跳过整个子树
                        if (dir != root && dir.fileName.toString() in excludedDirectoryNames) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        checkCanceled()
                        visitedFiles += 1
                        // 达到预算上限后立即终止遍历，避免无限制扫描大型工程
                        if (classes.size >= budget.maxProjectClasses) {
                            return FileVisitResult.TERMINATE
                        }
                        val relativePath = root.relativize(file).toString().replace('\\', '/')
                        // 不包含测试源时跳过 src/test/ 下的文件
                        if (!budget.includeTests && relativePath.contains("/src/test/")) {
                            return FileVisitResult.CONTINUE
                        }
                        // 按扩展名分派给不同的索引器
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
        // 磁盘扫描未发现类，或存在非本地文件系统的内容根（如 JAR 内、远程）时回退到 VFS
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

    /** 判断项目内容根中是否存在非本地 file 协议的虚拟文件系统，决定是否启用 VFS 索引路径。 */
    private fun hasNonFileContentRoots(): Boolean =
        ProjectRootManager.getInstance(project).contentRoots.any { root ->
            root.fileSystem.protocol != "file"
        }

    /**
     * 通过 IntelliJ VFS 遍历所有内容根，将虚拟文件作为补充索引来源。
     * 用于磁盘文件不可用（如打开的是已索引 jar）或内容根在非本地文件系统的场景。
     */
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
                    // 目录命中黑名单时停止向下遍历
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

    /**
     * 读取磁盘上的 Java 文件全文，转交给文本索引逻辑。
     * 仅作为 Path → String 的中间适配层。
     */
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

    /**
     * 对 Java 源码文本进行轻量解析：提取 package/import，识别类声明及其继承关系，并扫描字段。
     * 采用正则方案而非完整 PSI 解析，追求速度而非性能完整。
     */
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
        // 扫描字段时先剥离方法体，避免误把方法内部表达式识别成字段
        fieldRegex.findAll(text.withoutMethodBodies()).forEach { match ->
            val typeName = match.groupValues[1].resolveTypeName(packageName, imports) ?: return@forEach
            val fieldName = match.groupValues[2].takeIf(String::isNotBlank) ?: return@forEach
            // 通过文本偏移将字段绑定到所属类：取最后声明的类名位置作为 owner
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

    /** 读取磁盘上的 Kotlin 文件全文后转交给文本索引逻辑。 */
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

    /**
     * 对 Kotlin 源码文本进行轻量解析。
     * Kotlin 的继承与实现共用冒号语法，需通过简单名首字母大写来区分父类与接口。
     */
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
            // 冒号后的声明列表既可能包含父类也包含接口，按简单名首字母大写区分
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
        // Kotlin 属性通过 val/var 形式声明，绑定到所属类的逻辑与 Java 字段一致
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

    /** 处理磁盘上的非源码文件（配置、SPI 等），转交给 [indexResourceText]。 */
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

    /**
     * 处理资源文件：分类登记，并对 META-INF/services 下的 SPI 文件解析出实现类清单。
     * textProvider 使用懒加载形式，仅在需要解析 SPI 内容时才读取。
     */
    private fun indexResourceText(
        relativePath: String,
        textProvider: () -> String?,
        resources: MutableMap<String, JvmResourceSymbol>,
        serviceFiles: MutableMap<String, MutableList<JvmServiceProviderFile>>,
        virtualFileUrl: String?,
    ) {
        val kind = resourceKind(relativePath)
        // 不关心的资源类型直接跳过，避免污染索引
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
            // 解析 SPI 文件：按行拆分、去掉注释、去重，最终得到实现类列表
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

    /** 注册模块与包符号：模块名为空时跳过模块登记，包名为空时跳过包登记。 */
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

    /** 组装类符号实例，统一生成稳定 ID，便于跨次构建保持引用一致。 */
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

    /** 组装字段符号实例，默认按"直接值"角色登记类型引用。 */
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

    /**
     * 根据相对路径推断模块名：路径中包含 src 时取其前缀作为 Gradle 子模块路径，
     * 否则退化为根目录名。
     */
    private fun moduleName(rootName: String?, relativePath: String): String? {
        val parts = relativePath.split('/')
        val sourceIndex = parts.indexOf("src")
        if (sourceIndex > 0) {
            return parts.take(sourceIndex).joinToString(":")
        }
        return rootName
    }

    /** 读取磁盘文件全文，超过大小上限的文件直接跳过以控制内存占用。 */
    private fun readSmallText(file: Path): String? {
        checkCanceled()
        if (Files.size(file) > MAX_TEXT_FILE_BYTES) {
            return null
        }
        return runCatching { Files.readString(file, StandardCharsets.UTF_8) }.getOrNull()
    }

    /** 读取 VFS 文件全文，逻辑与 [readSmallText] 一致，用于虚拟文件系统路径。 */
    private fun readVirtualSmallText(file: VirtualFile): String? {
        checkCanceled()
        if (file.length > MAX_TEXT_FILE_BYTES) {
            return null
        }
        return runCatching { String(file.contentsToByteArray(), StandardCharsets.UTF_8) }.getOrNull()
    }

    /**
     * 用占位符替换方法体内容，避免方法体内的语句被字段正则误识别。
     * 仅保留方法签名结构以便后续字段扫描不被干扰。
     */
    private fun String.withoutMethodBodies(): String =
        replace(Regex("""(?s)\([^)]*\)\s*\{.*?\}"""), "(){}")

    /**
     * 将类型文本规范化为可用于索引的全限定名：剥离修饰符与泛型，按 import 表补全包名。
     * 对基本类型与全小写的本地名（无包前缀）返回 null，因为它们不构成跨类关系。
     */
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

    /** 解析 Java 源码中的 import 语句，构建简单名 → 全限定名的映射。 */
    private fun javaImports(text: String): Map<String, String> =
        javaImportRegex.findAll(text)
            .mapNotNull { match ->
                val qualifiedName = match.groupValues[1].takeUnless { name -> name.endsWith(".*") } ?: return@mapNotNull null
                qualifiedName.substringAfterLast('.') to qualifiedName
            }
            .toMap()

    /** 解析 Kotlin 源码中的 import 语句，支持 as 别名形式。 */
    private fun kotlinImports(text: String): Map<String, String> =
        kotlinImportRegex.findAll(text)
            .mapNotNull { match ->
                val qualifiedName = match.groupValues[1].takeUnless { name -> name.endsWith(".*") } ?: return@mapNotNull null
                val alias = match.groupValues.getOrNull(2)?.takeIf(String::isNotBlank)
                (alias ?: qualifiedName.substringAfterLast('.')) to qualifiedName
            }
            .toMap()

    /**
     * 通过类声明前的注解文本推断其架构原型（Controller/Service/...），
     * 用于在架构视图上区分不同职责的类。
     */
    private fun stereotypeFromNearbyAnnotations(text: String, startOffset: Int): JvmStereotype {
        // 取声明前 300 字符作为注解上下文窗口
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

    /** 将 Java 关键字映射到对应的 [JvmClassKind]，用于区分类、接口、枚举、记录与注解。 */
    private fun javaClassKind(keyword: String): JvmClassKind =
        when (keyword) {
            "interface" -> JvmClassKind.INTERFACE
            "enum" -> JvmClassKind.ENUM
            "record" -> JvmClassKind.RECORD
            "@interface" -> JvmClassKind.ANNOTATION
            else -> JvmClassKind.CLASS
        }

    /** 将 Kotlin 关键字映射到对应的 [JvmClassKind]。 */
    private fun kotlinClassKind(keyword: String): JvmClassKind =
        when (keyword) {
            "interface" -> JvmClassKind.INTERFACE
            "enum class" -> JvmClassKind.ENUM
            "annotation class" -> JvmClassKind.ANNOTATION
            "object" -> JvmClassKind.OBJECT
            else -> JvmClassKind.CLASS
        }

    /** 根据资源路径后缀与特征判定资源类型，便于在架构图上标识配置、SPI、MQ 等。 */
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

    /** 构造 [JvmSourceRef]，将相对路径作为展示用路径，VirtualFile URL 用于回跳定位。 */
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

    /** 包装进度检查，让用户在长任务中可随时取消。 */
    private fun checkCanceled() {
        ProgressManager.checkCanceled()
    }

    private companion object {
        // 单文件文本读取的大小上限，超过该大小视为非源码或生成文件，避免内存压力
        private const val MAX_TEXT_FILE_BYTES = 512 * 1024L
        // 不参与索引的目录名集合，主要覆盖构建产物、依赖与 IDE 缓存
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
        // 视为非关系型的基本类型与包装类型集合，类型解析时直接跳过
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
        // 匹配 Java package 声明
        private val javaPackageRegex = Regex("""(?m)^\s*package\s+([A-Za-z_][\w.]*)\s*;""")
        // 匹配 Kotlin package 声明
        private val kotlinPackageRegex = Regex("""(?m)^\s*package\s+([A-Za-z_][\w.]*)""")
        // 匹配 Java import（含 static）
        private val javaImportRegex = Regex("""(?m)^\s*import\s+(?:static\s+)?([A-Za-z_][\w.]*)(?:\s*;|;)""")
        // 匹配 Kotlin import（含 as 别名）
        private val kotlinImportRegex = Regex("""(?m)^\s*import\s+([A-Za-z_][\w.]*)(?:\s+as\s+([A-Za-z_][\w]*))?""")
        // 匹配 Java 类型声明，含修饰符、类型关键字、类名与继承/实现头部
        private val javaClassRegex = Regex(
            """(?m)(?:^|\s)(?:(?:public|protected|private|abstract|final|static|sealed|non-sealed)\s+)*(class|interface|enum|record|@interface)\s+([A-Za-z_][\w$]*)\s*([^;\{]*)""",
        )
        // 从 Java 类型头部提取 extends 后的父类名
        private val javaExtendsRegex = Regex("""\bextends\s+([A-Za-z_][\w.]*)""")
        // 从 Java 类型头部提取 implements 后的接口列表
        private val javaImplementsRegex = Regex("""\bimplements\s+([A-Za-z_][\w.,\s<>?]+)""")
        // 匹配 Kotlin 类型声明，含冒号后的继承列表
        private val kotlinClassRegex = Regex(
            """(?m)(?:^|\s)(class|interface|object|enum class|annotation class)\s+([A-Za-z_][\w]*)\s*(?::\s*([^{\n]+))?""",
        )
        // 匹配 Java 字段声明
        private val fieldRegex = Regex(
            """(?m)^\s*(?:(?:private|protected|public|static|final|volatile|transient)\s+)*([A-Z][A-Za-z0-9_.$<>?,\s]*)\s+([a-zA-Z_][\w]*)\s*(?:=|;)""",
        )
        // 匹配 Kotlin 属性声明
        private val kotlinPropertyRegex = Regex(
            """(?m)^\s*(?:private|protected|public|internal)?\s*(?:val|var)\s+([a-zA-Z_][\w]*)\s*:\s*([A-Z][A-Za-z0-9_.$<>?]*)""",
        )
    }
}
