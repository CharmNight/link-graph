package com.charmnight.linkgraph.jvm.index

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

data class JvmSymbolIndexBuildTraceEvent(
    val stage: String,
    val startedAtNanos: Long,
    val details: () -> List<String>,
)

class JvmSymbolIndexBuilder(
    private val project: Project,
    private val trace: ((JvmSymbolIndexBuildTraceEvent) -> Unit)? = null,
    private val fileFilter: (VirtualFile) -> Boolean = { true },
    private val attachedJarIndexProvider: () -> AttachedJarIndex = { AttachedJarIndex() },
) {
    fun build(budget: com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget = com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget()): JvmSymbolIndex {
        val modules = linkedMapOf<String, JvmModuleSymbol>()
        val packages = linkedMapOf<String, JvmPackageSymbol>()
        val classes = linkedMapOf<String, JvmClassSymbol>()
        val methods = linkedMapOf<String, JvmMethodSymbol>()
        val fields = linkedMapOf<String, JvmFieldSymbol>()
        val resources = linkedMapOf<String, JvmResourceSymbol>()
        val serviceFiles = linkedMapOf<String, MutableList<JvmServiceProviderFile>>()
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
                        if (classes.size < budget.maxProjectClasses) {
                            (psiManager.findFile(file) as? PsiJavaFile)?.classes.orEmpty().forEach { psiClass ->
                                indexPsiClass(file, psiClass, modules, packages, classes, methods, fields, budget)
                                indexFrameworkResources(file, psiClass, resources)
                            }
                        }
                    }
                    "kt", "kts" -> {
                        if (classes.size < budget.maxProjectClasses) {
                            val ktFile = psiManager.findFile(file) as? KtFile
                            ktFile?.collectDescendantsOfType<KtClass>().orEmpty()
                                .filter { ktClass -> PsiTreeUtil.getParentOfType(ktClass, KtClass::class.java, true) == null }
                                .forEach { ktClass ->
                                    ktClass.toLightClass()?.let { psiClass ->
                                        indexPsiClass(file, psiClass, modules, packages, classes, methods, fields, budget)
                                        indexFrameworkResources(file, psiClass, resources)
                                    }
                                }
                        }
                    }
                    "scala" -> {
                        if (classes.size < budget.maxProjectClasses) {
                            indexScalaSourceFile(file, modules, packages, classes, budget)
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
        indexProjectScopeClasses(modules, packages, classes, methods, fields, budget)

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
            indexDirectExternalClasses(classes, methods, fields, budget)
            indexLibraryServiceFiles(resources, serviceFiles, budget)
            ensureServiceTypesIndexed(serviceFiles, classes, methods, fields, budget)
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
            indexJdkServiceFiles(resources, serviceFiles)
            ensureServiceTypesIndexed(serviceFiles, classes, methods, fields, budget)
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

    private fun shouldIndexContentRootFile(
        root: VirtualFile,
        file: VirtualFile,
    ): Boolean =
        !contentRootRelativePath(root, file).hasExcludedContentRootSegment()

    private fun contentRootRelativePath(
        root: VirtualFile,
        file: VirtualFile,
    ): String =
        (VfsUtilCore.getRelativePath(file, root, '/') ?: file.path).replace('\\', '/')

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

    private fun indexDirectExternalClasses(
        classes: MutableMap<String, JvmClassSymbol>,
        methods: MutableMap<String, JvmMethodSymbol>,
        fields: MutableMap<String, JvmFieldSymbol>,
        budget: com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget,
    ) {
        checkCanceled()
        val externalNames = linkedSetOf<String>()
        classes.values
            .filterNot { symbol -> symbol.external }
            .forEach { symbol ->
                checkCanceled()
                val psiClass = JavaPsiFacade.getInstance(project).findClass(symbol.qualifiedName, GlobalSearchScope.projectScope(project))
                    ?: return@forEach
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
                val psiClass = facade.findClass(qualifiedName, GlobalSearchScope.allScope(project)) ?: return@forEach
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

    private fun AttachedJarClassKind.toJvmClassKind(): JvmClassKind =
        when (this) {
            AttachedJarClassKind.CLASS -> JvmClassKind.CLASS
            AttachedJarClassKind.INTERFACE -> JvmClassKind.INTERFACE
            AttachedJarClassKind.ENUM -> JvmClassKind.ENUM
            AttachedJarClassKind.ANNOTATION -> JvmClassKind.ANNOTATION
            AttachedJarClassKind.RECORD -> JvmClassKind.RECORD
        }

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

    private fun attachedMethodSignature(
        ownerClassName: String,
        methodName: String,
        descriptor: MethodDescriptor,
    ): String {
        val displayName = if (methodName == "<init>") ownerClassName.substringAfterLast('.') else methodName
        val returnType = if (methodName == "<init>") ownerClassName else descriptor.returnType
        return "$ownerClassName.$displayName(${descriptor.parameterTypes.joinToString(",")}):$returnType"
    }

    private fun methodDescriptor(descriptor: String): MethodDescriptor? {
        if (!descriptor.startsWith("(")) {
            return null
        }
        var offset = 1
        val parameters = mutableListOf<String>()
        while (offset < descriptor.length && descriptor[offset] != ')') {
            val parsed = parseDescriptorType(descriptor, offset) ?: return null
            parameters += parsed.typeName
            offset = parsed.nextOffset
        }
        if (offset >= descriptor.length || descriptor[offset] != ')') {
            return null
        }
        val returnType = parseDescriptorType(descriptor, offset + 1)?.typeName ?: return null
        return MethodDescriptor(parameters, returnType)
    }

    private fun descriptorTypeName(descriptor: String): String? =
        parseDescriptorType(descriptor, 0)?.takeIf { parsed -> parsed.nextOffset == descriptor.length }?.typeName

    private fun parseDescriptorType(
        descriptor: String,
        startOffset: Int,
    ): ParsedDescriptorType? {
        if (startOffset >= descriptor.length) {
            return null
        }
        var offset = startOffset
        var arrayDepth = 0
        while (offset < descriptor.length && descriptor[offset] == '[') {
            arrayDepth++
            offset++
        }
        if (offset >= descriptor.length) {
            return null
        }
        val baseType = when (val marker = descriptor[offset]) {
            'B' -> "byte".also { offset++ }
            'C' -> "char".also { offset++ }
            'D' -> "double".also { offset++ }
            'F' -> "float".also { offset++ }
            'I' -> "int".also { offset++ }
            'J' -> "long".also { offset++ }
            'S' -> "short".also { offset++ }
            'Z' -> "boolean".also { offset++ }
            'V' -> "void".also { offset++ }
            'L' -> {
                val end = descriptor.indexOf(';', startIndex = offset)
                if (end < 0) {
                    return null
                }
                descriptor.substring(offset + 1, end).replace('/', '.').also { offset = end + 1 }
            }
            else -> return null
        }
        return ParsedDescriptorType(
            typeName = baseType + "[]".repeat(arrayDepth),
            nextOffset = offset,
        )
    }

    private fun indexPsiClass(
        file: VirtualFile,
        psiClass: PsiClass,
        modules: MutableMap<String, JvmModuleSymbol>,
        packages: MutableMap<String, JvmPackageSymbol>,
        classes: MutableMap<String, JvmClassSymbol>,
        methods: MutableMap<String, JvmMethodSymbol>,
        fields: MutableMap<String, JvmFieldSymbol>,
        budget: com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget,
    ) {
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
            indexPsiClass(file, innerClass, modules, packages, classes, methods, fields, budget)
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

    private fun indexScalaSourceFile(
        file: VirtualFile,
        modules: MutableMap<String, JvmModuleSymbol>,
        packages: MutableMap<String, JvmPackageSymbol>,
        classes: MutableMap<String, JvmClassSymbol>,
        budget: com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget,
    ) {
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

    private data class ScalaTopLevelDeclaration(
        val kind: String,
        val name: String,
        val startLine: Int,
    )

    private fun scalaPackageName(text: String): String? =
        Regex("""(?m)^\s*package\s+([A-Za-z_][\w]*(?:\.[A-Za-z_][\w]*)*)\s*(?:$|\{)""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)

    private fun scalaTopLevelDeclarations(text: String): List<ScalaTopLevelDeclaration> {
        val withoutBlockComments = text.replace(Regex("""(?s)/\*.*?\*/"""), "")
        return Regex(
            """(?m)^\s*(?:@[^\n]+\s*)*(?:(?:final|sealed|abstract|case|private|protected|implicit|open)\s+)*(class|trait|object|enum)\s+([A-Za-z_][A-Za-z0-9_$]*)""",
        ).findAll(withoutBlockComments)
            .map { match ->
                ScalaTopLevelDeclaration(
                    kind = match.groupValues[1],
                    name = match.groupValues[2],
                    startLine = withoutBlockComments.take(match.range.first).count { char -> char == '\n' } + 1,
                )
            }
            .toList()
    }

    private fun indexProjectScopeClasses(
        modules: MutableMap<String, JvmModuleSymbol>,
        packages: MutableMap<String, JvmPackageSymbol>,
        classes: MutableMap<String, JvmClassSymbol>,
        methods: MutableMap<String, JvmMethodSymbol>,
        fields: MutableMap<String, JvmFieldSymbol>,
        budget: com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget,
    ) {
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
            indexPsiClass(file, psiClass, modules, packages, classes, methods, fields, budget)
        }
        val allClassesStartedAt = System.nanoTime()
        AllClassesSearch.search(scope, project).forEach(::indexIfNeeded)
        traceStage("jvmSymbolIndex.allClassesSearch") {
            allClassesStartedAt to listOf(
                "classes=${classes.size}",
                "methods=${methods.size}",
                "fields=${fields.size}",
            )
        }
        val shortNamesCache = PsiShortNamesCache.getInstance(project)
        val shortNamesStartedAt = System.nanoTime()
        val allClassNames = shortNamesCache.allClassNames
        allClassNames.forEach { className ->
            checkCanceled()
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

    private fun resourceKind(path: String): JvmResourceKind {
        return when {
            path.startsWith("mq:") -> JvmResourceKind.MQ_TOPIC
            path.contains("META-INF/services/") -> JvmResourceKind.SPI_SERVICE_FILE
            path.endsWith(".xml", ignoreCase = true) -> JvmResourceKind.XML
            path.endsWith(".yml", ignoreCase = true) || path.endsWith(".yaml", ignoreCase = true) -> JvmResourceKind.YAML
            path.endsWith(".properties", ignoreCase = true) -> JvmResourceKind.PROPERTIES
            path.endsWith(".sql", ignoreCase = true) -> JvmResourceKind.SQL
            path.endsWith(".md", ignoreCase = true) -> JvmResourceKind.MARKDOWN
            else -> JvmResourceKind.OTHER
        }
    }

    private fun providerClassNames(file: VirtualFile): List<String> {
        checkCanceled()
        return runCatching { String(file.contentsToByteArray(), StandardCharsets.UTF_8) }
            .getOrDefault("")
            .let(::providerClassNames)
    }

    private fun providerClassNames(text: String): List<String> {
        return text
            .lineSequence()
            .map { line -> line.substringBefore('#').trim() }
            .filter(String::isNotBlank)
            .distinct()
            .toList()
    }

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

    private fun checkCanceled() {
        ProgressManager.checkCanceled()
    }

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

    private fun annotationString(value: com.intellij.psi.PsiAnnotationMemberValue?): String? =
        when (value) {
            is com.intellij.psi.PsiLiteralExpression -> value.value as? String
            is com.intellij.psi.PsiArrayInitializerMemberValue -> value.initializers.firstOrNull()?.let(::annotationString)
            else -> null
        }

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

    private fun PsiClass.docCommentText(): String? =
        docComment?.plainText()

    private fun PsiDocComment.plainText(): String? =
        descriptionElements
            .joinToString(separator = "") { element -> element.text }
            .replace(Regex("""[ \t]*\R[ \t]*"""), "\n")
            .replace(Regex("""[ \t]+"""), " ")
            .trim()
            .ifBlank { null }

    private fun moduleName(file: VirtualFile): String? {
        return ModuleUtilCore.findModuleForFile(file, project)?.name
    }

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

    private fun packageNameForClassEntry(
        qualifiedName: String,
        classEntryName: String?,
        sourceEntryName: String?,
    ): String {
        return packageNameForPath(sourceEntryName, qualifiedName)
            ?: packageNameForPath(classEntryName, qualifiedName)
            ?: outermostPackageFromQualifiedName(qualifiedName)
    }

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

    private fun sourceRef(file: VirtualFile, element: PsiElement): JvmSourceRef {
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

    private fun relativePath(file: VirtualFile): String? {
        val root = ProjectRootManager.getInstance(project).contentRoots
            .firstOrNull { contentRoot -> VfsUtilCore.isAncestor(contentRoot, file, false) }
            ?: return file.path
        return VfsUtilCore.getRelativePath(file, root, '/') ?: file.path
    }

    private fun displayPathForExternalResource(file: VirtualFile): String =
        file.path.substringAfter("!/", missingDelimiterValue = file.path)

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

    private fun jdkHomePath(): String = System.getProperty("java.home").orEmpty()

    private fun String.isJdkQualifiedName(): Boolean =
        startsWith("java.") ||
            startsWith("javax.") ||
            startsWith("jdk.") ||
            startsWith("sun.") ||
            startsWith("com.sun.")

    private data class MethodDescriptor(
        val parameterTypes: List<String>,
        val returnType: String,
    )

    private data class ParsedDescriptorType(
        val typeName: String,
        val nextOffset: Int,
    )

    private companion object {
        private const val ACC_BRIDGE = 0x0040
        private const val ACC_SYNTHETIC = 0x1000
        private const val ACC_ABSTRACT = 0x0400
    }
}

fun stableJvmId(kind: String, rawKey: String): String {
    val normalizedKind = kind.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "jvm" }
    val normalizedKey = rawKey.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "unknown" }
    return "jvm:$normalizedKind:$normalizedKey"
}

fun methodSignature(method: PsiMethod): String {
    val ownerName = method.containingClass?.qualifiedName
        ?: method.containingClass?.name
        ?: method.name
    val ownerPackageName = (method.containingFile as? PsiJavaFile)?.packageName
        ?: (method.navigationElement?.containingFile as? KtFile)?.packageFqName?.asString()
        ?: (method.containingFile as? KtFile)?.packageFqName?.asString()
        ?: method.containingClass?.qualifiedName?.let(::outermostPackageFromQualifiedName)
        ?: ""
    val parameters = method.parameterList.parameters.joinToString(",") { parameter ->
        canonicalTypeTextNear(parameter.type, ownerPackageName) ?: parameter.type.canonicalText
    }
    val returnType = canonicalTypeTextNear(method.returnType, ownerPackageName)
        ?: if (method.isConstructor) ownerName else "void"
    return "$ownerName.${method.name}($parameters):$returnType"
}

fun canonicalTypeText(type: PsiType?): String? {
    val psiType = type ?: return null
    val resolvedClass = runCatching { (psiType as? PsiClassType)?.resolve() }.getOrNull()
    return resolvedClass?.qualifiedName ?: normalizeImplicitJavaLangType(psiType.canonicalText)
}

private fun canonicalTypeTextNear(type: PsiType?, ownerPackageName: String): String? {
    val normalized = canonicalTypeText(type) ?: return null
    if (normalized.contains('.') || ownerPackageName.isBlank()) {
        return normalized
    }
    val psiType = type as? PsiClassType ?: return normalized
    val resolved = runCatching { psiType.resolve() }.getOrNull()
    if (resolved?.qualifiedName != null) {
        return resolved.qualifiedName
    }
    val candidate = "$ownerPackageName.$normalized"
    val project = psiType.resolveScope.project ?: return normalized
    return if (JavaPsiFacade.getInstance(project).findClass(candidate, psiType.resolveScope) != null) {
        candidate
    } else {
        normalized
    }
}

fun fieldTypeReferences(
    type: PsiType?,
    ownerPackageName: String?,
): List<JvmFieldTypeReference> {
    val references = linkedMapOf<String, JvmFieldTypeRole>()

    fun add(typeName: String?, role: JvmFieldTypeRole) {
        val normalized = normalizeReferenceTypeName(typeName, ownerPackageName) ?: return
        val current = references[normalized]
        if (current == null || role.precedence < current.precedence) {
            references[normalized] = role
        }
    }

    fun visit(currentType: PsiType?, role: JvmFieldTypeRole) {
        when (currentType) {
            null -> return
            is PsiArrayType -> visit(currentType.componentType, role.elementRole())
            is PsiWildcardType -> visit(currentType.bound, role)
            is PsiClassType -> {
                val rawName = rawClassTypeName(currentType)
                val parameters = currentType.parameters.toList()
                val category = fieldContainerCategory(rawName)
                when {
                    category == FieldTypeContainerCategory.FUNCTION -> {
                        parameters.dropLast(1).forEach { parameter ->
                            visit(parameter, JvmFieldTypeRole.FUNCTION_PARAMETER)
                        }
                        visit(parameters.lastOrNull(), JvmFieldTypeRole.FUNCTION_RETURN)
                    }
                    category == FieldTypeContainerCategory.PROVIDER -> {
                        if (parameters.isEmpty()) {
                            add(canonicalTypeText(currentType), role)
                        } else {
                            parameters.forEach { parameter ->
                                visit(parameter, JvmFieldTypeRole.PROVIDER_RETURN)
                            }
                        }
                    }
                    category == FieldTypeContainerCategory.MAP -> {
                        parameters.getOrNull(0)?.let { keyType -> visit(keyType, role.mapKeyRole()) }
                        parameters.getOrNull(1)?.let { valueType -> visit(valueType, role.elementRole()) }
                        parameters.drop(2).forEach { parameter -> visit(parameter, role.typeArgumentRole()) }
                    }
                    category == FieldTypeContainerCategory.COLLECTION -> {
                        parameters.forEach { parameter -> visit(parameter, role.elementRole()) }
                    }
                    category == FieldTypeContainerCategory.WRAPPER -> {
                        parameters.forEach { parameter -> visit(parameter, role.wrapperRole()) }
                    }
                    else -> {
                        add(canonicalTypeText(currentType), role)
                        parameters.forEach { parameter -> visit(parameter, role.typeArgumentRole()) }
                    }
                }
            }
            else -> add(canonicalTypeText(currentType) ?: currentType.canonicalText, role)
        }
    }

    visit(type, JvmFieldTypeRole.DIRECT_VALUE)
    return references.map { (typeName, role) -> JvmFieldTypeReference(typeName, role) }
}

private enum class FieldTypeContainerCategory {
    COLLECTION,
    MAP,
    PROVIDER,
    FUNCTION,
    WRAPPER,
    OTHER,
}

private val JvmFieldTypeRole.precedence: Int
    get() = when (this) {
        JvmFieldTypeRole.DIRECT_VALUE -> 0
        JvmFieldTypeRole.COLLECTION_ELEMENT -> 1
        JvmFieldTypeRole.MAP_VALUE -> 2
        JvmFieldTypeRole.MAP_KEY -> 3
        JvmFieldTypeRole.WRAPPER_VALUE -> 4
        JvmFieldTypeRole.TYPE_ARGUMENT -> 5
        JvmFieldTypeRole.PROVIDER_RETURN -> 6
        JvmFieldTypeRole.FUNCTION_RETURN -> 7
        JvmFieldTypeRole.FUNCTION_PARAMETER -> 8
    }

private fun JvmFieldTypeRole.elementRole(): JvmFieldTypeRole =
    when (this) {
        JvmFieldTypeRole.PROVIDER_RETURN,
        JvmFieldTypeRole.FUNCTION_RETURN,
        -> this
        JvmFieldTypeRole.FUNCTION_PARAMETER -> JvmFieldTypeRole.FUNCTION_PARAMETER
        else -> JvmFieldTypeRole.COLLECTION_ELEMENT
    }

private fun JvmFieldTypeRole.mapKeyRole(): JvmFieldTypeRole =
    when (this) {
        JvmFieldTypeRole.PROVIDER_RETURN,
        JvmFieldTypeRole.FUNCTION_RETURN,
        -> this
        JvmFieldTypeRole.FUNCTION_PARAMETER -> JvmFieldTypeRole.FUNCTION_PARAMETER
        else -> JvmFieldTypeRole.MAP_KEY
    }

private fun JvmFieldTypeRole.wrapperRole(): JvmFieldTypeRole =
    when (this) {
        JvmFieldTypeRole.PROVIDER_RETURN,
        JvmFieldTypeRole.FUNCTION_RETURN,
        JvmFieldTypeRole.FUNCTION_PARAMETER,
        -> this
        else -> JvmFieldTypeRole.WRAPPER_VALUE
    }

private fun JvmFieldTypeRole.typeArgumentRole(): JvmFieldTypeRole =
    when (this) {
        JvmFieldTypeRole.PROVIDER_RETURN,
        JvmFieldTypeRole.FUNCTION_RETURN,
        JvmFieldTypeRole.FUNCTION_PARAMETER,
        -> this
        else -> JvmFieldTypeRole.TYPE_ARGUMENT
    }

private fun rawClassTypeName(type: PsiClassType): String? {
    val resolved = runCatching { type.resolve() }.getOrNull()
    return resolved?.qualifiedName ?: normalizeReferenceTypeName(type.rawType().canonicalText, null)
}

private fun fieldContainerCategory(rawName: String?): FieldTypeContainerCategory {
    val normalized = rawName?.removeSuffix("?") ?: return FieldTypeContainerCategory.OTHER
    if (isJvmFunctionType(normalized)) {
        return FieldTypeContainerCategory.FUNCTION
    }
    val simpleName = normalized.substringAfterLast('.')
    if (normalized in providerTypeNames || simpleName in providerSimpleTypeNames) {
        return FieldTypeContainerCategory.PROVIDER
    }
    if (normalized in mapTypeNames || simpleName in mapSimpleTypeNames) {
        return FieldTypeContainerCategory.MAP
    }
    if (normalized in collectionTypeNames || simpleName in collectionSimpleTypeNames) {
        return FieldTypeContainerCategory.COLLECTION
    }
    if (normalized in wrapperTypeNames || simpleName in wrapperSimpleTypeNames) {
        return FieldTypeContainerCategory.WRAPPER
    }
    return FieldTypeContainerCategory.OTHER
}

private fun isJvmFunctionType(typeName: String): Boolean =
    typeName == "kotlin.Function" ||
        Regex("""^(kotlin\.|kotlin\.jvm\.functions\.)Function\d+$""").matches(typeName)

private fun normalizeReferenceTypeName(
    typeName: String?,
    ownerPackageName: String?,
): String? {
    var normalized = typeName
        ?.trim()
        ?.removeSuffix("?")
        ?.takeIf(String::isNotBlank)
        ?: return null
    while (normalized.endsWith("[]")) {
        normalized = normalized.removeSuffix("[]")
    }
    normalized = normalizeImplicitJavaLangType(normalized.substringBefore('<'))
    if (normalized.isBlank() || normalized in primitiveTypeNames) {
        return null
    }
    if (normalized.contains('.') || ownerPackageName.isNullOrBlank()) {
        return normalized
    }
    return "$ownerPackageName.$normalized"
}

private fun normalizeImplicitJavaLangType(typeText: String): String {
    return when (typeText) {
        "String" -> "java.lang.String"
        "Object" -> "java.lang.Object"
        "Integer" -> "java.lang.Integer"
        "Long" -> "java.lang.Long"
        "Boolean" -> "java.lang.Boolean"
        "Double" -> "java.lang.Double"
        "Float" -> "java.lang.Float"
        "Short" -> "java.lang.Short"
        "Byte" -> "java.lang.Byte"
        "Character" -> "java.lang.Character"
        "Void" -> "java.lang.Void"
        else -> typeText
    }
}

private fun outermostPackageFromQualifiedName(qualifiedName: String): String {
    val parts = qualifiedName.substringBefore('$').split('.').filter(String::isNotBlank)
    val classIndex = parts.indexOfFirst { part -> part.firstOrNull()?.isUpperCase() == true }
    return when {
        classIndex > 0 -> parts.take(classIndex).joinToString(".")
        else -> qualifiedName.substringBeforeLast('.', missingDelimiterValue = "")
    }
}

private fun String.hasExcludedContentRootSegment(): Boolean {
    val segments = split('/').filter(String::isNotBlank)
    return segments.withIndex().any { (index, segment) ->
        segment in alwaysExcludedContentRootSegments ||
            segment in generatedContentRootSegments && "src" !in segments.take(index)
    }
}

private val alwaysExcludedContentRootSegments = setOf(
    ".cache",
    ".git",
    ".gradle",
    ".idea",
    ".next",
    ".nuxt",
    ".parcel-cache",
    "build-idea-sandbox",
    "node_modules",
)

private val generatedContentRootSegments = setOf(
    "build",
    "coverage",
    "dist",
    "out",
    "target",
    "temp",
    "tmp",
)

private val primitiveTypeNames = setOf(
    "boolean",
    "byte",
    "char",
    "double",
    "float",
    "int",
    "long",
    "short",
    "void",
)

private val collectionTypeNames = setOf(
    "java.lang.Iterable",
    "java.util.Collection",
    "java.util.List",
    "java.util.Set",
    "java.util.Queue",
    "java.util.Deque",
    "java.util.SortedSet",
    "java.util.NavigableSet",
    "kotlin.collections.Collection",
    "kotlin.collections.Iterable",
    "kotlin.collections.List",
    "kotlin.collections.MutableCollection",
    "kotlin.collections.MutableIterable",
    "kotlin.collections.MutableList",
    "kotlin.collections.MutableSet",
    "kotlin.collections.Set",
    "scala.collection.Iterable",
    "scala.collection.Seq",
    "scala.collection.Set",
)
private val collectionSimpleTypeNames = setOf(
    "Collection",
    "Deque",
    "Iterable",
    "List",
    "MutableCollection",
    "MutableIterable",
    "MutableList",
    "MutableSet",
    "NavigableSet",
    "Queue",
    "Seq",
    "Set",
    "SortedSet",
)

private val mapTypeNames = setOf(
    "java.util.Map",
    "java.util.SortedMap",
    "java.util.NavigableMap",
    "java.util.concurrent.ConcurrentMap",
    "kotlin.collections.Map",
    "kotlin.collections.MutableMap",
    "scala.collection.Map",
)
private val mapSimpleTypeNames = setOf(
    "ConcurrentMap",
    "Map",
    "MutableMap",
    "NavigableMap",
    "SortedMap",
)

private val providerTypeNames = setOf(
    "com.google.inject.Provider",
    "dagger.Lazy",
    "javax.inject.Provider",
    "jakarta.inject.Provider",
    "java.util.concurrent.Callable",
    "java.util.function.Supplier",
    "kotlin.Lazy",
    "org.springframework.beans.factory.ObjectFactory",
    "org.springframework.beans.factory.ObjectProvider",
    "reactor.core.publisher.Flux",
    "reactor.core.publisher.Mono",
)
private val providerSimpleTypeNames = setOf(
    "Callable",
    "Flux",
    "Lazy",
    "Mono",
    "ObjectFactory",
    "ObjectProvider",
    "Provider",
    "Supplier",
)

private val wrapperTypeNames = setOf(
    "java.lang.ref.Reference",
    "java.lang.ref.SoftReference",
    "java.lang.ref.WeakReference",
    "java.util.Optional",
    "java.util.OptionalDouble",
    "java.util.OptionalInt",
    "java.util.OptionalLong",
    "kotlin.Result",
)
private val wrapperSimpleTypeNames = setOf(
    "Optional",
    "OptionalDouble",
    "OptionalInt",
    "OptionalLong",
    "Reference",
    "Result",
    "SoftReference",
    "WeakReference",
)
