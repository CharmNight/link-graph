package com.charmnight.linkgraph.jvm.index

import com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget
import com.charmnight.linkgraph.source.AttachedJarIndex
import com.charmnight.linkgraph.source.SourceOrigin
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiType
import com.intellij.psi.search.GlobalSearchScope
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

/**
 * 外部依赖（库 JAR / JDK / 用户附加 JAR）符号索引器（P2-1 真正的架构分解）。
 *
 * 从 JvmSymbolIndexBuilder 抽出的独立 class，负责三类外部来源的符号索引：
 * - 项目类引用的外部类型（超类、接口、字段类型、方法参数/返回类型等）通过 PSI findClass 解析
 * - 项目依赖 JAR 中的 META-INF/services SPI 配置文件
 * - JDK 模块（jrt 文件系统）中的 SPI 配置文件
 * - 用户手动附加的 JAR（字节码扫描）
 *
 * 与项目源码索引器的区别：不遍历 content root，而是从已索引的项目类出发反向发现外部依赖。
 */
internal class ExternalLibraryIndexer(
    private val project: Project,
    private val sharedHelpers: IndexerSharedHelpers,
) {
    /**
     * 索引项目类直接引用的外部类型（超类/接口/字段/方法参数）。
     *
     * 流程：遍历项目类 → 收集外部类型名 → 按 budget 过滤 → 通过 PSI findClass 解析 → 写入 class/method/field 符号。
     */
    fun indexDirectExternalClasses(ctx: SymbolIndexBuildContext) {
        ProgressManager.checkCanceled()
        val budget = ctx.budget
        val classes = ctx.classes
        val methods = ctx.methods
        val fields = ctx.fields
        val facade = JavaPsiFacade.getInstance(project)
        val findClassCache = mutableMapOf<String, PsiClass?>()
        val findClassByProjectScope: (String) -> PsiClass? = { name ->
            findClassCache.getOrPut("project:$name") {
                facade.findClass(name, GlobalSearchScope.projectScope(project))
            } ?: null
        }
        val findClassByAllScope: (String) -> PsiClass? = { name ->
            findClassCache.getOrPut("all:$name") {
                facade.findClass(name, GlobalSearchScope.allScope(project))
            } ?: null
        }
        val externalNames = linkedSetOf<String>()
        classes.values
            .filterNot { symbol -> symbol.external }
            .forEach { symbol ->
                ProgressManager.checkCanceled()
                val psiClass = findClassByProjectScope(symbol.qualifiedName) ?: return@forEach
                psiClass.superClass?.qualifiedName?.let(externalNames::add)
                psiClass.interfaces.mapNotNull(PsiClass::getQualifiedName).forEach(externalNames::add)
                psiClass.fields
                    .flatMap { field: PsiField -> fieldTypeReferences(field.type, symbol.packageName).map(JvmFieldTypeReference::typeName) }
                    .forEach(externalNames::add)
                psiClass.methods.forEach { method ->
                    canonicalTypeText(method.returnType)?.let(externalNames::add)
                    method.parameterList.parameters.mapNotNull { p -> canonicalTypeText(p.type) }.forEach(externalNames::add)
                    method.throwsList.referencedTypes.mapNotNull { t -> canonicalTypeText(t) }.forEach(externalNames::add)
                }
            }
        val existingProjectNames = classes.keys.toSet()
        externalNames
            .filter { name -> name !in existingProjectNames && name != "java.lang.Object" }
            .filter { name -> if (name.isJdkQualifiedName()) budget.includeJdk else budget.includeExternalLibraries }
            .take((budget.maxExternalClasses - classes.values.count { it.external }).coerceAtLeast(0))
            .forEach { qualifiedName ->
                ProgressManager.checkCanceled()
                val psiClass = findClassByAllScope(qualifiedName) ?: return@forEach
                val navigationFile = psiClass.navigationElement?.containingFile?.virtualFile ?: psiClass.containingFile?.virtualFile
                val origin = navigationFile?.let(::sourceOriginForExternalFile)
                    ?: if (qualifiedName.startsWith("java.") || qualifiedName.startsWith("javax.") || qualifiedName.startsWith("jdk.")) {
                        SourceOrigin.JDK_CLASS
                    } else {
                        SourceOrigin.LIBRARY_CLASS_JAR
                    }
                classes.putIfAbsent(qualifiedName, sharedHelpers.buildExternalClassSymbol(qualifiedName, psiClass, navigationFile, origin))
                indexExternalPsiMembers(qualifiedName, psiClass, ctx, origin, navigationFile)
            }
    }

    /** 遍历项目依赖 JAR 的 META-INF/services 目录，收集 SPI 配置文件。 */
    fun indexLibraryServiceFiles(ctx: SymbolIndexBuildContext) {
        val budget = ctx.budget
        val resources = ctx.resources
        val serviceFiles = ctx.serviceFiles
        val roots = ProjectRootManager.getInstance(project).orderEntries().classes().roots.asSequence() +
            ProjectRootManager.getInstance(project).orderEntries().sources().roots.asSequence()
        roots.distinctBy(VirtualFile::getUrl).forEach { root ->
            ProgressManager.checkCanceled()
            val serviceRoot = root.findFileByRelativePath("META-INF/services") ?: return@forEach
            if (!serviceRoot.isDirectory) return@forEach
            serviceRoot.children.asSequence()
                .filter { file -> !file.isDirectory && file.name.isNotBlank() }
                .filter { file -> if (file.url.startsWith("jrt://")) budget.includeJdk else budget.includeExternalLibraries }
                .forEach { file ->
                    ProgressManager.checkCanceled()
                    val providers = runCatching { String(file.contentsToByteArray(), StandardCharsets.UTF_8) }
                        .getOrDefault("").let(::spiProviderClassNames)
                    if (providers.isEmpty()) return@forEach
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
                        JvmServiceProviderFile(file.name, providers, resource, origin)
                }
        }
    }

    /** 通过 jrt 文件系统枚举 JDK 各模块的 SPI 配置文件。 */
    fun indexJdkServiceFiles(ctx: SymbolIndexBuildContext) {
        val resources = ctx.resources
        val serviceFiles = ctx.serviceFiles
        val jrt = runCatching { FileSystems.getFileSystem(URI.create("jrt:/")) }
            .recoverCatching { FileSystems.newFileSystem(URI.create("jrt:/"), emptyMap<String, Any>()) }
            .getOrNull() ?: return
        val modulesRoot = jrt.getPath("/modules")
        if (!Files.isDirectory(modulesRoot)) return
        runCatching {
            Files.list(modulesRoot).use { modules ->
                modules.filter(Files::isDirectory).forEach { modulePath ->
                    ProgressManager.checkCanceled()
                    val serviceRoot = modulePath.resolve("META-INF/services")
                    if (!Files.isDirectory(serviceRoot)) return@forEach
                    Files.list(serviceRoot).use { files ->
                        files.filter(Files::isRegularFile).forEach { file ->
                            ProgressManager.checkCanceled()
                            val serviceName = file.fileName.toString().takeIf(String::isNotBlank) ?: return@forEach
                            val text = runCatching { Files.readString(file, StandardCharsets.UTF_8) }.getOrDefault("")
                            val providers = spiProviderClassNames(text)
                            if (providers.isEmpty()) return@forEach
                            val moduleName = modulePath.fileName.toString()
                            val resourcePath = "$moduleName/META-INF/services/$serviceName"
                            val virtualFileUrl = "jrt://${jdkHomePath()}!/$resourcePath"
                            if (serviceFiles[serviceName].orEmpty().any { it.resource.source?.virtualFileUrl == virtualFileUrl }) return@forEach
                            val resource = JvmResourceSymbol(
                                id = stableJvmId("resource", resourcePath),
                                path = resourcePath,
                                kind = JvmResourceKind.SPI_SERVICE_FILE,
                                source = JvmSourceRef(resourcePath, virtualFileUrl, 1, text.lineSequence().count().coerceAtLeast(1), false),
                                origin = SourceOrigin.JDK_CLASS,
                            )
                            resources.putIfAbsent(resource.path, resource)
                            serviceFiles.getOrPut(serviceName) { mutableListOf() } +=
                                JvmServiceProviderFile(serviceName, providers, resource, SourceOrigin.JDK_CLASS)
                        }
                    }
                }
            }
        }
    }

    /** 把 SPI 服务文件中出现的接口名/实现类名补齐到类索引。 */
    fun ensureServiceTypesIndexed(ctx: SymbolIndexBuildContext) {
        val budget = ctx.budget
        val facade = JavaPsiFacade.getInstance(project)
        ctx.serviceFiles.values.flatten().asSequence()
            .flatMap { sf -> sequenceOf(sf.serviceInterfaceName) + sf.providerClassNames.asSequence() }
            .distinct()
            .filter { qn -> qn !in ctx.classes }
            .filter { qn -> if (qn.isJdkQualifiedName()) budget.includeJdk else budget.includeExternalLibraries }
            .take((budget.maxExternalClasses - ctx.classes.values.count { it.external }).coerceAtLeast(0))
            .forEach { qualifiedName ->
                ProgressManager.checkCanceled()
                val psiClass = facade.findClass(qualifiedName, GlobalSearchScope.allScope(project)) ?: return@forEach
                val navigationFile = psiClass.navigationElement?.containingFile?.virtualFile ?: psiClass.containingFile?.virtualFile
                val origin = navigationFile?.let(::sourceOriginForExternalFile)
                    ?: if (qualifiedName.isJdkQualifiedName()) SourceOrigin.JDK_CLASS else SourceOrigin.LIBRARY_CLASS_JAR
                ctx.classes.putIfAbsent(qualifiedName, sharedHelpers.buildExternalClassSymbol(qualifiedName, psiClass, navigationFile, origin))
                indexExternalPsiMembers(qualifiedName, psiClass, ctx, origin, navigationFile)
            }
    }

    /** 把外部 PsiClass 的字段和方法补齐到符号表。 */
    private fun indexExternalPsiMembers(
        ownerClassName: String,
        psiClass: PsiClass,
        ctx: SymbolIndexBuildContext,
        origin: SourceOrigin,
        sourceFile: VirtualFile?,
    ) {
        val budget = ctx.budget
        val fields = ctx.fields
        val methods = ctx.methods
        val ownerPackageName = ownerClassName.substringBeforeLast('.', "")
        psiClass.fields.forEach { field ->
            val fieldName = field.name.takeIf(String::isNotBlank) ?: return@forEach
            val qualifiedName = "$ownerClassName.$fieldName"
            fields.putIfAbsent(qualifiedName, JvmFieldSymbol(
                id = stableJvmId("field", qualifiedName),
                qualifiedName = qualifiedName,
                simpleName = fieldName,
                ownerClassName = ownerClassName,
                typeName = canonicalTypeTextNear(field.type, ownerPackageName) ?: field.type.canonicalText,
                source = sourceFile?.let { sharedHelpers.sourceRef(it, field.navigationElement ?: field) }
                    ?.copy(decompiled = origin in setOf(SourceOrigin.LIBRARY_CLASS_JAR, SourceOrigin.JDK_CLASS)),
                origin = origin,
                typeReferences = fieldTypeReferences(field.type, ownerPackageName),
            ))
        }
        psiClass.methods.forEach { method ->
            ProgressManager.checkCanceled()
            if (methods.size >= budget.maxMethods) return@forEach
            val signature = methodSignature(method)
            methods.putIfAbsent(signature, JvmMethodSymbol(
                id = stableJvmId("method", signature),
                qualifiedName = signature,
                simpleName = method.name,
                ownerClassName = ownerClassName,
                signature = signature,
                parameterTypes = method.parameterList.parameters.map { p -> canonicalTypeText(p.type) ?: p.type.canonicalText },
                returnType = canonicalTypeText(method.returnType) ?: if (method.isConstructor) ownerClassName else "void",
                abstract = method.hasModifierProperty(com.intellij.psi.PsiModifier.ABSTRACT),
                source = sourceFile?.let { sharedHelpers.sourceRef(it, method.navigationElement ?: method) }
                    ?.copy(decompiled = origin in setOf(SourceOrigin.LIBRARY_CLASS_JAR, SourceOrigin.JDK_CLASS)),
                origin = origin,
            ))
        }
    }

    // ---- 外部文件来源判定 helper ----

    private fun sourceOriginForExternalFile(file: VirtualFile): SourceOrigin {
        val url = file.url
        val path = file.path
        return when {
            url.startsWith("jar://") -> {
                if (path.contains("/.gradle/") || path.contains("/caches/modules-") || path.contains("/.m2/")) {
                    SourceOrigin.LIBRARY_CLASS_JAR
                } else {
                    SourceOrigin.LIBRARY_SOURCE_JAR
                }
            }
            url.startsWith("jrt://") -> SourceOrigin.JDK_SOURCE
            path.contains("/.gradle/") || path.contains("/caches/modules-") || path.contains("/.m2/") -> SourceOrigin.LIBRARY_CLASS_JAR
            else -> SourceOrigin.LIBRARY_CLASS_JAR
        }
    }

    private fun displayPathForExternalResource(file: VirtualFile): String {
        val url = file.url
        val jarPrefix = "jar://"
        val jrtPrefix = "jrt:/"
        return when {
            url.startsWith(jarPrefix) -> url.removePrefix(jarPrefix).substringAfter("!/", url)
            url.startsWith(jrtPrefix) -> url.removePrefix(jrtPrefix).substringAfter("!/", url)
            else -> file.path
        }
    }

    private fun jdkHomePath(): String = System.getProperty("java.home")?.let { Path.of(it).parent?.toString() ?: it } ?: ""
}

private fun String.isJdkQualifiedName(): Boolean = startsWith("java.") || startsWith("javax.") || startsWith("jdk.") || startsWith("sun.") || startsWith("com.sun.")

/**
 * JvmSymbolIndexBuilder 与 ExternalLibraryIndexer 共享的 helper 接口。
 *
 * 这些方法在 JvmSymbolIndexBuilder 中已有实现（private），通过此接口注入到
 * ExternalLibraryIndexer。methodSignature / spiProviderClassNames 已是 top-level
 * 函数，ExternalLibraryIndexer 直接 import 使用，不需要通过此接口。
 */
internal interface IndexerSharedHelpers {
    fun sourceRef(file: VirtualFile, element: com.intellij.psi.PsiElement): JvmSourceRef
    fun buildExternalClassSymbol(
        qualifiedName: String,
        psiClass: PsiClass,
        navigationFile: VirtualFile?,
        origin: SourceOrigin,
    ): JvmClassSymbol
}
