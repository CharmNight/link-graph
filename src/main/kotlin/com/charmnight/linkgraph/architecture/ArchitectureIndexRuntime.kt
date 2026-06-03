package com.charmnight.linkgraph.architecture

import com.charmnight.linkgraph.architecture.query.ArchitectureGraphQueryService
import com.charmnight.linkgraph.architecture.memory.ArchitectureIndexFragmentCacheKey
import com.charmnight.linkgraph.architecture.memory.ArchitectureIndexMemorySnapshot
import com.charmnight.linkgraph.architecture.memory.ArchitectureIndexPersistentFragmentCache
import com.charmnight.linkgraph.architecture.memory.ArchitectureIndexSliceFragment
import com.charmnight.linkgraph.architecture.memory.FieldTypeReferenceSliceFragment
import com.charmnight.linkgraph.architecture.memory.PersistentArchitectureIndexCacheStore
import com.charmnight.linkgraph.architecture.memory.ProjectFileFingerprint
import com.charmnight.linkgraph.architecture.memory.ProjectSlice
import com.charmnight.linkgraph.architecture.memory.ProjectSliceInputFile
import com.charmnight.linkgraph.architecture.memory.ProjectSliceKind
import com.charmnight.linkgraph.architecture.memory.ProjectSlicePlanner
import com.charmnight.linkgraph.architecture.memory.RelationSliceFragment
import com.charmnight.linkgraph.architecture.memory.ResourceSliceFragment
import com.charmnight.linkgraph.architecture.memory.ServiceProviderSliceFragment
import com.charmnight.linkgraph.architecture.memory.SymbolSliceFragment
import com.charmnight.linkgraph.foundation.LinkGraphDebugEnvironment
import com.charmnight.linkgraph.foundation.LinkGraphRenderTrace
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmFieldSymbol
import com.charmnight.linkgraph.jvm.index.JvmMethodSymbol
import com.charmnight.linkgraph.jvm.index.JvmServiceProviderFile
import com.charmnight.linkgraph.jvm.index.JvmServiceProviderIndex
import com.charmnight.linkgraph.jvm.index.JvmResourceSymbol
import com.charmnight.linkgraph.jvm.index.JvmSourceRef
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndexBuilder
import com.charmnight.linkgraph.jvm.index.PsiJvmSourceTextSymbolExtractor
import com.charmnight.linkgraph.jvm.relation.JvmRelationResolverRegistry
import com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget
import com.charmnight.linkgraph.jvm.relation.JvmResolutionContext
import com.charmnight.linkgraph.review.git.GitBaselineSymbolMapper
import com.charmnight.linkgraph.review.git.GitChangeSetProvider
import com.charmnight.linkgraph.review.ReviewGraphQueryService
import com.charmnight.linkgraph.settings.LinkGraphSettingsService
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.source.SourceContentResolver
import com.charmnight.linkgraph.source.SourceContentResolverFactory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class ArchitectureIndexRuntime(
    private val project: Project,
) {
    private val relationResolverRegistry = JvmRelationResolverRegistry()
    private val sourceResolverFactory = SourceContentResolverFactory(project, ::settingsSnapshot)
    private val logger = Logger.getInstance(ArchitectureIndexRuntime::class.java)
    private val traceEnabled: Boolean =
        LinkGraphDebugEnvironment.isEnabled("LINKGRAPH_DEBUG_TRACE")
    private val buildLocks = ConcurrentHashMap<ArchitectureGraphCacheKey, Any>()
    private val persistentCacheStore: PersistentArchitectureIndexCacheStore by lazy {
        PersistentArchitectureIndexCacheStore(Path.of(PathManager.getSystemPath()))
    }
    private val persistentFragmentCache: ArchitectureIndexPersistentFragmentCache by lazy {
        ArchitectureIndexPersistentFragmentCache(persistentCacheStore)
    }

    fun currentIndex(): ArchitectureGraphIndex? =
        project.architectureIndexService().currentIndex()

    fun index(
        budget: JvmResolutionBudget = defaultBudget(),
        symbolIndexHint: JvmSymbolIndex? = null,
        recordAsCurrent: Boolean = true,
        forceRebuild: Boolean = false,
    ): ArchitectureGraphIndex {
        val settings = settingsSnapshot()
        val sourceComponentsStartedAt = System.nanoTime()
        val sourceComponents = sourceResolverFactory.create(budget, settings)
        traceStage("architectureIndex.sourceComponents", sourceComponentsStartedAt) {
            listOf(
                "includeUserAttachedJars=${budget.includeUserAttachedJars}",
                "attachedJarFingerprints=${sourceComponents.attachedJarIndex.fingerprints.size}",
            )
        }
        val cacheKeyStartedAt = System.nanoTime()
        val cacheKey = readActionIfNeeded {
            ArchitectureGraphCacheKey.from(project, budget, settings.attachedJars, sourceComponents.attachedJarIndex)
        }
        traceStage("architectureIndex.cacheKey", cacheKeyStartedAt) {
            listOf(
                "includeTests=${budget.includeTests}",
                "includeExternalLibraries=${budget.includeExternalLibraries}",
                "includeJdk=${budget.includeJdk}",
                "includeUserAttachedJars=${budget.includeUserAttachedJars}",
                "maxProjectClasses=${budget.maxProjectClasses}",
                "maxMethods=${budget.maxMethods}",
                "maxRelations=${budget.maxRelations}",
                "attachedJars=${cacheKey.attachedJars.size}",
            )
        }
        if (!forceRebuild) {
            project.architectureIndexService().getCachedIndex(cacheKey)?.let { index ->
                project.architectureIndexService().recordCurrentIndex(index, recordAsCurrent)
                return index
            }
        }
        val lock = buildLocks.computeIfAbsent(cacheKey) { Any() }
        return synchronized(lock) {
            try {
                if (!forceRebuild) {
                    project.architectureIndexService().getCachedIndex(cacheKey)?.let { index ->
                        project.architectureIndexService().recordCurrentIndex(index, recordAsCurrent)
                        return@synchronized index
                    }
                    if (symbolIndexHint == null && supportsPersistentSliceCache(budget)) {
                        val manifest = currentProjectSliceManifest(sourceComponents, cacheKey)
                        if (manifest.slices.isNotEmpty()) {
                            val staleSliceIds = project.architectureIndexService().memorySnapshot().staleSliceIds.toSet()
                            val restored = persistentFragmentCache.restoreCompleteIndex(
                                manifest = manifest,
                                staleSliceIds = staleSliceIds,
                                cacheKeyForSlice = { slice -> fragmentCacheKey(cacheKey, slice) },
                                budget = budget,
                            )
                            restored.index?.let { index ->
                                project.architectureIndexService().recordMemorySnapshot(
                                    ArchitectureIndexMemorySnapshot(
                                        manifest = manifest,
                                        staleSliceIds = emptyList(),
                                        persistentCacheHits = restored.hits,
                                        persistentCacheMisses = restored.misses,
                                        lastUpdatedAtEpochMillis = System.currentTimeMillis(),
                                        indexSource = "PERSISTENT_FULL_HIT",
                                    ),
                                )
                                return@synchronized project.architectureIndexService().putCachedIndex(cacheKey, index, recordAsCurrent)
                            }
                            rebuildFromPersistentFragments(
                                cachedFragments = restored.fragments,
                                rebuildSliceIds = restored.missingSliceIds,
                                manifest = manifest,
                                sourceComponents = sourceComponents,
                                cacheKey = cacheKey,
                                budget = budget,
                            )?.let { index ->
                                project.architectureIndexService().recordMemorySnapshot(
                                    ArchitectureIndexMemorySnapshot(
                                        manifest = manifest,
                                        staleSliceIds = emptyList(),
                                        persistentCacheHits = restored.hits,
                                        persistentCacheMisses = restored.misses,
                                        lastUpdatedAtEpochMillis = System.currentTimeMillis(),
                                        indexSource = "PERSISTENT_PARTIAL",
                                    ),
                                )
                                return@synchronized project.architectureIndexService().putCachedIndex(cacheKey, index, recordAsCurrent)
                            }
                        }
                    }
                }
                val manifest = if (supportsPersistentSliceCache(budget)) {
                    currentProjectSliceManifest(sourceComponents, cacheKey)
                } else {
                    null
                }
                val built = buildUncachedIndex(
                    budget = budget,
                    symbolIndexHint = symbolIndexHint,
                    sourceComponents = sourceComponents,
                )
                recordProjectMemory(
                    index = built,
                    sourceComponents = sourceComponents,
                    cacheKey = cacheKey,
                    manifestHint = manifest,
                    writePersistentFragments = manifest != null,
                )
                project.architectureIndexService().putCachedIndex(cacheKey, built, recordAsCurrent)
            } finally {
                buildLocks.remove(cacheKey, lock)
            }
        }
    }

    fun sourceQuery(budget: JvmResolutionBudget = defaultBudget()): SourceContentResolver =
        sourceResolverFactory.create(budget).resolver

    fun symbolQuery(budget: JvmResolutionBudget = defaultBudget()): ArchitectureGraphQueryService =
        queryService(index(budget, recordAsCurrent = false))

    fun relationQuery(budget: JvmResolutionBudget = defaultBudget()): ArchitectureGraphQueryService =
        queryService(index(budget, recordAsCurrent = false))

    fun architectureGraphQuery(budget: JvmResolutionBudget = defaultBudget()): ArchitectureGraphQueryService =
        queryService(index(budget, recordAsCurrent = false))

    fun classDiagramQuery(budget: JvmResolutionBudget = defaultBudget()): ArchitectureGraphQueryService =
        queryService(index(budget, recordAsCurrent = false))

    fun classDiagramStructureIndex(
        budget: JvmResolutionBudget = defaultBudget(),
        forceRebuild: Boolean = false,
    ): ArchitectureGraphIndex =
        classDiagramStructureIndexWithBoundedReadActions(budget, forceRebuild)

    fun architectureOverviewIndex(
        budget: JvmResolutionBudget = defaultBudget(),
        forceRebuild: Boolean = false,
        recordAsCurrent: Boolean = true,
    ): ArchitectureGraphIndex =
        architectureOverviewIndexWithBoundedReadActions(budget, forceRebuild, recordAsCurrent)

    fun hasCachedFullIndex(budget: JvmResolutionBudget = defaultBudget()): Boolean {
        val settings = settingsSnapshot()
        val sourceComponents = sourceResolverFactory.create(budget, settings)
        val cacheKey = ArchitectureGraphCacheKey.from(project, budget, settings.attachedJars, sourceComponents.attachedJarIndex)
        return project.architectureIndexService().getCachedIndex(cacheKey) != null
    }

    fun reviewQuery(
        budget: JvmResolutionBudget = defaultBudget(),
        index: ArchitectureGraphIndex = index(budget, recordAsCurrent = false),
    ): ReviewGraphQueryService =
        ReviewGraphQueryService(
            index,
            sourceResolver = sourceQuery(budget),
            baselineSymbolMapper = GitBaselineSymbolMapper(
                GitChangeSetProvider(project.basePath),
                PsiJvmSourceTextSymbolExtractor(project),
            ),
        )

    fun invalidate() {
        project.architectureIndexService().invalidate()
    }

    fun defaultBudget(): JvmResolutionBudget {
        val settings = settingsSnapshot()
        return JvmResolutionBudget(
            includeTests = true,
            includeExternalLibraries = settings.allowExternalLibraryExpansion,
            includeJdk = settings.allowJdkLibraryExpansion,
            includeUserAttachedJars = settings.attachedJars.isNotEmpty(),
            maxExternalClasses = settings.maxExternalClassNodes,
        )
    }

    fun settingsSnapshot(): LinkGraphSettingsState =
        runCatching {
            ApplicationManager.getApplication().getService(LinkGraphSettingsService::class.java).nonSecretSnapshot()
        }.getOrDefault(LinkGraphSettingsState())

    private fun queryService(index: ArchitectureGraphIndex): ArchitectureGraphQueryService =
        ArchitectureGraphQueryService(
            index = index,
            memorySnapshot = project.architectureIndexService().memorySnapshot(),
        )

    private fun recordProjectMemory(
        index: ArchitectureGraphIndex,
        sourceComponents: com.charmnight.linkgraph.source.SourceContentResolverComponents,
        cacheKey: ArchitectureGraphCacheKey,
        manifestHint: com.charmnight.linkgraph.architecture.memory.ProjectSliceManifest? = null,
        writePersistentFragments: Boolean = true,
    ) {
        val snapshot = runCatching {
            if (!writePersistentFragments && manifestHint == null) {
                return@runCatching ArchitectureIndexMemorySnapshot(
                    lastUpdatedAtEpochMillis = System.currentTimeMillis(),
                    indexSource = "FULL_REBUILD",
                )
            }
            val manifest = manifestHint ?: buildProjectSliceManifest(
                symbolIndex = index.symbolIndex,
                sourceComponents = sourceComponents,
                cacheKey = cacheKey,
            )
            var hits = 0
            var misses = 0
            if (writePersistentFragments) {
                manifest.slices.forEach { slice ->
                    val fragmentKey = fragmentCacheKey(cacheKey, slice)
                    if (persistentCacheStore.read(fragmentKey) != null) {
                        hits += 1
                    } else {
                        misses += 1
                        persistentCacheStore.write(fragmentKey, fragmentForSlice(slice, index))
                    }
                }
            }
            ArchitectureIndexMemorySnapshot(
                manifest = manifest,
                staleSliceIds = emptyList(),
                persistentCacheHits = hits,
                persistentCacheMisses = misses,
                lastUpdatedAtEpochMillis = System.currentTimeMillis(),
                indexSource = "FULL_REBUILD",
            )
        }.getOrElse { error ->
            logger.warn("Failed to refresh architecture index memory cache", error)
            ArchitectureIndexMemorySnapshot(lastUpdatedAtEpochMillis = System.currentTimeMillis())
        }
        project.architectureIndexService().recordMemorySnapshot(snapshot)
    }

    private fun supportsPersistentSliceCache(budget: JvmResolutionBudget): Boolean =
        !budget.includeExternalLibraries && !budget.includeJdk

    private fun currentProjectSliceManifest(
        sourceComponents: com.charmnight.linkgraph.source.SourceContentResolverComponents,
        cacheKey: ArchitectureGraphCacheKey,
    ): com.charmnight.linkgraph.architecture.memory.ProjectSliceManifest =
        ProjectSlicePlanner(projectLocationHash = cacheKey.projectLocationHash.toString()).plan(
            currentProjectSliceInputFiles(sourceComponents),
        )

    private fun currentProjectSliceInputFiles(
        sourceComponents: com.charmnight.linkgraph.source.SourceContentResolverComponents,
    ): List<ProjectSliceInputFile> {
        val files = linkedMapOf<String, ProjectSliceInputFile>()
        readActionIfNeeded {
            val projectFileIndex = ProjectFileIndex.getInstance(project)
            ProjectRootManager.getInstance(project).contentRoots.forEach { root ->
                VfsUtilCore.iterateChildrenRecursively(
                    root,
                    null,
                ) { file ->
                    if (file.isDirectory) {
                        return@iterateChildrenRecursively true
                    }
                    val relativePath = toProjectRelativePath(file.path).takeIf(String::isNotBlank) ?: return@iterateChildrenRecursively true
                    if (!isIndexAffectingFile(file, relativePath)) {
                        return@iterateChildrenRecursively true
                    }
                    files.putIfAbsent(
                        relativePath,
                        ProjectSliceInputFile(
                            moduleName = projectFileIndex.getModuleForFile(file)?.name,
                            contentRoot = project.basePath ?: root.path,
                            relativePath = relativePath,
                            size = file.length,
                            modifiedAtMillis = file.timeStamp,
                            contentSha256 = null,
                        ),
                    )
                    true
                }
            }
        }
        sourceComponents.attachedJarIndex.fingerprints.forEach { fingerprint ->
            val relativePath = fingerprint.path.replace('\\', '/')
            files[relativePath] = ProjectSliceInputFile(
                moduleName = null,
                contentRoot = relativePath.substringBeforeLast('/', missingDelimiterValue = "attached-jars"),
                relativePath = relativePath,
                size = fingerprint.classJarSize,
                modifiedAtMillis = fingerprint.classJarLastModifiedMillis,
                contentSha256 = fingerprint.classJarSha256,
                attachedJarFingerprint = fingerprint.classJarSha256,
            )
        }
        return files.values.toList()
    }

    private fun rebuildFromPersistentFragments(
        cachedFragments: List<ArchitectureIndexSliceFragment>,
        rebuildSliceIds: Set<String>,
        manifest: com.charmnight.linkgraph.architecture.memory.ProjectSliceManifest,
        sourceComponents: com.charmnight.linkgraph.source.SourceContentResolverComponents,
        cacheKey: ArchitectureGraphCacheKey,
        budget: JvmResolutionBudget,
    ): ArchitectureGraphIndex? {
        if (cachedFragments.isEmpty() || rebuildSliceIds.isEmpty()) {
            return null
        }
        val rebuildSlices = manifest.slices.filter { slice -> slice.id in rebuildSliceIds }
        if (rebuildSlices.isEmpty() || rebuildSlices.any { slice -> slice.kind == ProjectSliceKind.ATTACHED_JAR.name }) {
            return null
        }
        val cachedMerged = com.charmnight.linkgraph.architecture.memory.ArchitectureIndexFragmentMerger().merge(cachedFragments)
        val rebuiltSymbolIndex = readActionIfNeeded {
            val rebuildFiles = rebuildSlices
                .flatMap(ProjectSlice::files)
                .mapTo(hashSetOf(), ProjectFileFingerprint::relativePath)
            JvmSymbolIndexBuilder(
                project = project,
                attachedJarIndexProvider = { sourceComponents.attachedJarIndex },
                trace = { event ->
                    traceStage(event.stage, event.startedAtNanos, event.details)
                },
                fileFilter = { file ->
                    pathMatchesSliceFiles(toProjectRelativePath(file.path), rebuildFiles)
                },
            ).build(budget.copy(includeUserAttachedJars = false, includeExternalLibraries = false, includeJdk = false))
        }
        val symbolIndex = mergeSymbolIndexes(cachedMerged.symbolIndex, rebuiltSymbolIndex)
        val relationIndex = readActionIfNeeded {
            relationResolverRegistry.resolveAll(
                JvmResolutionContext(
                    project = project,
                    symbolIndex = symbolIndex,
                    sourceResolver = sourceComponents.resolver,
                    budget = budget,
                ),
                traceStage = ::traceStage,
            )
        }
        val index = ArchitectureGraphIndex.from(symbolIndex, relationIndex, budget = budget)
        rebuildSlices.forEach { slice ->
            persistentCacheStore.write(fragmentCacheKey(cacheKey, slice), fragmentForSlice(slice, index))
        }
        return index
    }

    private fun mergeSymbolIndexes(
        cached: JvmSymbolIndex,
        rebuilt: JvmSymbolIndex,
    ): JvmSymbolIndex =
        JvmSymbolIndex(
            modulesByName = cached.modulesByName + rebuilt.modulesByName,
            packagesByName = cached.packagesByName + rebuilt.packagesByName,
            classesByQualifiedName = cached.classesByQualifiedName + rebuilt.classesByQualifiedName,
            methodsBySignature = cached.methodsBySignature + rebuilt.methodsBySignature,
            fieldsByQualifiedName = cached.fieldsByQualifiedName + rebuilt.fieldsByQualifiedName,
            resourcesByPath = cached.resourcesByPath + rebuilt.resourcesByPath,
            serviceProviderIndex = JvmServiceProviderIndex(
                mergeServiceProviderFiles(
                    cached.serviceProviderIndex.filesByInterfaceName,
                    rebuilt.serviceProviderIndex.filesByInterfaceName,
                ),
            ),
        )

    private fun mergeServiceProviderFiles(
        cached: Map<String, List<JvmServiceProviderFile>>,
        rebuilt: Map<String, List<JvmServiceProviderFile>>,
    ): Map<String, List<JvmServiceProviderFile>> =
        (cached.keys + rebuilt.keys).associateWith { interfaceName ->
            (cached[interfaceName].orEmpty() + rebuilt[interfaceName].orEmpty())
                .distinctBy { file -> file.resource.path to file.providerClassNames }
        }

    private fun isIndexAffectingFile(file: VirtualFile, relativePath: String): Boolean {
        if (relativePath.contains("/META-INF/services/")) {
            return true
        }
        return file.extension?.lowercase() in setOf(
            "java",
            "kt",
            "kts",
            "xml",
            "yml",
            "yaml",
            "properties",
            "sql",
            "md",
        )
    }

    private fun buildProjectSliceManifest(
        symbolIndex: JvmSymbolIndex,
        sourceComponents: com.charmnight.linkgraph.source.SourceContentResolverComponents,
        cacheKey: ArchitectureGraphCacheKey,
    ) = ProjectSlicePlanner(projectLocationHash = cacheKey.projectLocationHash.toString()).plan(
        sourceInputFiles(symbolIndex, sourceComponents),
    )

    private fun sourceInputFiles(
        symbolIndex: JvmSymbolIndex,
        sourceComponents: com.charmnight.linkgraph.source.SourceContentResolverComponents,
    ): List<ProjectSliceInputFile> {
        val files = linkedMapOf<String, ProjectSliceInputFile>()
        fun addSource(displayPath: String?, moduleName: String?) {
            val relativePath = toProjectRelativePath(displayPath ?: return).takeIf(String::isNotBlank) ?: return
            val metadata = fileFingerprint(relativePath, contentSha256 = null)
            files.putIfAbsent(
                relativePath,
                ProjectSliceInputFile(
                    moduleName = moduleName,
                    contentRoot = project.basePath ?: "project",
                    relativePath = relativePath,
                    size = metadata.size,
                    modifiedAtMillis = metadata.modifiedAtMillis,
                    contentSha256 = metadata.contentSha256,
                ),
            )
        }
        symbolIndex.classesByQualifiedName.values.forEach { symbol ->
            addSource(symbol.source?.displayPath, symbol.moduleName)
        }
        symbolIndex.methodsBySignature.values.forEach { symbol ->
            addSource(symbol.source?.displayPath, null)
        }
        symbolIndex.fieldsByQualifiedName.values.forEach { symbol ->
            addSource(symbol.source?.displayPath, null)
        }
        symbolIndex.resourcesByPath.values.forEach { resource ->
            addSource(resource.source?.displayPath ?: resource.path, null)
        }
        sourceComponents.attachedJarIndex.fingerprints.forEach { fingerprint ->
            val relativePath = fingerprint.path.replace('\\', '/')
            files[relativePath] = ProjectSliceInputFile(
                moduleName = null,
                contentRoot = relativePath.substringBeforeLast('/', missingDelimiterValue = "attached-jars"),
                relativePath = relativePath,
                size = fingerprint.classJarSize,
                modifiedAtMillis = fingerprint.classJarLastModifiedMillis,
                contentSha256 = fingerprint.classJarSha256,
                attachedJarFingerprint = fingerprint.classJarSha256,
            )
        }
        return files.values.toList()
    }

    private fun fragmentCacheKey(
        cacheKey: ArchitectureGraphCacheKey,
        slice: ProjectSlice,
    ): ArchitectureIndexFragmentCacheKey =
        ArchitectureIndexFragmentCacheKey(
            projectLocationHash = cacheKey.projectLocationHash.toString(),
            budgetHash = budgetHash(cacheKey),
            sliceId = slice.id,
            fileHash = sliceFileHash(slice.files),
            attachedJarFingerprint = slice.files
                .firstOrNull { slice.kind == ProjectSliceKind.ATTACHED_JAR.name }
                ?.contentSha256,
        )

    private fun fragmentForSlice(
        slice: ProjectSlice,
        index: ArchitectureGraphIndex,
    ): ArchitectureIndexSliceFragment {
        val sliceFiles = slice.files.mapTo(hashSetOf(), ProjectFileFingerprint::relativePath)
        val symbols = index.symbolIndex.symbolsById.values
            .filterNot { symbol -> symbol is JvmResourceSymbol }
            .filter { symbol -> toProjectRelativePath(symbol.source?.displayPath).let { path -> path.isNotBlank() && pathMatchesSliceFiles(path, sliceFiles) } }
            .mapNotNull { symbol ->
                when (symbol) {
                    is JvmClassSymbol -> SymbolSliceFragment(
                        id = symbol.id,
                        qualifiedName = symbol.qualifiedName,
                        simpleName = symbol.simpleName,
                        kind = "CLASS",
                        sourcePath = persistedSourcePath(symbol.source),
                        sourceVirtualFileUrl = sourceVirtualFileUrl(symbol.source),
                        sourceStartLine = symbol.source?.startLine,
                        sourceEndLine = symbol.source?.endLine,
                        sourceDecompiled = symbol.source?.decompiled ?: false,
                        moduleName = symbol.moduleName,
                        packageName = symbol.packageName,
                        classKind = symbol.kind.name,
                        stereotype = symbol.stereotype.name,
                        external = symbol.external,
                        library = symbol.library,
                        jdk = symbol.jdk,
                        testSource = symbol.testSource,
                        abstract = symbol.abstract,
                        superClassName = symbol.superClassName,
                        interfaceNames = symbol.interfaceNames,
                        docComment = symbol.docComment,
                        origin = symbol.origin.name,
                    )
                    is JvmMethodSymbol -> SymbolSliceFragment(
                        id = symbol.id,
                        qualifiedName = symbol.qualifiedName,
                        simpleName = symbol.simpleName,
                        kind = "METHOD",
                        sourcePath = persistedSourcePath(symbol.source),
                        sourceVirtualFileUrl = sourceVirtualFileUrl(symbol.source),
                        sourceStartLine = symbol.source?.startLine,
                        sourceEndLine = symbol.source?.endLine,
                        sourceDecompiled = symbol.source?.decompiled ?: false,
                        ownerClassName = symbol.ownerClassName,
                        signature = symbol.signature,
                        parameterTypes = symbol.parameterTypes,
                        returnType = symbol.returnType,
                        abstract = symbol.abstract,
                        origin = symbol.origin.name,
                    )
                    is JvmFieldSymbol -> SymbolSliceFragment(
                        id = symbol.id,
                        qualifiedName = symbol.qualifiedName,
                        simpleName = symbol.simpleName,
                        kind = "FIELD",
                        sourcePath = persistedSourcePath(symbol.source),
                        sourceVirtualFileUrl = sourceVirtualFileUrl(symbol.source),
                        sourceStartLine = symbol.source?.startLine,
                        sourceEndLine = symbol.source?.endLine,
                        sourceDecompiled = symbol.source?.decompiled ?: false,
                        ownerClassName = symbol.ownerClassName,
                        typeName = symbol.typeName,
                        typeReferences = fieldTypeReferenceFragments(symbol),
                        origin = symbol.origin.name,
                    )
                    else -> null
                }
            }
        val resources = index.symbolIndex.resourcesByPath.values
            .filter { resource ->
                val path = toProjectRelativePath(resource.source?.displayPath ?: resource.path)
                path.isNotBlank() && pathMatchesSliceFiles(path, sliceFiles)
            }
            .map { resource ->
                ResourceSliceFragment(
                    id = resource.id,
                    path = persistedDisplayPath(resource.source?.displayPath ?: resource.path),
                    kind = resource.kind.name,
                    sourceVirtualFileUrl = resource.source?.virtualFileUrl,
                    sourceStartLine = resource.source?.startLine,
                    sourceEndLine = resource.source?.endLine,
                    sourceDecompiled = resource.source?.decompiled ?: false,
                    origin = resource.origin.name,
                )
            }
        val resourceIds = resources.map(ResourceSliceFragment::id).toSet()
        val resourcePaths = resources.map { resource -> toProjectRelativePath(resource.path) }.toSet()
        val serviceProviders = index.symbolIndex.serviceProviderIndex.filesByInterfaceName.values
            .flatten()
            .filter { providerFile ->
                providerFile.resource.id in resourceIds ||
                    toProjectRelativePath(providerFile.resource.source?.displayPath ?: providerFile.resource.path) in resourcePaths
            }
            .map { providerFile ->
                ServiceProviderSliceFragment(
                    serviceInterfaceName = providerFile.serviceInterfaceName,
                    providerClassNames = providerFile.providerClassNames,
                    resourceId = providerFile.resource.id,
                    resourcePath = persistedDisplayPath(providerFile.resource.source?.displayPath ?: providerFile.resource.path),
                    resourceKind = providerFile.resource.kind.name,
                    origin = providerFile.origin.name,
                )
            }
        val localSymbolIds = (symbols.map(SymbolSliceFragment::id) + resources.map(ResourceSliceFragment::id)).toSet()
        val relations = index.relationIndex.relations
            .filter { relation -> relation.fromSymbolId in localSymbolIds || relation.toSymbolId in localSymbolIds }
            .map { relation ->
                RelationSliceFragment(
                    id = relation.id,
                    kind = relation.kind.name,
                    fromSymbolId = relation.fromSymbolId,
                    toSymbolId = relation.toSymbolId,
                    metadata = relation.metadata,
                    confidence = relation.confidence.name,
                    source = relation.source.name,
                    count = relation.count,
                )
            }
        return ArchitectureIndexSliceFragment(
            sliceId = slice.id,
            symbols = symbols,
            relations = relations,
            resources = resources,
            serviceProviders = serviceProviders,
        )
    }

    private fun persistedSourcePath(source: JvmSourceRef?): String? =
        persistedDisplayPath(source?.displayPath).takeIf(String::isNotBlank)

    private fun persistedDisplayPath(displayPath: String?): String {
        val normalized = displayPath
            ?.replace('\\', '/')
            ?.trim()
            ?: return ""
        val basePath = project.basePath?.replace('\\', '/') ?: return normalized
        return normalized.removePrefix("$basePath/")
    }

    private fun sourceVirtualFileUrl(source: JvmSourceRef?): String? =
        source?.virtualFileUrl

    private fun fieldTypeReferenceFragments(symbol: JvmFieldSymbol): List<FieldTypeReferenceSliceFragment> =
        symbol.typeReferences.map { reference ->
            FieldTypeReferenceSliceFragment(
                typeName = reference.typeName,
                role = reference.role.name,
            )
        }

    private fun pathMatchesSliceFiles(path: String, sliceFiles: Set<String>): Boolean =
        path in sliceFiles ||
            sliceFiles.any { slicePath ->
                slicePath.endsWith("/$path") || path.endsWith("/$slicePath")
            }

    private fun toProjectRelativePath(displayPath: String?): String {
        val normalized = displayPath
            ?.substringBefore("!/")
            ?.replace('\\', '/')
            ?.trim()
            ?: return ""
        val basePath = project.basePath?.replace('\\', '/') ?: return normalized
        return normalized.removePrefix("$basePath/")
    }

    private fun fileFingerprint(relativePath: String, contentSha256: String?): ProjectFileFingerprint {
        val path = diskPath(relativePath)
        val size = path?.takeIf(Files::isRegularFile)?.let(Files::size) ?: 0L
        val modifiedAtMillis = path?.takeIf(Files::isRegularFile)
            ?.let { file -> runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrNull() }
            ?: 0L
        return ProjectFileFingerprint(
            relativePath = relativePath,
            size = size,
            modifiedAtMillis = modifiedAtMillis,
            contentSha256 = contentSha256,
        )
    }

    private fun diskPath(relativePath: String): Path? =
        runCatching {
            val path = Path.of(relativePath)
            when {
                path.isAbsolute -> path
                project.basePath != null -> Path.of(project.basePath!!).resolve(relativePath)
                else -> path
            }
        }.getOrNull()

    private fun budgetHash(cacheKey: ArchitectureGraphCacheKey): String =
        stableSha256(
            listOf(
                cacheKey.includeTests,
                cacheKey.includeExternalLibraries,
                cacheKey.includeJdk,
                cacheKey.includeUserAttachedJars,
                cacheKey.maxProjectClasses,
                cacheKey.maxExternalClasses,
                cacheKey.maxMethods,
                cacheKey.maxRelations,
                cacheKey.attachedJars.joinToString("|"),
                cacheKey.purpose,
            ).joinToString(":"),
        )

    private fun sliceFileHash(files: List<ProjectFileFingerprint>): String =
        stableSha256(
            files.sortedBy(ProjectFileFingerprint::relativePath)
                .joinToString("|") { file ->
                    listOf(
                        file.relativePath,
                        file.size.toString(),
                        file.modifiedAtMillis.toString(),
                        file.contentSha256.orEmpty(),
                    ).joinToString(":")
                },
        )

    private fun stableSha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun buildUncachedIndex(
        budget: JvmResolutionBudget,
        symbolIndexHint: JvmSymbolIndex? = null,
        sourceComponents: com.charmnight.linkgraph.source.SourceContentResolverComponents,
    ): ArchitectureGraphIndex {
        val symbolStartedAt = System.nanoTime()
        val symbolIndex = symbolIndexHint ?: readActionIfNeeded {
            JvmSymbolIndexBuilder(
                project = project,
                attachedJarIndexProvider = { sourceComponents.attachedJarIndex },
                trace = { event ->
                    traceStage(event.stage, event.startedAtNanos, event.details)
                },
            ).build(budget)
        }
        traceStage("architectureIndex.symbolIndex", symbolStartedAt) {
            listOf(
                "source=${if (symbolIndexHint == null) "built" else "hint"}",
                "modules=${symbolIndex.modulesByName.size}",
                "packages=${symbolIndex.packagesByName.size}",
                "classes=${symbolIndex.classesByQualifiedName.size}",
                "methods=${symbolIndex.methodsBySignature.size}",
                "fields=${symbolIndex.fieldsByQualifiedName.size}",
                "resources=${symbolIndex.resourcesByPath.size}",
            )
        }
        val relationStartedAt = System.nanoTime()
        val relationIndex = readActionIfNeeded {
            relationResolverRegistry.resolveAll(
                JvmResolutionContext(
                    project = project,
                    symbolIndex = symbolIndex,
                    sourceResolver = sourceComponents.resolver,
                    budget = budget,
                ),
                traceStage = ::traceStage,
            )
        }
        traceStage("architectureIndex.relationIndex", relationStartedAt) {
            listOf(
                "relations=${relationIndex.relations.size}",
                "truncated=${relationIndex.truncated}",
            )
        }
        val graphStartedAt = System.nanoTime()
        return ArchitectureGraphIndex.from(symbolIndex, relationIndex, budget = budget)
            .also { index ->
                traceStage("architectureIndex.graphBuild", graphStartedAt) {
                    listOf(
                        "graphNodes=${index.graph.nodes.size}",
                        "graphEdges=${index.graph.edges.size}",
                        "truncated=${index.graph.truncated}",
                    )
                }
            }
    }

    private fun <T> readActionIfNeeded(action: () -> T): T {
        val application = ApplicationManager.getApplication()
        return if (application.isReadAccessAllowed) {
            action()
        } else {
            ReadAction.nonBlocking<T> { action() }
                .expireWith(project)
                .executeSynchronously()
        }
    }

    private fun classDiagramStructureIndexWithBoundedReadActions(
        budget: JvmResolutionBudget,
        forceRebuild: Boolean,
    ): ArchitectureGraphIndex {
        val settings = settingsSnapshot()
        val sourceComponentsStartedAt = System.nanoTime()
        val sourceComponents = sourceResolverFactory.create(budget, settings)
        traceStage("classDiagram.fastIndex.sourceComponents", sourceComponentsStartedAt) {
            listOf(
                "includeUserAttachedJars=${budget.includeUserAttachedJars}",
                "attachedJarFingerprints=${sourceComponents.attachedJarIndex.fingerprints.size}",
            )
        }
        val cacheKeyStartedAt = System.nanoTime()
        val cacheKey = readActionIfNeeded {
            ArchitectureGraphCacheKey.from(
                project = project,
                budget = budget,
                attachedJars = settings.attachedJars,
                attachedJarIndex = sourceComponents.attachedJarIndex,
                purpose = ArchitectureGraphCacheKey.PURPOSE_CLASS_DIAGRAM_STRUCTURE,
            )
        }
        traceStage("classDiagram.fastIndex.cacheKey", cacheKeyStartedAt) {
            listOf(
                "includeTests=${budget.includeTests}",
                "includeExternalLibraries=${budget.includeExternalLibraries}",
                "includeJdk=${budget.includeJdk}",
                "includeUserAttachedJars=${budget.includeUserAttachedJars}",
                "maxProjectClasses=${budget.maxProjectClasses}",
                "maxMethods=${budget.maxMethods}",
                "maxRelations=${budget.maxRelations}",
                "attachedJars=${cacheKey.attachedJars.size}",
            )
        }
        return project.architectureIndexService().getOrBuildAuxiliaryCachedIndex(
            cacheKey = cacheKey,
            forceRebuild = forceRebuild,
        ) {
            val symbolStartedAt = System.nanoTime()
            val symbolIndex = readActionIfNeeded {
                JvmSymbolIndexBuilder(
                    project = project,
                    attachedJarIndexProvider = { sourceComponents.attachedJarIndex },
                    trace = { event ->
                        traceStage(event.stage, event.startedAtNanos, event.details)
                    },
                ).build(budget)
            }
            traceStage("classDiagram.fastIndex.symbolIndex", symbolStartedAt) {
                listOf(
                    "modules=${symbolIndex.modulesByName.size}",
                    "packages=${symbolIndex.packagesByName.size}",
                    "classes=${symbolIndex.classesByQualifiedName.size}",
                    "methods=${symbolIndex.methodsBySignature.size}",
                    "fields=${symbolIndex.fieldsByQualifiedName.size}",
                    "resources=${symbolIndex.resourcesByPath.size}",
                )
            }
            val graphStartedAt = System.nanoTime()
            ClassDiagramFastIndex.fromSymbols(symbolIndex, budget = budget)
                .also { index ->
                    traceStage("classDiagram.fastIndex.graphBuild", graphStartedAt) {
                        listOf(
                            "relations=${index.relationIndex.relations.size}",
                            "graphNodes=${index.graph.nodes.size}",
                            "graphEdges=${index.graph.edges.size}",
                            "truncated=${index.graph.truncated}",
                        )
                    }
                }
        }
    }

    private fun architectureOverviewIndexWithBoundedReadActions(
        budget: JvmResolutionBudget,
        forceRebuild: Boolean,
        recordAsCurrent: Boolean,
    ): ArchitectureGraphIndex {
        val settings = settingsSnapshot()
        val sourceComponentsStartedAt = System.nanoTime()
        val sourceComponents = sourceResolverFactory.create(budget, settings)
        traceStage("architectureOverview.fastIndex.sourceComponents", sourceComponentsStartedAt) {
            listOf(
                "includeUserAttachedJars=${budget.includeUserAttachedJars}",
                "attachedJarFingerprints=${sourceComponents.attachedJarIndex.fingerprints.size}",
            )
        }
        val cacheKeyStartedAt = System.nanoTime()
        val cacheKey = readActionIfNeeded {
            ArchitectureGraphCacheKey.from(
                project = project,
                budget = budget,
                attachedJars = settings.attachedJars,
                attachedJarIndex = sourceComponents.attachedJarIndex,
                purpose = ArchitectureGraphCacheKey.PURPOSE_ARCHITECTURE_OVERVIEW,
            )
        }
        traceStage("architectureOverview.fastIndex.cacheKey", cacheKeyStartedAt) {
            listOf(
                "includeTests=${budget.includeTests}",
                "includeExternalLibraries=${budget.includeExternalLibraries}",
                "includeJdk=${budget.includeJdk}",
                "includeUserAttachedJars=${budget.includeUserAttachedJars}",
                "maxProjectClasses=${budget.maxProjectClasses}",
                "maxMethods=${budget.maxMethods}",
                "maxRelations=${budget.maxRelations}",
                "attachedJars=${cacheKey.attachedJars.size}",
            )
        }
        return project.architectureIndexService().getOrBuildAuxiliaryCachedIndex(
            cacheKey = cacheKey,
            forceRebuild = forceRebuild,
        ) {
            val symbolStartedAt = System.nanoTime()
            val symbolIndex = ArchitectureOverviewSymbolIndexBuilder(
                project = project,
                trace = ::traceStage,
            ).build(budget)
            traceStage("architectureOverview.fastIndex.symbolIndex", symbolStartedAt) {
                listOf(
                    "modules=${symbolIndex.modulesByName.size}",
                    "packages=${symbolIndex.packagesByName.size}",
                    "classes=${symbolIndex.classesByQualifiedName.size}",
                    "methods=${symbolIndex.methodsBySignature.size}",
                    "fields=${symbolIndex.fieldsByQualifiedName.size}",
                    "resources=${symbolIndex.resourcesByPath.size}",
                )
            }
            val graphStartedAt = System.nanoTime()
            ArchitectureOverviewFastIndex.fromSymbols(symbolIndex, budget = budget)
                .also { index ->
                    traceStage("architectureOverview.fastIndex.graphBuild", graphStartedAt) {
                        listOf(
                            "relations=${index.relationIndex.relations.size}",
                            "graphNodes=${index.graph.nodes.size}",
                            "graphEdges=${index.graph.edges.size}",
                            "truncated=${index.graph.truncated}",
                        )
                    }
                }
        }.also { index ->
            project.architectureIndexService().recordCurrentIndex(index, recordAsCurrent)
        }
    }

    private fun traceStage(
        stage: String,
        startedAtNanos: Long,
        details: () -> List<String>,
    ) {
        LinkGraphRenderTrace.stage(
            enabled = traceEnabled,
            log = { message -> logger.warn(message) },
            stage = stage,
            startedAtNanos = startedAtNanos,
            details = details,
        )
    }
}

fun Project.architectureIndexRuntime(): ArchitectureIndexRuntime =
    getService(ArchitectureIndexRuntime::class.java)
