package com.charmnight.linkgraph.jvm.index

import com.intellij.ide.highlighter.JavaFileType
import com.charmnight.linkgraph.source.SourceOrigin
import com.charmnight.linkgraph.source.AttachedJarClassKind
import com.charmnight.linkgraph.source.AttachedJarClassEntry
import com.charmnight.linkgraph.source.AttachedJarIndex
import com.charmnight.linkgraph.source.AttachedJarServiceFileEntry
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiAnonymousClass
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiType
import com.intellij.psi.PsiWildcardType
import com.intellij.psi.javadoc.PsiDocComment
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.search.searches.AllClassesSearch
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files

/** 符号索引构建过程中的追踪事件，记录阶段名、起始纳秒与详情。 */
data class JvmSymbolIndexBuildTraceEvent(
    val stage: String,
    val startedAtNanos: Long,
    val details: () -> List<String>,
)

/**
 * JVM 符号索引构建器：通过 PSI 扫描项目源码、附加 jar 等位置，
 * 构建模块/包/类/字段/方法/资源等符号的统一索引。
 */
class JvmSymbolIndexBuilder(
    /** 当前项目实例。 */
    private val project: Project,
    /** 可选的构建追踪回调。 */
    private val trace: ((JvmSymbolIndexBuildTraceEvent) -> Unit)? = null,
    /** 文件过滤谓词，控制扫描范围。 */
    private val fileFilter: (VirtualFile) -> Boolean = { true },
    /** 附加 jar 索引提供者。 */
    private val attachedJarIndexProvider: () -> AttachedJarIndex = { AttachedJarIndex() },
) : IndexerSharedHelpers {
    // P2-1 真正的架构分解：外部依赖索引委托给独立的 ExternalLibraryIndexer
    private val externalLibraryIndexer = ExternalLibraryIndexer(project, this)
    /** 按预算构建 JVM 符号索引。 */
    fun build(budget: com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget = com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget()): JvmSymbolIndex {
        // P2-1: 用 SymbolIndexBuildContext 统一管理可变状态，后续 index 方法逐步迁移为接收 context 参数。
        val ctx = SymbolIndexBuildContext(
            project = project,
            budget = budget,
            fileFilter = fileFilter,
        )
        val modules = ctx.modules
        val packages = ctx.packages
        val classes = ctx.classes
        val methods = ctx.methods
        val fields = ctx.fields
        val resources = ctx.resources
        val serviceFiles = ctx.serviceFiles
        val psiManager = PsiManager.getInstance(project)

        val contentScanStartedAt = System.nanoTime()
        val fileIndex = ProjectFileIndex.getInstance(project)
        ProjectRootManager.getInstance(project).contentRoots.forEach { root ->
            VfsUtilCore.iterateChildrenRecursively(root, null) { file ->
                checkCanceled()
                if (project.isDisposed) {
                    return@iterateChildrenRecursively false
                }
                if (fileIndex.isExcluded(file)) {
                    return@iterateChildrenRecursively !file.isDirectory
                }
                if (file.isDirectory) {
                    return@iterateChildrenRecursively shouldDescendContentRootDirectory(root, file)
                }
                if (!shouldIndexContentRootFile(root, file)) {
                    return@iterateChildrenRecursively true
                }
                if (!fileFilter(file)) {
                    return@iterateChildrenRecursively true
                }
                if (!budget.includeTests && fileIndex.isInTestSourceContent(file)) {
                    return@iterateChildrenRecursively true
                }
                when (file.extension?.lowercase()) {
                    "java" -> {
                        if (!ctx.isFull()) {
                            (psiManager.findFile(file) as? PsiJavaFile)?.classes.orEmpty().forEach { psiClass ->
                                indexPsiClass(file, psiClass, ctx)
                                indexFrameworkResources(file, psiClass, resources)
                            }
                        }
                    }
                    "kt", "kts" -> {
                        if (!ctx.isFull()) {
                            val ktFile = psiManager.findFile(file) as? KtFile
                            ktFile?.collectDescendantsOfType<KtClass>().orEmpty()
                                .filter { ktClass -> PsiTreeUtil.getParentOfType(ktClass, KtClass::class.java, true) == null }
                                .forEach { ktClass ->
                                    ktClass.toLightClass()?.let { psiClass ->
                                        indexPsiClass(file, psiClass, ctx)
                                        indexFrameworkResources(file, psiClass, resources)
                                    }
                                }
                        }
                    }
                    "scala" -> {
                        if (classes.size < budget.maxProjectClasses) {
                            indexScalaSourceFile(file, ctx)
                        }
                    }
                    else -> {
                        val resource = indexResource(file, resources) ?: return@iterateChildrenRecursively true
                        if (resource.kind == JvmResourceKind.SPI_SERVICE_FILE) {
                            val providers = providerClassNames(file)
                            serviceFiles.getOrPut(resource.path.substringAfter("META-INF/services/")) { mutableListOf() } +=
                                JvmServiceProviderFile(
                                    serviceInterfaceName = resource.path.substringAfter("META-INF/services/"),
                                    providerClassNames = providers,
                                    resource = resource,
                                    origin = resource.origin,
                                )
                        }
                    }
                }
                true
            }
        }
        traceStage("jvmSymbolIndex.contentRoots") {
            contentScanStartedAt to listOf(
                "modules=${modules.size}",
                "packages=${packages.size}",
                "classes=${classes.size}",
                "methods=${methods.size}",
                "fields=${fields.size}",
                "resources=${resources.size}",
                "serviceFiles=${serviceFiles.values.sumOf { it.size }}",
            )
        }
        checkCanceled()
        if (classes.size < budget.maxProjectClasses) {
            indexProjectScopeClasses(ctx)
        }
        if (classes.isEmpty() && budget.maxProjectClasses > 0) {
            val fallbackStartedAt = System.nanoTime()
            val fallbackStats = indexProjectBaseSourcesFallback(
                psiManager = psiManager,
                ctx = ctx,
            )
            traceStage("jvmSymbolIndex.projectBaseFallback") {
                fallbackStartedAt to listOf(
                    "files=${fallbackStats.files}",
                    "javaFiles=${fallbackStats.javaFiles}",
                    "kotlinFiles=${fallbackStats.kotlinFiles}",
                    "scalaFiles=${fallbackStats.scalaFiles}",
                    "resourceFiles=${fallbackStats.resourceFiles}",
                    "classes=${classes.size}",
                    "methods=${methods.size}",
                    "fields=${fields.size}",
                    "resources=${resources.size}",
                    "serviceFiles=${serviceFiles.values.sumOf { it.size }}",
                )
            }
        }

        if (budget.includeUserAttachedJars) {
            checkCanceled()
            val attachedStartedAt = System.nanoTime()
            indexAttachedJars(
                attachedJarIndex = attachedJarIndexProvider(),
                classes = classes,
                resources = resources,
                serviceFiles = serviceFiles,
                methods = methods,
                fields = fields,
                budget = budget,
            )
            traceStage("jvmSymbolIndex.attachedJars") {
                attachedStartedAt to listOf(
                    "classes=${classes.size}",
                    "methods=${methods.size}",
                    "fields=${fields.size}",
                    "resources=${resources.size}",
                    "serviceFiles=${serviceFiles.values.sumOf { it.size }}",
                )
            }
        }
        if (budget.includeExternalLibraries || budget.includeJdk) {
            checkCanceled()
            val externalStartedAt = System.nanoTime()
            externalLibraryIndexer.indexDirectExternalClasses(ctx)
            externalLibraryIndexer.indexLibraryServiceFiles(ctx)
            externalLibraryIndexer.ensureServiceTypesIndexed(ctx)
            traceStage("jvmSymbolIndex.externalLibraries") {
                externalStartedAt to listOf(
                    "classes=${classes.size}",
                    "externalClasses=${classes.values.count { it.external }}",
                    "methods=${methods.size}",
                    "fields=${fields.size}",
                    "resources=${resources.size}",
                    "serviceFiles=${serviceFiles.values.sumOf { it.size }}",
                )
            }
        }
        if (budget.includeJdk) {
            checkCanceled()
            val jdkStartedAt = System.nanoTime()
            externalLibraryIndexer.indexJdkServiceFiles(ctx)
            externalLibraryIndexer.ensureServiceTypesIndexed(ctx)
            traceStage("jvmSymbolIndex.jdk") {
                jdkStartedAt to listOf(
                    "classes=${classes.size}",
                    "jdkClasses=${classes.values.count { it.jdk }}",
                    "methods=${methods.size}",
                    "fields=${fields.size}",
                    "resources=${resources.size}",
                    "serviceFiles=${serviceFiles.values.sumOf { it.size }}",
                )
            }
        }

        return JvmSymbolIndex(
            modulesByName = modules,
            packagesByName = packages,
            classesByQualifiedName = classes,
            methodsBySignature = methods,
            fieldsByQualifiedName = fields,
            resourcesByPath = resources,
            serviceProviderIndex = JvmServiceProviderIndex(serviceFiles),
        )
    }

    /** 兜底扫描阶段的统计计数，按语言/资源类型分组，用于追踪埋点。 */
    private data class ProjectBaseFallbackStats(
        var files: Int = 0,
        var javaFiles: Int = 0,
        var kotlinFiles: Int = 0,
        var scalaFiles: Int = 0,
        var resourceFiles: Int = 0,
    )

    /**
     * 当 content root 扫描未采集到任何类时的兜底实现：
     * 直接基于 [project] 基础路径递归遍历文件系统，确保即使 IntelliJ 索引不可用也能产出一个最小可用的符号集。
     */
    private fun indexProjectBaseSourcesFallback(
        psiManager: PsiManager,
        ctx: SymbolIndexBuildContext,
    ): ProjectBaseFallbackStats {
        val modules = ctx.modules
        val packages = ctx.packages
        val classes = ctx.classes
        val methods = ctx.methods
        val fields = ctx.fields
        val resources = ctx.resources
        val serviceFiles = ctx.serviceFiles
        val budget = ctx.budget
        val basePath = project.basePath
            ?.takeIf(String::isNotBlank)
            ?.let { path -> runCatching { java.nio.file.Path.of(path).normalize() }.getOrNull() }
            ?: return ProjectBaseFallbackStats()
        if (!Files.isDirectory(basePath)) {
            return ProjectBaseFallbackStats()
        }
        val localFileSystem = LocalFileSystem.getInstance()
        val stats = ProjectBaseFallbackStats()
        Files.walk(basePath).use { paths ->
            paths
                .filter(Files::isRegularFile)
                .forEach { path ->
                    checkCanceled()
                    if (classes.size >= budget.maxProjectClasses && !path.isProjectResourcePath()) {
                        return@forEach
                    }
                    val relativePath = runCatching {
                        basePath.relativize(path.normalize()).toString().replace('\\', '/')
                    }.getOrNull()?.takeIf(String::isNotBlank) ?: return@forEach
                    if (!shouldIndexProjectBaseFallbackPath(relativePath, budget)) {
                        return@forEach
                    }
                    val file = localFileSystem.refreshAndFindFileByNioFile(path) ?: return@forEach
                    if (file.isDirectory || !fileFilter(file)) {
                        return@forEach
                    }
                    stats.files += 1
                    when (file.extension?.lowercase()) {
                        "java" -> {
                            stats.javaFiles += 1
                            indexFallbackJavaFile(
                                file = file,
                                relativePath = relativePath,
                                psiManager = psiManager,
                                modules = modules,
                                packages = packages,
                                classes = classes,
                                methods = methods,
                                fields = fields,
                                resources = resources,
                                budget = budget,
                            )
                        }
                        "kt", "kts" -> {
                            stats.kotlinFiles += 1
                            indexFallbackKotlinFile(file, psiManager, modules, packages, classes, methods, fields, budget)
                        }
                        "scala" -> {
                            stats.scalaFiles += 1
                            indexScalaSourceFile(file, ctx)
                        }
                        else -> {
                            val resource = indexResource(file, resources) ?: return@forEach
                            stats.resourceFiles += 1
                            if (resource.kind == JvmResourceKind.SPI_SERVICE_FILE) {
                                val providers = providerClassNames(file)
                                serviceFiles.getOrPut(resource.path.substringAfter("META-INF/services/")) { mutableListOf() } +=
                                    JvmServiceProviderFile(
                                        serviceInterfaceName = resource.path.substringAfter("META-INF/services/"),
                                        providerClassNames = providers,
                                        resource = resource,
                                        origin = resource.origin,
                                    )
                            }
                        }
                    }
                }
        }
        return stats
    }

    /** 判断当前路径是否属于典型的配置/文档资源，用于决定是否突破类预算限制继续索引。 */
    private fun java.nio.file.Path.isProjectResourcePath(): Boolean =
        fileName?.toString()?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase() in setOf(
            "xml",
            "yml",
            "yaml",
            "properties",
            "sql",
            "md",
        )

    /** 判断相对路径是否符合兜底扫描的收录规则：排除受控目录、按测试预算过滤、限定扩展名白名单。 */
    private fun shouldIndexProjectBaseFallbackPath(
        relativePath: String,
        budget: com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget,
    ): Boolean {
        val normalized = relativePath.replace('\\', '/').trim('/').takeIf(String::isNotBlank) ?: return false
        if (normalized.hasExcludedContentRootSegment()) {
            return false
        }
        if (!budget.includeTests && normalized.contains("/src/test/")) {
            return false
        }
        return normalized.substringAfterLast('.', missingDelimiterValue = "").lowercase() in setOf(
            "java",
            "kt",
            "kts",
            "scala",
            "xml",
            "yml",
            "yaml",
            "properties",
            "sql",
            "md",
        )
    }

    /** 兜底扫描单个 Java 文件：先尝试通过 PSI 解析，失败时使用 [fallbackPsiJavaFile] 重建，再补充继承信息。 */
    private fun indexFallbackJavaFile(
        file: VirtualFile,
        relativePath: String,
        psiManager: PsiManager,
        modules: MutableMap<String, JvmModuleSymbol>,
        packages: MutableMap<String, JvmPackageSymbol>,
        classes: MutableMap<String, JvmClassSymbol>,
        methods: MutableMap<String, JvmMethodSymbol>,
        fields: MutableMap<String, JvmFieldSymbol>,
        resources: MutableMap<String, JvmResourceSymbol>,
        budget: com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget,
    ) {
        // 从 individual maps 构造 ctx 供已迁移的 indexPsiClass 调用使用
        val ctx = SymbolIndexBuildContext(project, budget, fileFilter, modules, packages, classes, methods, fields, resources)
        if (classes.size >= budget.maxProjectClasses) {
            return
        }
        val sourceText = readVirtualFileText(file)
        val psiJavaFile = (psiManager.findFile(file) as? PsiJavaFile)
            ?: fallbackPsiJavaFile(relativePath, file.name, sourceText)
            ?: return
        val fallbackClassInfo = sourceText
            ?.let { text -> FallbackJavaClassInfoExtractor.extract(relativePath, text) }
            .orEmpty()
        psiJavaFile.classes.forEach { psiClass ->
            if (classes.size >= budget.maxProjectClasses) {
                return@forEach
            }
            indexPsiClass(file, psiClass, ctx)
            applyFallbackJavaClassInfo(psiJavaFile, psiClass, classes, fallbackClassInfo)
            indexFrameworkResources(file, psiClass, resources)
        }
    }

    /** 容错读取 [VirtualFile] 文本，捕获编码异常返回 null，避免单文件失败拖垮整次扫描。 */
    private fun readVirtualFileText(file: VirtualFile): String? =
        runCatching { String(file.contentsToByteArray(), file.charset) }.getOrNull()

    /** 在不依赖项目索引的情况下，使用 [PsiFileFactory] 直接由文本创建一个临时 [PsiJavaFile] 供词法/语法解析。 */
    private fun fallbackPsiJavaFile(
        relativePath: String,
        fallbackFileName: String,
        text: String?,
    ): PsiJavaFile? {
        val sourceText = text ?: return null
        val fileName = relativePath.substringAfterLast('/').ifBlank { fallbackFileName }
        return PsiFileFactory.getInstance(project)
            .createFileFromText(fileName, JavaFileType.INSTANCE, sourceText) as? PsiJavaFile
    }

    /** 将兜底提取器得到的父类/接口名称合并到已索引的 [JvmClassSymbol] 中，补充 PSI 在简化模式下遗漏的继承关系。 */
    private fun applyFallbackJavaClassInfo(
        psiJavaFile: PsiJavaFile,
        psiClass: PsiClass,
        classes: MutableMap<String, JvmClassSymbol>,
        fallbackClassInfo: Map<String, FallbackJavaClassInfo>,
    ) {
        val qualifiedName = psiClass.qualifiedName?.takeIf(String::isNotBlank)
            ?: psiClass.name
                ?.takeIf(String::isNotBlank)
                ?.let { simpleName ->
                    listOf(psiJavaFile.packageName, simpleName)
                        .filter(String::isNotBlank)
                        .joinToString(".")
                }
            ?: return
        val fallback = fallbackClassInfo[qualifiedName] ?: return
        val current = classes[qualifiedName] ?: return
        val fallbackSuperClassName = fallback.extendsNames
            .firstOrNull()
            ?.takeIf { current.kind != JvmClassKind.INTERFACE }
            ?.takeUnless { name -> name == "java.lang.Object" }
        val fallbackInterfaceNames = if (current.kind == JvmClassKind.INTERFACE) {
            fallback.extendsNames + fallback.implementsNames
        } else {
            fallback.implementsNames
        }
        classes[qualifiedName] = current.copy(
            superClassName = current.superClassName ?: fallbackSuperClassName,
            interfaceNames = (current.interfaceNames + fallbackInterfaceNames)
                .filter(String::isNotBlank)
                .distinct(),
        )
    }

    /** 兜底扫描 Kotlin 文件：取出顶层 [KtClass] 转 light PSI 后按通用流程索引，保证 Kotlin 类不被遗漏。 */
    private fun indexFallbackKotlinFile(
        file: VirtualFile,
        psiManager: PsiManager,
        modules: MutableMap<String, JvmModuleSymbol>,
        packages: MutableMap<String, JvmPackageSymbol>,
        classes: MutableMap<String, JvmClassSymbol>,
        methods: MutableMap<String, JvmMethodSymbol>,
        fields: MutableMap<String, JvmFieldSymbol>,
        budget: com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget,
    ) {
        // 从 individual maps 构造 ctx 供已迁移的 indexPsiClass 调用使用
        val ctx = SymbolIndexBuildContext(project, budget, fileFilter, modules, packages, classes, methods, fields)
        if (classes.size >= budget.maxProjectClasses) {
            return
        }
        val ktFile = psiManager.findFile(file) as? KtFile ?: return
        ktFile.collectDescendantsOfType<KtClass>()
            .filter { ktClass -> PsiTreeUtil.getParentOfType(ktClass, KtClass::class.java, true) == null }
            .forEach { ktClass ->
                if (classes.size >= budget.maxProjectClasses) {
                    return@forEach
                }
                ktClass.toLightClass()?.let { psiClass ->
                    indexPsiClass(file, psiClass, ctx)
                }
            }
    }

    /** 判断 content root 内的某个目录是否应当继续向下递归：用于剔除构建产物、版本控制等噪声目录。 */
    private fun shouldDescendContentRootDirectory(
        root: VirtualFile,
        directory: VirtualFile,
    ): Boolean {
        val relativePath = contentRootRelativePath(root, directory)
        if (relativePath.isBlank()) {
            return true
        }
        return !relativePath.hasExcludedContentRootSegment()
    }

    /** 判断 content root 内的文件是否应被纳入索引，主要过滤掉排除目录下的文件。 */
    private fun shouldIndexContentRootFile(
        root: VirtualFile,
        file: VirtualFile,
    ): Boolean =
        !contentRootRelativePath(root, file).hasExcludedContentRootSegment()

    /** 计算文件相对 content root 的归一化路径（'/' 分隔），失败时退化为绝对路径，用于排除规则匹配。 */
    private fun contentRootRelativePath(
        root: VirtualFile,
        file: VirtualFile,
    ): String =
        (VfsUtilCore.getRelativePath(file, root, '/') ?: file.path).replace('\\', '/')

    /** 在构建过程的关键节点上报一条追踪事件；若未注入 [trace] 回调则直接跳过，避免无谓的字符串构造。 */
    private fun traceStage(
        stage: String,
        details: () -> Pair<Long, List<String>>,
    ) {
        val traceSink = trace ?: return
        val (startedAtNanos, traceDetails) = details()
        traceSink(
            JvmSymbolIndexBuildTraceEvent(
                stage = stage,
                startedAtNanos = startedAtNanos,
                details = { traceDetails },
            ),
        )
    }

    /**
     * 抽取项目类引用到的外部类型（父类、接口、字段类型、方法签名、异常），并在预算内将这些外部类
     * 通过 PSI 反查补充进索引，使得依赖链路在图谱中可被呈现。
     */
    private fun indexDirectExternalClasses(
        classes: MutableMap<String, JvmClassSymbol>,
        methods: MutableMap<String, JvmMethodSymbol>,
        fields: MutableMap<String, JvmFieldSymbol>,
        budget: com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget,
    ) {
        checkCanceled()
        // 同一 build 周期内的 PSI findClass 缓存：避免对同一个外部类名重复走 project+all 双 scope 查找。
        // 项目越大、外部依赖越深，这个缓存的收益越显著（从 O(N²) 降到 O(N)）。
        val findClassCache = mutableMapOf<String, PsiClass?>()
        val findClassByProjectScope: (String) -> PsiClass? = { name ->
            findClassCache.getOrPut("project:$name") {
                JavaPsiFacade.getInstance(project).findClass(name, GlobalSearchScope.projectScope(project))
            } ?: null
        }
        val findClassByAllScope: (String) -> PsiClass? = { name ->
            findClassCache.getOrPut("all:$name") {
                JavaPsiFacade.getInstance(project).findClass(name, GlobalSearchScope.allScope(project))
            } ?: null
        }
        val externalNames = linkedSetOf<String>()
        classes.values
            .filterNot { symbol -> symbol.external }
            .forEach { symbol ->
                checkCanceled()
                val psiClass = findClassByProjectScope(symbol.qualifiedName) ?: return@forEach
                psiClass.superClass?.qualifiedName?.let(externalNames::add)
                psiClass.interfaces.mapNotNull(PsiClass::getQualifiedName).forEach(externalNames::add)
                psiClass.fields
                    .flatMap { field: PsiField -> fieldTypeReferences(field.type, symbol.packageName).map(JvmFieldTypeReference::typeName) }
                    .forEach(externalNames::add)
                psiClass.methods.forEach { method ->
                    canonicalTypeText(method.returnType)?.let(externalNames::add)
                    method.parameterList.parameters.mapNotNull { parameter -> canonicalTypeText(parameter.type) }.forEach(externalNames::add)
                    method.throwsList.referencedTypes.mapNotNull(::canonicalTypeText).forEach(externalNames::add)
                }
            }
        val facade = JavaPsiFacade.getInstance(project)
        val existingProjectNames = classes.keys.toSet()
        externalNames
            .filter { name -> name !in existingProjectNames && name != "java.lang.Object" }
            .filter { name -> if (name.isJdkQualifiedName()) budget.includeJdk else budget.includeExternalLibraries }
            .take((budget.maxExternalClasses - classes.values.count { symbol -> symbol.external }).coerceAtLeast(0))
            .forEach { qualifiedName ->
                checkCanceled()
                val psiClass = findClassByAllScope(qualifiedName) ?: return@forEach
                val navigationFile = psiClass.navigationElement?.containingFile?.virtualFile ?: psiClass.containingFile?.virtualFile
                val origin = navigationFile?.let(::sourceOriginForExternalFile)
                    ?: if (qualifiedName.startsWith("java.") || qualifiedName.startsWith("javax.") || qualifiedName.startsWith("jdk.")) {
                        SourceOrigin.JDK_CLASS
                    } else {
                        SourceOrigin.LIBRARY_CLASS_JAR
                    }
                classes.putIfAbsent(
                    qualifiedName,
                    JvmClassSymbol(
                        id = stableJvmId("class", qualifiedName),
                        qualifiedName = qualifiedName,
                        simpleName = psiClass.name ?: qualifiedName.substringAfterLast('.'),
                        packageName = packageNameForPsiClass(navigationFile, psiClass),
                        moduleName = externalModuleName(navigationFile, origin),
                        kind = classKind(psiClass),
                        stereotype = JvmStereotype.UNKNOWN,
                        abstract = psiClass.hasModifierProperty(com.intellij.psi.PsiModifier.ABSTRACT),
                        external = true,
                        library = origin in setOf(SourceOrigin.LIBRARY_SOURCE_JAR, SourceOrigin.LIBRARY_CLASS_JAR),
                        jdk = origin in setOf(SourceOrigin.JDK_SOURCE, SourceOrigin.JDK_CLASS),
                        source = navigationFile?.let { file -> sourceRef(file, psiClass.navigationElement ?: psiClass).copy(decompiled = origin in setOf(SourceOrigin.LIBRARY_CLASS_JAR, SourceOrigin.JDK_CLASS)) },
                        origin = origin,
                    ),
                )
                indexExternalPsiMembers(
                    ownerClassName = qualifiedName,
                    psiClass = psiClass,
                    methods = methods,
                    fields = fields,
                    origin = origin,
                    sourceFile = navigationFile,
                    budget = budget,
                )
            }
    }

    /** 遍历项目依赖 jar（classes + sources）的 META-INF/services 目录，把 SPI 配置文件收录为服务发现资源。 */
    private fun indexLibraryServiceFiles(
        resources: MutableMap<String, JvmResourceSymbol>,
        serviceFiles: MutableMap<String, MutableList<JvmServiceProviderFile>>,
        budget: com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget,
    ) {
        val roots = ProjectRootManager.getInstance(project).orderEntries().classes().roots.asSequence() +
            ProjectRootManager.getInstance(project).orderEntries().sources().roots.asSequence()
        roots.distinctBy(VirtualFile::getUrl).forEach { root ->
            checkCanceled()
            val serviceRoot = root.findFileByRelativePath("META-INF/services") ?: return@forEach
            if (!serviceRoot.isDirectory) {
                return@forEach
            }
            serviceRoot.children
                .asSequence()
                .filter { file -> !file.isDirectory && file.name.isNotBlank() }
                .filter { file -> if (file.url.startsWith("jrt://")) budget.includeJdk else budget.includeExternalLibraries }
                .forEach { file ->
                    checkCanceled()
                    val providers = providerClassNames(file)
                    if (providers.isEmpty()) {
                        return@forEach
                    }
                    val origin = sourceOriginForExternalFile(file)
                    val resourcePath = displayPathForExternalResource(file)
                    val resource = JvmResourceSymbol(
                        id = stableJvmId("resource", resourcePath),
                        path = resourcePath,
                        kind = JvmResourceKind.SPI_SERVICE_FILE,
                        source = JvmSourceRef(
                            displayPath = resourcePath,
                            virtualFileUrl = file.url,
                            startLine = 1,
                            endLine = FileDocumentManager.getInstance().getDocument(file)?.lineCount,
                            decompiled = origin in setOf(SourceOrigin.LIBRARY_CLASS_JAR, SourceOrigin.JDK_CLASS),
                        ),
                        origin = origin,
                    )
                    resources.putIfAbsent(resource.path, resource)
                    serviceFiles.getOrPut(file.name) { mutableListOf() } +=
                        JvmServiceProviderFile(
                            serviceInterfaceName = file.name,
                            providerClassNames = providers,
                            resource = resource,
                            origin = origin,
                        )
                }
        }
    }

/** 通过 jrt 文件系统枚举 JDK 各模块下的 META-INF/services，将 JDK 自带的 SPI 配置补充到服务索引中。 */
    private fun indexJdkServiceFiles(
        resources: MutableMap<String, JvmResourceSymbol>,
        serviceFiles: MutableMap<String, MutableList<JvmServiceProviderFile>>,
    ) {
        val jrt = runCatching {
            FileSystems.getFileSystem(URI.create("jrt:/"))
        }.recoverCatching {
            FileSystems.newFileSystem(URI.create("jrt:/"), emptyMap<String, Any>())
        }.getOrNull() ?: return
        val modulesRoot = jrt.getPath("/modules")
        if (!Files.isDirectory(modulesRoot)) {
            return
        }
        runCatching {
            Files.list(modulesRoot).use { modules ->
                modules
                    .filter(Files::isDirectory)
                    .forEach { modulePath ->
                        checkCanceled()
                        val serviceRoot = modulePath.resolve("META-INF/services")
                        if (!Files.isDirectory(serviceRoot)) {
                            return@forEach
                        }
                        Files.list(serviceRoot).use { files ->
                            files
                                .filter(Files::isRegularFile)
                                .forEach { file ->
                                    checkCanceled()
                                    val serviceName = file.fileName.toString().takeIf(String::isNotBlank)
                                        ?: return@forEach
                                    val text = runCatching { Files.readString(file, StandardCharsets.UTF_8) }
                                        .getOrDefault("")
                                    val providers = providerClassNames(text)
                                    if (providers.isEmpty()) {
                                        return@forEach
                                    }
                                    val moduleName = modulePath.fileName.toString()
                                    val resourcePath = "$moduleName/META-INF/services/$serviceName"
                                    val virtualFileUrl = "jrt://${jdkHomePath()}!/$resourcePath"
                                    if (serviceFiles[serviceName].orEmpty().any { existing ->
                                            existing.resource.source?.virtualFileUrl == virtualFileUrl
                                        }
                                    ) {
                                        return@forEach
                                    }
                                    val resource = JvmResourceSymbol(
                                        id = stableJvmId("resource", resourcePath),
                                        path = resourcePath,
                                        kind = JvmResourceKind.SPI_SERVICE_FILE,
                                        source = JvmSourceRef(
                                            displayPath = resourcePath,
                                            virtualFileUrl = virtualFileUrl,
                                            startLine = 1,
                                            endLine = text.lineSequence().count().coerceAtLeast(1),
                                            decompiled = false,
                                        ),
                                        origin = SourceOrigin.JDK_CLASS,
                                    )
                                    resources.putIfAbsent(resource.path, resource)
                                    serviceFiles.getOrPut(serviceName) { mutableListOf() } +=
                                        JvmServiceProviderFile(
                                            serviceInterfaceName = serviceName,
                                            providerClassNames = providers,
                                            resource = resource,
                                            origin = SourceOrigin.JDK_CLASS,
                                        )
                                }
                        }
                    }
            }
        }
    }

    /** 处理用户手动附加的 jar：将其中的类、字段、方法和 SPI 服务文件按预算追加到索引中。 */
    private fun indexAttachedJars(
        attachedJarIndex: AttachedJarIndex,
        classes: MutableMap<String, JvmClassSymbol>,
        resources: MutableMap<String, JvmResourceSymbol>,
        serviceFiles: MutableMap<String, MutableList<JvmServiceProviderFile>>,
        methods: MutableMap<String, JvmMethodSymbol>,
        fields: MutableMap<String, JvmFieldSymbol>,
        budget: com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget,
    ) {
        val remaining = (budget.maxExternalClasses - classes.values.count { symbol -> symbol.external }).coerceAtLeast(0)
        attachedJarIndex.classesByQualifiedName.values
            .sortedBy { entry -> entry.qualifiedName }
            .take(remaining)
            .forEach { entry ->
                checkCanceled()
                val packageName = packageNameForClassEntry(entry.qualifiedName, entry.classEntryName, entry.sourceEntryName)
                val sourceRef = JvmSourceRef(
                    displayPath = entry.displayPath,
                    virtualFileUrl = if (entry.sourceEntryName != null && entry.sourceJarPath != null) {
                        "jar://${entry.sourceJarPath}!/${entry.sourceEntryName}"
                    } else {
                        entry.classEntryName?.let { classEntry -> "jar://${entry.classJarPath}!/$classEntry" }
                    },
                    startLine = 1,
                    endLine = null,
                    decompiled = entry.sourceEntryName == null,
                )
                val classSymbol = JvmClassSymbol(
                    id = stableJvmId("class", entry.qualifiedName),
                    qualifiedName = entry.qualifiedName,
                    simpleName = entry.qualifiedName.substringAfterLast('.').substringBefore('$'),
                    packageName = packageName,
                    moduleName = "attached:${java.nio.file.Path.of(entry.classJarPath).fileName}",
                    kind = entry.kind.toJvmClassKind(),
                    external = true,
                    library = true,
                    abstract = entry.kind == AttachedJarClassKind.INTERFACE,
                    source = sourceRef,
                    origin = if (entry.sourceEntryName != null) {
                        SourceOrigin.USER_ATTACHED_SOURCE_JAR
                    } else {
                        SourceOrigin.USER_ATTACHED_CLASS_JAR
                    },
                    superClassName = entry.superClassName?.takeUnless { name -> name == "java.lang.Object" },
                    interfaceNames = entry.interfaceNames,
                )
                classes.putIfAbsent(entry.qualifiedName, classSymbol)
                indexAttachedJarMembers(entry, classSymbol, methods, fields, budget)
            }
        attachedJarIndex.serviceFilesByInterfaceName.values.flatten()
            .forEach { serviceFile ->
                checkCanceled()
                val resourcePath = "${serviceFile.resourceJarPath}!/${serviceFile.resourceEntryName}"
                val resource = JvmResourceSymbol(
                    id = stableJvmId("resource", resourcePath),
                    path = resourcePath,
                    kind = JvmResourceKind.SPI_SERVICE_FILE,
                    source = JvmSourceRef(
                        displayPath = resourcePath,
                        virtualFileUrl = "jar://${serviceFile.resourceJarPath}!/${serviceFile.resourceEntryName}",
                        startLine = 1,
                        endLine = null,
                        decompiled = false,
                    ),
                    origin = serviceFile.origin,
                )
                resources.putIfAbsent(resource.path, resource)
                serviceFiles.getOrPut(serviceFile.serviceInterfaceName) { mutableListOf() } +=
                    JvmServiceProviderFile(
                        serviceInterfaceName = serviceFile.serviceInterfaceName,
                        providerClassNames = serviceFile.providerClassNames,
                        resource = resource,
                        origin = resource.origin,
                    )
                ensureAttachedServiceTypesIndexed(
                    serviceFile = serviceFile,
                    attachedJarIndex = attachedJarIndex,
                    classes = classes,
                    methods = methods,
                    fields = fields,
                    budget = budget,
                )
            }
    }

    /** 把 SPI 服务文件中出现的接口名/实现类名补齐到类索引，确保服务提供关系两端都能在图谱中找到节点。 */
    private fun ensureServiceTypesIndexed(
        serviceFiles: MutableMap<String, MutableList<JvmServiceProviderFile>>,
        classes: MutableMap<String, JvmClassSymbol>,
        methods: MutableMap<String, JvmMethodSymbol>,
        fields: MutableMap<String, JvmFieldSymbol>,
        budget: com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget,
    ) {
        val facade = JavaPsiFacade.getInstance(project)
        serviceFiles.values
            .flatten()
            .asSequence()
            .flatMap { serviceFile ->
                sequenceOf(serviceFile.serviceInterfaceName) + serviceFile.providerClassNames.asSequence()
            }
            .distinct()
            .filter { qualifiedName -> qualifiedName !in classes }
            .filter { qualifiedName -> if (qualifiedName.isJdkQualifiedName()) budget.includeJdk else budget.includeExternalLibraries }
            .take((budget.maxExternalClasses - classes.values.count { symbol -> symbol.external }).coerceAtLeast(0))
            .forEach { qualifiedName ->
                checkCanceled()
                val psiClass = facade.findClass(qualifiedName, GlobalSearchScope.allScope(project)) ?: return@forEach
                val navigationFile = psiClass.navigationElement?.containingFile?.virtualFile ?: psiClass.containingFile?.virtualFile
                val origin = navigationFile?.let(::sourceOriginForExternalFile)
                    ?: if (qualifiedName.isJdkQualifiedName()) SourceOrigin.JDK_CLASS else SourceOrigin.LIBRARY_CLASS_JAR
                classes.putIfAbsent(
                    qualifiedName,
                    JvmClassSymbol(
                        id = stableJvmId("class", qualifiedName),
                        qualifiedName = qualifiedName,
                        simpleName = psiClass.name ?: qualifiedName.substringAfterLast('.'),
                        packageName = packageNameForPsiClass(navigationFile, psiClass),
                        moduleName = externalModuleName(navigationFile, origin),
                        kind = classKind(psiClass),
                        stereotype = JvmStereotype.UNKNOWN,
                        external = true,
                        library = origin in setOf(SourceOrigin.LIBRARY_SOURCE_JAR, SourceOrigin.LIBRARY_CLASS_JAR),
                        jdk = origin in setOf(SourceOrigin.JDK_SOURCE, SourceOrigin.JDK_CLASS),
                        abstract = psiClass.hasModifierProperty(com.intellij.psi.PsiModifier.ABSTRACT),
                        source = navigationFile?.let { file ->
                            sourceRef(file, psiClass.navigationElement ?: psiClass)
                                .copy(decompiled = origin in setOf(SourceOrigin.LIBRARY_CLASS_JAR, SourceOrigin.JDK_CLASS))
                        },
                        origin = origin,
                    ),
                )
                indexExternalPsiMembers(
                    ownerClassName = qualifiedName,
                    psiClass = psiClass,
                    methods = methods,
                    fields = fields,
                    origin = origin,
                    sourceFile = navigationFile,
                    budget = budget,
                )
            }
    }

    /** 在附加 jar 索引中补齐 SPI 服务接口/实现类，避免附加 jar 的服务发现链路因缺类而断链。 */
    private fun ensureAttachedServiceTypesIndexed(
        serviceFile: AttachedJarServiceFileEntry,
        attachedJarIndex: AttachedJarIndex,
        classes: MutableMap<String, JvmClassSymbol>,
        methods: MutableMap<String, JvmMethodSymbol>,
        fields: MutableMap<String, JvmFieldSymbol>,
        budget: com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget,
    ) {
        sequenceOf(serviceFile.serviceInterfaceName)
            .plus(serviceFile.providerClassNames.asSequence())
            .distinct()
            .forEach { qualifiedName ->
                checkCanceled()
                if (classes.size >= budget.maxProjectClasses + budget.maxExternalClasses) {
                    return@forEach
                }
                val entry = attachedJarIndex.findClass(qualifiedName) ?: return@forEach
                val packageName = packageNameForClassEntry(entry.qualifiedName, entry.classEntryName, entry.sourceEntryName)
                val classSymbol = JvmClassSymbol(
                    id = stableJvmId("class", entry.qualifiedName),
                    qualifiedName = entry.qualifiedName,
                    simpleName = entry.qualifiedName.substringAfterLast('.').substringBefore('$'),
                    packageName = packageName,
                    moduleName = "attached:${java.nio.file.Path.of(entry.classJarPath).fileName}",
                    kind = entry.kind.toJvmClassKind(),
                    external = true,
                    library = true,
                    abstract = entry.kind == AttachedJarClassKind.INTERFACE,
                    source = JvmSourceRef(
                        displayPath = entry.displayPath,
                        virtualFileUrl = if (entry.sourceEntryName != null && entry.sourceJarPath != null) {
                            "jar://${entry.sourceJarPath}!/${entry.sourceEntryName}"
                        } else {
                            entry.classEntryName?.let { classEntry -> "jar://${entry.classJarPath}!/$classEntry" }
                        },
                        startLine = 1,
                        endLine = null,
                        decompiled = entry.sourceEntryName == null,
                    ),
                    origin = if (entry.sourceEntryName != null) {
                        SourceOrigin.USER_ATTACHED_SOURCE_JAR
                    } else {
                        SourceOrigin.USER_ATTACHED_CLASS_JAR
                    },
                    superClassName = entry.superClassName?.takeUnless { name -> name == "java.lang.Object" },
                    interfaceNames = entry.interfaceNames,
                )
                classes.putIfAbsent(entry.qualifiedName, classSymbol)
                indexAttachedJarMembers(entry, classSymbol, methods, fields, budget)
            }
    }

    /** 把附加 jar 中识别出的字节码类型映射到统一 [JvmClassKind] 枚举。 */
    private fun AttachedJarClassKind.toJvmClassKind(): JvmClassKind =
        when (this) {
            AttachedJarClassKind.CLASS -> JvmClassKind.CLASS
            AttachedJarClassKind.INTERFACE -> JvmClassKind.INTERFACE
            AttachedJarClassKind.ENUM -> JvmClassKind.ENUM
            AttachedJarClassKind.ANNOTATION -> JvmClassKind.ANNOTATION
            AttachedJarClassKind.RECORD -> JvmClassKind.RECORD
        }

    /** 解析附加 jar 类的字节码成员表，按访问标志过滤合成/桥接方法后写入字段与方法符号。 */
    private fun indexAttachedJarMembers(
        entry: AttachedJarClassEntry,
        classSymbol: JvmClassSymbol,
        methods: MutableMap<String, JvmMethodSymbol>,
        fields: MutableMap<String, JvmFieldSymbol>,
        budget: com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget,
    ) {
        val memberSource = classSymbol.source
        entry.fields
            .asSequence()
            .filterNot { field -> field.accessFlags and ACC_SYNTHETIC != 0 }
            .filterNot { field -> field.name.contains('$') }
            .forEach { field ->
                val qualifiedName = "${classSymbol.qualifiedName}.${field.name}"
                val typeName = descriptorTypeName(field.descriptor) ?: return@forEach
                fields.putIfAbsent(
                    qualifiedName,
                    JvmFieldSymbol(
                        id = stableJvmId("field", qualifiedName),
                    qualifiedName = qualifiedName,
                    simpleName = field.name,
                    ownerClassName = classSymbol.qualifiedName,
                    typeName = typeName,
                    source = memberSource,
                    origin = classSymbol.origin,
                    typeReferences = listOf(JvmFieldTypeReference(typeName, JvmFieldTypeRole.DIRECT_VALUE)),
                ),
            )
        }
        entry.methods
            .asSequence()
            .filterNot { method -> method.accessFlags and ACC_SYNTHETIC != 0 }
            .filterNot { method -> method.accessFlags and ACC_BRIDGE != 0 }
            .filterNot { method -> method.name == "<clinit>" }
            .forEach { method ->
                if (methods.size >= budget.maxMethods) {
                    return@forEach
                }
                val descriptor = methodDescriptor(method.descriptor) ?: return@forEach
                val signature = attachedMethodSignature(classSymbol.qualifiedName, method.name, descriptor)
                methods.putIfAbsent(
                    signature,
                    JvmMethodSymbol(
                        id = stableJvmId("method", signature),
                        qualifiedName = signature,
                        simpleName = method.name,
                        ownerClassName = classSymbol.qualifiedName,
                        signature = signature,
                        parameterTypes = descriptor.parameterTypes,
                        returnType = if (method.name == "<init>") classSymbol.qualifiedName else descriptor.returnType,
                        abstract = method.accessFlags and ACC_ABSTRACT != 0,
                        source = memberSource,
                        origin = classSymbol.origin,
                    ),
                )
            }
    }

    /** 把外部 [PsiClass] 的字段和方法（去除超类继承成员）补齐到符号表，附带反编译标记和源引用。 */
    private fun indexExternalPsiMembers(
        ownerClassName: String,
        psiClass: PsiClass,
        methods: MutableMap<String, JvmMethodSymbol>,
        fields: MutableMap<String, JvmFieldSymbol>,
        origin: SourceOrigin,
        sourceFile: VirtualFile?,
        budget: com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget,
    ) {
        psiClass.fields.forEach { field ->
            val fieldName = field.name.takeIf(String::isNotBlank) ?: return@forEach
            val qualifiedName = "$ownerClassName.$fieldName"
            val ownerPackageName = ownerClassName.substringBeforeLast('.', "")
            fields.putIfAbsent(
                qualifiedName,
                JvmFieldSymbol(
                    id = stableJvmId("field", qualifiedName),
                    qualifiedName = qualifiedName,
                    simpleName = fieldName,
                    ownerClassName = ownerClassName,
                    typeName = canonicalTypeTextNear(
                        field.type,
                        ownerPackageName,
                    ) ?: field.type.canonicalText,
                    source = sourceFile?.let { file ->
                        sourceRef(file, field.navigationElement ?: field)
                            .copy(decompiled = origin in setOf(SourceOrigin.LIBRARY_CLASS_JAR, SourceOrigin.JDK_CLASS))
                    },
                    origin = origin,
                    typeReferences = fieldTypeReferences(field.type, ownerPackageName),
                ),
            )
        }
        psiClass.methods.forEach { method ->
            if (methods.size >= budget.maxMethods) {
                return@forEach
            }
            val signature = methodSignature(method)
            methods.putIfAbsent(
                signature,
                JvmMethodSymbol(
                    id = stableJvmId("method", signature),
                    qualifiedName = signature,
                    simpleName = method.name,
                    ownerClassName = ownerClassName,
                    signature = signature,
                    parameterTypes = method.parameterList.parameters.map { parameter ->
                        canonicalTypeText(parameter.type) ?: parameter.type.canonicalText
                    },
                    returnType = canonicalTypeText(method.returnType) ?: if (method.isConstructor) ownerClassName else "void",
                    abstract = method.hasModifierProperty(com.intellij.psi.PsiModifier.ABSTRACT),
                    source = sourceFile?.let { file ->
                        sourceRef(file, method.navigationElement ?: method)
                            .copy(decompiled = origin in setOf(SourceOrigin.LIBRARY_CLASS_JAR, SourceOrigin.JDK_CLASS))
                    },
                    origin = origin,
                ),
            )
        }
    }

    /** 把附加 jar 方法的全限定签名格式化为 `Owner.name(params):return`，构造器以类名作为显示名。 */
    private fun attachedMethodSignature(
        ownerClassName: String,
        methodName: String,
        descriptor: MethodDescriptor,
    ): String {
        val displayName = if (methodName == "<init>") ownerClassName.substringAfterLast('.') else methodName
        val returnType = if (methodName == "<init>") ownerClassName else descriptor.returnType
        return "$ownerClassName.$displayName(${descriptor.parameterTypes.joinToString(",")}):$returnType"
    }

    /** 解析 JVM 方法描述符（形如 `(Ljava/lang/String;I)V`），拆出参数类型列表与返回类型。 */
    /** 解析单个 JVM 类型 / 方法描述符（详见 top-level fun methodDescriptor / parseDescriptorType / descriptorTypeName）。 */
    /** 解析 Scala package / 顶层声明（详见 top-level fun scalaPackageName / scalaTopLevelDeclarations）。 */

    /** 把一个 [PsiClass] 转换为 [JvmClassSymbol] 写入索引，并连带登记模块、包、内部类、字段与方法。 */
    private fun indexPsiClass(
        file: VirtualFile,
        psiClass: PsiClass,
        ctx: SymbolIndexBuildContext,
    ) {
        val modules = ctx.modules
        val packages = ctx.packages
        val classes = ctx.classes
        val methods = ctx.methods
        val fields = ctx.fields
        val budget = ctx.budget
        checkCanceled()
        if (psiClass is PsiAnonymousClass || classes.size >= budget.maxProjectClasses) {
            return
        }
        val qualifiedName = psiClass.qualifiedName?.takeIf(String::isNotBlank) ?: return
        val moduleName = moduleName(file)
        if (moduleName != null) {
            modules.putIfAbsent(
                moduleName,
                JvmModuleSymbol(
                    id = stableJvmId("module", moduleName),
                    qualifiedName = moduleName,
                    simpleName = moduleName,
                    source = null,
                    origin = SourceOrigin.PROJECT_SOURCE,
                ),
            )
        }
        val packageName = packageNameForPsiClass(file, psiClass)
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
        val source = sourceRef(file, psiClass.navigationElement ?: psiClass)
        val testSource = ProjectFileIndex.getInstance(project).isInTestSourceContent(file) ||
            file.path.replace('\\', '/').contains("/src/test/")
        val superClassName = runCatching { psiClass.superClass?.qualifiedName }
            .getOrNull()
            ?.takeUnless { name -> name == "java.lang.Object" }
        val interfaceNames = runCatching {
            val declaredTypes = if (psiClass.isInterface) {
                psiClass.extendsListTypes.asSequence()
            } else {
                psiClass.implementsListTypes.asSequence()
            }
            (declaredTypes.mapNotNull(::canonicalTypeText) + psiClass.interfaces.asSequence().mapNotNull(PsiClass::getQualifiedName))
                .filter(String::isNotBlank)
                .distinct()
                .toList()
        }.getOrDefault(emptyList())
        classes[qualifiedName] = JvmClassSymbol(
            id = stableJvmId("class", qualifiedName),
            qualifiedName = qualifiedName,
            simpleName = psiClass.name ?: qualifiedName.substringAfterLast('.'),
            packageName = packageName,
            moduleName = moduleName,
            kind = classKind(psiClass),
            stereotype = stereotypeOf(psiClass),
            testSource = testSource,
            abstract = psiClass.hasModifierProperty(com.intellij.psi.PsiModifier.ABSTRACT),
            source = source,
            origin = SourceOrigin.PROJECT_SOURCE,
            superClassName = superClassName,
            interfaceNames = interfaceNames,
            docComment = psiClass.docCommentText(),
        )
        psiClass.innerClasses.forEach { innerClass ->
            indexPsiClass(file, innerClass, ctx)
        }
        psiClass.fields.forEach { field ->
            checkCanceled()
            val fieldName = field.name.takeIf(String::isNotBlank) ?: return@forEach
            val qualifiedFieldName = "$qualifiedName.$fieldName"
            fields[qualifiedFieldName] = JvmFieldSymbol(
                id = stableJvmId("field", qualifiedFieldName),
                qualifiedName = qualifiedFieldName,
                simpleName = fieldName,
                ownerClassName = qualifiedName,
                typeName = canonicalTypeTextNear(field.type, packageName) ?: field.type.canonicalText,
                source = sourceRef(file, field.navigationElement ?: field),
                origin = SourceOrigin.PROJECT_SOURCE,
                typeReferences = fieldTypeReferences(field.type, packageName),
            )
        }
        psiClass.methods.forEach { method ->
            checkCanceled()
            if (methods.size >= budget.maxMethods) {
                return@forEach
            }
            val signature = methodSignature(method)
            methods[signature] = JvmMethodSymbol(
                id = stableJvmId("method", signature),
                qualifiedName = signature,
                simpleName = method.name,
                ownerClassName = qualifiedName,
                signature = signature,
                parameterTypes = method.parameterList.parameters.map { parameter ->
                    canonicalTypeText(parameter.type) ?: parameter.type.canonicalText
                },
                returnType = canonicalTypeText(method.returnType) ?: if (method.isConstructor) qualifiedName else "void",
                abstract = method.hasModifierProperty(com.intellij.psi.PsiModifier.ABSTRACT),
                source = sourceRef(file, method.navigationElement ?: method),
                origin = SourceOrigin.PROJECT_SOURCE,
            )
        }
    }

    /** 兜底解析 Scala 源文件：在缺少 Scala 插件时用正则提取包名、顶层声明并写入最小可用类符号。 */
    private fun indexScalaSourceFile(
        file: VirtualFile,
        ctx: SymbolIndexBuildContext,
    ) {
        val modules = ctx.modules
        val packages = ctx.packages
        val classes = ctx.classes
        val budget = ctx.budget
        checkCanceled()
        val text = runCatching { String(file.contentsToByteArray(), StandardCharsets.UTF_8) }
            .getOrDefault("")
        if (text.isBlank()) {
            return
        }
        val packageName = scalaPackageName(text)
            ?: packageNameForPath(file.path, "placeholder.Placeholder").orEmpty()
        val moduleName = moduleName(file)
        if (moduleName != null) {
            modules.putIfAbsent(
                moduleName,
                JvmModuleSymbol(
                    id = stableJvmId("module", moduleName),
                    qualifiedName = moduleName,
                    simpleName = moduleName,
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
        val testSource = ProjectFileIndex.getInstance(project).isInTestSourceContent(file) ||
            file.path.replace('\\', '/').contains("/src/test/")
        if (!budget.includeTests && testSource) {
            return
        }
        scalaTopLevelDeclarations(text)
            .sortedBy { declaration -> if (declaration.kind == "object") 1 else 0 }
            .forEach { declaration ->
                checkCanceled()
                if (classes.size >= budget.maxProjectClasses) {
                    return@forEach
                }
                val qualifiedName = listOf(packageName, declaration.name)
                    .filter(String::isNotBlank)
                    .joinToString(".")
                if (qualifiedName.isBlank()) {
                    return@forEach
                }
                val existing = classes[qualifiedName]
                if (existing != null && declaration.kind == "object") {
                    return@forEach
                }
                val kind = when (declaration.kind) {
                    "trait" -> JvmClassKind.INTERFACE
                    "object" -> JvmClassKind.OBJECT
                    "enum" -> JvmClassKind.ENUM
                    else -> JvmClassKind.CLASS
                }
                classes[qualifiedName] = JvmClassSymbol(
                    id = stableJvmId("class", qualifiedName),
                    qualifiedName = qualifiedName,
                    simpleName = declaration.name,
                    packageName = packageName,
                    moduleName = moduleName,
                    kind = kind,
                    testSource = testSource,
                    abstract = declaration.kind == "trait",
                    source = JvmSourceRef(
                        displayPath = relativePath(file) ?: file.path,
                        virtualFileUrl = file.url,
                        startLine = declaration.startLine,
                        endLine = null,
                        decompiled = false,
                    ),
                    origin = SourceOrigin.PROJECT_SOURCE,
                )
            }
    }

    /** ScalaTopLevelDeclaration 已抽到 top-level（JvmSymbolDescriptorParser.kt）。 */

    /** 用正则从 Scala 源码顶部抽取 package 声明的全限定名（详见 top-level fun scalaPackageName）。 */
    /** 用正则枚举 Scala 顶层 class/trait/object/enum（详见 top-level fun scalaTopLevelDeclarations）。 */

    /** 在 content root 扫描不足预算时，再通过 [AllClassesSearch] 和 [PsiShortNamesCache] 双通道兜底补齐项目类。 */
    private fun indexProjectScopeClasses(
        ctx: SymbolIndexBuildContext,
    ) {
        val classes = ctx.classes
        val methods = ctx.methods
        val fields = ctx.fields
        val budget = ctx.budget
        if (classes.size >= budget.maxProjectClasses) {
            return
        }
        val scope = GlobalSearchScope.projectScope(project)
        fun indexIfNeeded(psiClass: PsiClass) {
            checkCanceled()
            if (classes.size >= budget.maxProjectClasses || psiClass.containingClass != null) {
                return
            }
            val qualifiedName = psiClass.qualifiedName?.takeIf(String::isNotBlank) ?: return
            if (classes.containsKey(qualifiedName)) {
                return
            }
            val file = psiClass.containingFile?.virtualFile ?: return
            if (!fileFilter(file)) {
                return
            }
            if (!budget.includeTests && ProjectFileIndex.getInstance(project).isInTestSourceContent(file)) {
                return
            }
            indexPsiClass(file, psiClass, ctx)
        }
        if (classes.size < budget.maxProjectClasses) {
            val allClassesStartedAt = System.nanoTime()
            for (psiClass in AllClassesSearch.search(scope, project)) {
                if (classes.size >= budget.maxProjectClasses) {
                    break
                }
                indexIfNeeded(psiClass)
            }
            traceStage("jvmSymbolIndex.allClassesSearch") {
                allClassesStartedAt to listOf(
                    "classes=${classes.size}",
                    "methods=${methods.size}",
                    "fields=${fields.size}",
                )
            }
        }
        if (classes.size < budget.maxProjectClasses) {
            val shortNamesCache = PsiShortNamesCache.getInstance(project)
            val shortNamesStartedAt = System.nanoTime()
            val allClassNames = shortNamesCache.allClassNames
            allClassNames.forEach { className ->
                checkCanceled()
                if (classes.size >= budget.maxProjectClasses) {
                    return@forEach
                }
                shortNamesCache.getClassesByName(className, scope).forEach(::indexIfNeeded)
            }
            traceStage("jvmSymbolIndex.shortNamesCache") {
                shortNamesStartedAt to listOf(
                    "classNames=${allClassNames.size}",
                    "classes=${classes.size}",
                    "methods=${methods.size}",
                    "fields=${fields.size}",
                )
            }
        }
    }

    /** 把一个非代码文件登记为 [JvmResourceSymbol] 并归类，对无关扩展名直接返回 null 跳过。 */
    private fun indexResource(
        file: VirtualFile,
        resources: MutableMap<String, JvmResourceSymbol>,
    ): JvmResourceSymbol? {
        val relativePath = relativePath(file) ?: return null
        val kind = resourceKind(relativePath)
        if (kind == JvmResourceKind.OTHER && file.extension?.lowercase() !in setOf("xml", "yml", "yaml", "properties", "sql", "md")) {
            return null
        }
        val resource = JvmResourceSymbol(
            id = stableJvmId("resource", relativePath),
            path = relativePath,
            kind = kind,
            source = JvmSourceRef(
                displayPath = relativePath,
                virtualFileUrl = file.url,
                startLine = 1,
                endLine = FileDocumentManager.getInstance().getDocument(file)?.lineCount,
                decompiled = false,
            ),
            origin = SourceOrigin.CONTENT_ROOT,
        )
        resources[relativePath] = resource
        return resource
    }

    /** 根据路径前缀/后缀把资源文件分门别类（SPI/MQ/配置文件/文档等），用于后续按类型筛选。 */
    private fun resourceKind(path: String): JvmResourceKind =
        com.charmnight.linkgraph.jvm.index.resourceKind(path)

    /** 读取 SPI 服务配置文件，逐行剔除注释与空白，得到该接口的实现类全限定名列表。 */
    private fun providerClassNames(file: VirtualFile): List<String> {
        checkCanceled()
        return runCatching { String(file.contentsToByteArray(), StandardCharsets.UTF_8) }
            .getOrDefault("")
            .let(::providerClassNames)
    }

    /** 纯文本版本的 SPI 实现名提取：详见 top-level fun spiProviderClassNames。 */
    private fun providerClassNames(text: String): List<String> =
        com.charmnight.linkgraph.jvm.index.spiProviderClassNames(text)

    /** 扫描类方法上的 MQ 监听注解（Kafka/Rabbit/Jms/RocketMQ 等），把目标主题登记为 MQ 资源节点。 */
    private fun indexFrameworkResources(
        file: VirtualFile,
        psiClass: PsiClass,
        resources: MutableMap<String, JvmResourceSymbol>,
    ) {
        psiClass.methods.forEach { method ->
            checkCanceled()
            method.modifierList.annotations
                .mapNotNull(::mqDestination)
                .forEach { destination ->
                    resources.putIfAbsent(
                        "mq:$destination",
                        JvmResourceSymbol(
                            id = stableJvmId("resource", "mq:$destination"),
                            path = "mq:$destination",
                            kind = JvmResourceKind.MQ_TOPIC,
                            source = sourceRef(file, method.navigationElement ?: method),
                            origin = SourceOrigin.PROJECT_SOURCE,
                        ),
                    )
                }
        }
    }

    /** 包装 [ProgressManager.checkCanceled]，让长循环可以统一抛出 ProcessCanceledException 中断。 */
    private fun checkCanceled() {
        ProgressManager.checkCanceled()
    }

    /** 从消息监听注解中按候选属性名（topics/queues/destination 等）取出目标目的地字符串。 */
    private fun mqDestination(annotation: com.intellij.psi.PsiAnnotation): String? {
        val simpleName = annotation.qualifiedName?.substringAfterLast('.') ?: annotation.nameReferenceElement?.referenceName
        if (simpleName !in setOf("KafkaListener", "RabbitListener", "JmsListener", "RocketMQMessageListener", "StreamListener")) {
            return null
        }
        return listOf("topics", "topic", "value", "queues", "queue", "destination")
            .firstNotNullOfOrNull { name -> annotationString(annotation.findDeclaredAttributeValue(name)) }
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }

    /** 提取注解属性值的字符串表示，兼容字面量与数组初始化表达式两种形式。 */
    private fun annotationString(value: com.intellij.psi.PsiAnnotationMemberValue?): String? =
        when (value) {
            is com.intellij.psi.PsiLiteralExpression -> value.value as? String
            is com.intellij.psi.PsiArrayInitializerMemberValue -> value.initializers.firstOrNull()?.let(::annotationString)
            else -> null
        }

    /** 根据 PSI 标志位判定类的具体形态：注解、枚举、record、接口、Kotlin object 或普通 class。 */
    private fun classKind(psiClass: PsiClass): JvmClassKind {
        return when {
            psiClass.isAnnotationType -> JvmClassKind.ANNOTATION
            psiClass.isEnum -> JvmClassKind.ENUM
            runCatching { psiClass.isRecord }.getOrDefault(false) -> JvmClassKind.RECORD
            psiClass.isInterface -> JvmClassKind.INTERFACE
            psiClass.navigationElement is org.jetbrains.kotlin.psi.KtObjectDeclaration -> JvmClassKind.OBJECT
            else -> JvmClassKind.CLASS
        }
    }

    /** P2-1: 构造外部类符号，供 ExternalLibraryIndexer 通过 IndexerSharedHelpers 接口调用。 */
    override fun buildExternalClassSymbol(
        qualifiedName: String,
        psiClass: PsiClass,
        navigationFile: VirtualFile?,
        origin: SourceOrigin,
    ): JvmClassSymbol = JvmClassSymbol(
        id = stableJvmId("class", qualifiedName),
        qualifiedName = qualifiedName,
        simpleName = psiClass.name ?: qualifiedName.substringAfterLast('.'),
        packageName = packageNameForPsiClass(navigationFile, psiClass),
        moduleName = externalModuleName(navigationFile, origin),
        kind = classKind(psiClass),
        stereotype = JvmStereotype.UNKNOWN,
        abstract = psiClass.hasModifierProperty(com.intellij.psi.PsiModifier.ABSTRACT),
        external = true,
        library = origin in setOf(SourceOrigin.LIBRARY_SOURCE_JAR, SourceOrigin.LIBRARY_CLASS_JAR),
        jdk = origin in setOf(SourceOrigin.JDK_SOURCE, SourceOrigin.JDK_CLASS),
        source = navigationFile?.let { file -> sourceRef(file, psiClass.navigationElement ?: psiClass)
            .copy(decompiled = origin in setOf(SourceOrigin.LIBRARY_CLASS_JAR, SourceOrigin.JDK_CLASS)) },
        origin = origin,
    )

    /** 根据 Spring/Spring Boot 等框架的常见 stereotype 注解推断类在分层架构中的角色。 */
    private fun stereotypeOf(psiClass: PsiClass): JvmStereotype {
        val names = psiClass.annotations.mapNotNull { annotation -> annotation.qualifiedName?.substringAfterLast('.') }
            .toSet()
        return when {
            names.any { it in setOf("RestController", "Controller") } -> JvmStereotype.CONTROLLER
            names.contains("Service") -> JvmStereotype.SERVICE
            names.contains("Repository") -> JvmStereotype.REPOSITORY
            names.contains("Configuration") -> JvmStereotype.CONFIGURATION
            names.any { it in setOf("Component", "Named", "Singleton") } -> JvmStereotype.COMPONENT
            else -> JvmStereotype.UNKNOWN
        }
    }

    /** 提取类的 KDoc/Javadoc 文本，作为节点 tooltip 与语义提示的基础素材。 */
    private fun PsiClass.docCommentText(): String? =
        docComment?.plainText()

    /** 把 [PsiDocComment] 描述段拼接成单行紧凑文本，去除多余空白和换行。 */
    private fun PsiDocComment.plainText(): String? =
        descriptionElements
            .joinToString(separator = "") { element -> element.text }
            .replace(Regex("""[ \t]*\R[ \t]*"""), "\n")
            .replace(Regex("""[ \t]+"""), " ")
            .trim()
            .ifBlank { null }

    /** 通过 [ModuleUtilCore] 反查文件所属 IntelliJ 模块名，作为符号的模块归属。 */
    private fun moduleName(file: VirtualFile): String? {
        return ModuleUtilCore.findModuleForFile(file, project)?.name
    }

    /** 综合多种来源（Java/Kotlin PSI、文件路径、外层类）确定一个 [PsiClass] 的包名，尽量不返回空。 */
    private fun packageNameForPsiClass(file: VirtualFile?, psiClass: PsiClass): String {
        (psiClass.containingFile as? PsiJavaFile)
            ?.packageName
            ?.takeIf(String::isNotBlank)
            ?.let { return it }
        (psiClass.navigationElement?.containingFile as? PsiJavaFile)
            ?.packageName
            ?.takeIf(String::isNotBlank)
            ?.let { return it }
        (psiClass.navigationElement?.containingFile as? KtFile)
            ?.packageFqName
            ?.asString()
            ?.takeIf(String::isNotBlank)
            ?.let { return it }
        (psiClass.containingFile as? KtFile)
            ?.packageFqName
            ?.asString()
            ?.takeIf(String::isNotBlank)
            ?.let { return it }
        return packageNameForPath(file?.path, psiClass.qualifiedName.orEmpty())
            ?: psiClass.containingClass?.let { outerClass -> packageNameForPsiClass(file, outerClass) }
            ?: psiClass.qualifiedName?.let(::outermostPackageFromQualifiedName).orEmpty()
    }

    /** 用附加 jar 的 class/source entry 路径反推包名，作为符号归属的兜底来源。 */
    private fun packageNameForClassEntry(
        qualifiedName: String,
        classEntryName: String?,
        sourceEntryName: String?,
    ): String {
        return packageNameForPath(sourceEntryName, qualifiedName)
            ?: packageNameForPath(classEntryName, qualifiedName)
            ?: outermostPackageFromQualifiedName(qualifiedName)
    }

    /** 通过 jar entry 路径与全限定类名做交叉匹配，挑出最可能的包名段，避免内部类污染包路径。 */
    private fun packageNameForPath(path: String?, qualifiedName: String): String? {
        val normalizedPath = path
            ?.substringAfter("!/", missingDelimiterValue = path)
            ?.substringBeforeLast('.', missingDelimiterValue = "")
            ?.replace('\\', '/')
            ?: return null
        val packageFromPath = normalizedPath.substringBeforeLast('/', missingDelimiterValue = "")
            .replace('/', '.')
            .trim('.')
            .takeIf(String::isNotBlank)
            ?: return null
        val qualifiedParts = qualifiedName.substringBefore('$').split('.').filter(String::isNotBlank)
        return qualifiedParts.indices
            .map { index -> qualifiedParts.take(index).joinToString(".") }
            .filter(String::isNotBlank)
            .lastOrNull { candidate -> packageFromPath.endsWith(candidate) }
    }

    /** 计算 PSI 元素在文件中的起止行号，组装成 [JvmSourceRef]，用于点击节点跳转源码。 */
    override fun sourceRef(file: VirtualFile, element: PsiElement): JvmSourceRef {
        val document = FileDocumentManager.getInstance().getDocument(file)
        val range = element.textRange
        return JvmSourceRef(
            displayPath = relativePath(file) ?: file.path,
            virtualFileUrl = file.url,
            startLine = range?.let { document?.getLineNumber(it.startOffset)?.plus(1) },
            endLine = range?.let { document?.getLineNumber(it.endOffset.coerceAtLeast(it.startOffset))?.plus(1) },
            decompiled = false,
        )
    }

    /** 计算 [file] 相对其所属 content root 的相对路径，找不到祖先 root 时回退为绝对路径。 */
    private fun relativePath(file: VirtualFile): String? {
        val root = ProjectRootManager.getInstance(project).contentRoots
            .firstOrNull { contentRoot -> VfsUtilCore.isAncestor(contentRoot, file, false) }
            ?: return file.path
        return VfsUtilCore.getRelativePath(file, root, '/') ?: file.path
    }

    /** 把外部资源（jar/jrt 内）的 URL 路径裁剪为用户友好的显示路径，去掉协议前缀和 jar 包装。 */
    private fun displayPathForExternalResource(file: VirtualFile): String =
        file.path.substringAfter("!/", missingDelimiterValue = file.path)

    /** 根据外部文件的协议（jrt/jar）和 [ProjectFileIndex] 归属，判定它是 JDK 源码、JDK class 还是库源码/class。 */
    private fun sourceOriginForExternalFile(file: VirtualFile): SourceOrigin {
        val fileIndex = ProjectFileIndex.getInstance(project)
        return when {
            file.url.startsWith("jrt://") && file.extension?.equals("java", ignoreCase = true) == true -> SourceOrigin.JDK_SOURCE
            file.url.startsWith("jrt://") -> SourceOrigin.JDK_CLASS
            fileIndex.isInLibrarySource(file) -> SourceOrigin.LIBRARY_SOURCE_JAR
            fileIndex.isInLibraryClasses(file) -> SourceOrigin.LIBRARY_CLASS_JAR
            file.path.contains(".jar!/") && file.extension?.equals("java", ignoreCase = true) == true -> SourceOrigin.LIBRARY_SOURCE_JAR
            file.path.contains(".jar!/") -> SourceOrigin.LIBRARY_CLASS_JAR
            else -> SourceOrigin.LOCAL_FILE
        }
    }

    /** 根据来源类型推导外部依赖的模块显示名：JDK 统一记为 `jdk`，库以 jar 文件名标记。 */
    private fun externalModuleName(file: VirtualFile?, origin: SourceOrigin): String? =
        when (origin) {
            SourceOrigin.JDK_SOURCE,
            SourceOrigin.JDK_CLASS,
            -> "jdk"
            SourceOrigin.LIBRARY_SOURCE_JAR,
            SourceOrigin.LIBRARY_CLASS_JAR,
            -> file?.path?.substringBefore("!/")?.substringAfterLast('/')?.let { "library:$it" }
            else -> null
        }

    /** 取当前运行 JDK 的 home 路径，作为 jrt 资源 URL 拼装的辅助信息。 */
    private fun jdkHomePath(): String = System.getProperty("java.home").orEmpty()

    /** 通过包名前缀判断该全限定名是否属于 JDK（java/javax/jdk/sun/com.sun）。 */
    private fun String.isJdkQualifiedName(): Boolean =
        startsWith("java.") ||
            startsWith("javax.") ||
            startsWith("jdk.") ||
            startsWith("sun.") ||
            startsWith("com.sun.")

    /** 方法描述符解析结果：参数类型列表 + 返回类型。 */
    /** MethodDescriptor / ParsedDescriptorType 已抽到 top-level（JvmSymbolDescriptorParser.kt）。 */

    private companion object {
        // JVM access flag：桥接方法，编译器为泛型兼容生成
        private const val ACC_BRIDGE = 0x0040
        // JVM access flag：合成方法/字段，由编译器内部使用
        private const val ACC_SYNTHETIC = 0x1000
        // JVM access flag：抽象方法
        private const val ACC_ABSTRACT = 0x0400
    }
}
