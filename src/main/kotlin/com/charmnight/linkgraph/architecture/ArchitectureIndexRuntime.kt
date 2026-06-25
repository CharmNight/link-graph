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
import com.charmnight.linkgraph.architecture.memory.ProjectSliceFingerprint
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

/**
 * 项目级架构索引运行时：负责构建/缓存架构图索引，并对外暴露符号/关系/查询服务。
 * 支持基于切片的持久化缓存，可在多次会话间增量恢复索引。
 */
@Service(Service.Level.PROJECT)
class ArchitectureIndexRuntime(
    /** 当前 IntelliJ 项目实例。 */
    private val project: Project,
) {
    /** JVM 关系解析器注册表。 */
    private val relationResolverRegistry = JvmRelationResolverRegistry()
    private val logger = Logger.getInstance(ArchitectureIndexRuntime::class.java)
    /** 是否开启构建阶段追踪。 */
    private val traceEnabled: Boolean =
        LinkGraphDebugEnvironment.isEnabled("LINKGRAPH_DEBUG_TRACE")
    /** P2-1 真正的架构分解：索引构建管道委托给独立的 ArchitectureIndexBuildPipeline */
    private val buildPipeline = ArchitectureIndexBuildPipeline(
        project = project,
        relationResolverRegistry = relationResolverRegistry,
        logger = logger,
        traceEnabled = traceEnabled,
    )
    /** 源码内容解析器工厂，按预算与设置生成对应解析器。 */
    private val sourceResolverFactory = SourceContentResolverFactory(project, ::settingsSnapshot)
    /** 各缓存键的构建锁，避免重复构建。 */
    private val buildLocks = ConcurrentHashMap<ArchitectureGraphCacheKey, Any>()
    /** 持久化缓存存储，用于把切片片段落到磁盘。 */
    private val persistentCacheStore: PersistentArchitectureIndexCacheStore by lazy {
        PersistentArchitectureIndexCacheStore(Path.of(PathManager.getSystemPath()))
    }
    /** 基于存储实现的切片片段缓存。 */
    private val persistentFragmentCache: ArchitectureIndexPersistentFragmentCache by lazy {
        ArchitectureIndexPersistentFragmentCache(persistentCacheStore)
    }

    /** 切片输入文件候选信息，承载模块名、内容根、相对路径、大小、修改时间与虚拟文件。 */
    private data class ProjectSliceInputFileCandidate(
        val moduleName: String?,
        val contentRoot: String,
        val relativePath: String,
        val size: Long,
        val modifiedAtMillis: Long,
        val virtualFile: VirtualFile,
    )

    /** 返回当前索引服务中已记录的最新索引（可能为空）。 */
    fun currentIndex(): ArchitectureGraphIndex? =
        project.architectureIndexService().currentIndex()

    /**
     * 主入口：根据预算构建或获取缓存的架构图索引。
     * 优先复用缓存；支持通过持久化切片加速重建；强制重建时会绕过缓存。
     */
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
        val indexService = project.architectureIndexService()
        if (!forceRebuild) {
            indexService.getCachedIndex(cacheKey)?.let { index ->
                indexService.recordCurrentIndex(index, recordAsCurrent)
                return index
            }
        }
        val lock = buildLocks.computeIfAbsent(cacheKey) { Any() }
        return synchronized(lock) {
            var buildToken: Long? = null
            try {
                if (!forceRebuild) {
                    indexService.getCachedIndex(cacheKey)?.let { index ->
                        indexService.recordCurrentIndex(index, recordAsCurrent)
                        return@synchronized index
                    }
                    buildToken = indexService.beginCurrentIndexBuild(recordAsCurrent)
                    if (symbolIndexHint == null && supportsPersistentSliceCache(budget)) {
                        val manifest = currentProjectSliceManifest(sourceComponents, cacheKey)
                        if (manifest.slices.isNotEmpty()) {
                            val staleSliceIds = indexService.memorySnapshot().staleSliceIds.toSet()
                            val restored = persistentFragmentCache.restoreCompleteIndex(
                                manifest = manifest,
                                staleSliceIds = staleSliceIds,
                                cacheKeyForSlice = { slice -> fragmentCacheKey(cacheKey, slice) },
                                budget = budget,
                            )
                            restored.index?.let { index ->
                                indexService.recordMemorySnapshot(
                                    ArchitectureIndexMemorySnapshot(
                                        manifest = manifest,
                                        staleSliceIds = emptyList(),
                                        persistentCacheHits = restored.hits,
                                        persistentCacheMisses = restored.misses,
                                        lastUpdatedAtEpochMillis = System.currentTimeMillis(),
                                        indexSource = "PERSISTENT_FULL_HIT",
                                    ),
                                )
                                return@synchronized indexService.putCachedIndex(
                                    cacheKey = cacheKey,
                                    index = index,
                                    recordAsCurrent = recordAsCurrent,
                                    buildToken = buildToken,
                                )
                            }
                            rebuildFromPersistentFragments(
                                cachedFragments = restored.fragments,
                                rebuildSliceIds = restored.missingSliceIds,
                                manifest = manifest,
                                sourceComponents = sourceComponents,
                                cacheKey = cacheKey,
                                budget = budget,
                            )?.let { index ->
                                indexService.recordMemorySnapshot(
                                    ArchitectureIndexMemorySnapshot(
                                        manifest = manifest,
                                        staleSliceIds = emptyList(),
                                        persistentCacheHits = restored.hits,
                                        persistentCacheMisses = restored.misses,
                                        lastUpdatedAtEpochMillis = System.currentTimeMillis(),
                                        indexSource = "PERSISTENT_PARTIAL",
                                    ),
                                )
                                return@synchronized indexService.putCachedIndex(
                                    cacheKey = cacheKey,
                                    index = index,
                                    recordAsCurrent = recordAsCurrent,
                                    buildToken = buildToken,
                                )
                            }
                        }
                    }
                } else {
                    buildToken = indexService.beginCurrentIndexBuild(recordAsCurrent)
                }
                val manifest = if (supportsPersistentSliceCache(budget)) {
                    currentProjectSliceManifest(sourceComponents, cacheKey)
                } else {
                    null
                }
                val built = buildPipeline.build(
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
                indexService.putCachedIndex(
                    cacheKey = cacheKey,
                    index = built,
                    recordAsCurrent = recordAsCurrent,
                    buildToken = buildToken,
                )
            } catch (throwable: Throwable) {
                indexService.recordCurrentIndexBuildFailed(buildToken)
                throw throwable
            } finally {
                buildLocks.remove(cacheKey, lock)
            }
        }
    }

    /** 基于预算创建源码内容解析器（不带附加 jar 上下文）。 */
    fun sourceQuery(budget: JvmResolutionBudget = defaultBudget()): SourceContentResolver =
        sourceResolverFactory.create(budget).resolver

    /** 获取符号查询服务。 */
    fun symbolQuery(budget: JvmResolutionBudget = defaultBudget()): ArchitectureGraphQueryService =
        queryService(index(budget, recordAsCurrent = false))

    /** 获取关系查询服务。 */
    fun relationQuery(budget: JvmResolutionBudget = defaultBudget()): ArchitectureGraphQueryService =
        queryService(index(budget, recordAsCurrent = false))

    /** 获取架构图查询服务。 */
    fun architectureGraphQuery(budget: JvmResolutionBudget = defaultBudget()): ArchitectureGraphQueryService =
        queryService(index(budget, recordAsCurrent = false))

    /** 获取类图查询服务。 */
    fun classDiagramQuery(budget: JvmResolutionBudget = defaultBudget()): ArchitectureGraphQueryService =
        queryService(index(budget, recordAsCurrent = false))

    /** 获取类图结构专用索引（基于有界读操作执行）。 */
    fun classDiagramStructureIndex(
        budget: JvmResolutionBudget = defaultBudget(),
        forceRebuild: Boolean = false,
    ): ArchitectureGraphIndex =
        classDiagramStructureIndexWithBoundedReadActions(budget, forceRebuild)

    /** 获取架构概览索引（基于有界读操作执行）。 */
    fun architectureOverviewIndex(
        budget: JvmResolutionBudget = defaultBudget(),
        forceRebuild: Boolean = false,
        recordAsCurrent: Boolean = true,
    ): ArchitectureGraphIndex =
        architectureOverviewIndexWithBoundedReadActions(budget, forceRebuild, recordAsCurrent)

    /** 判断指定预算下是否已有完整缓存的索引。 */
    fun hasCachedFullIndex(budget: JvmResolutionBudget = defaultBudget()): Boolean {
        val settings = settingsSnapshot()
        val sourceComponents = sourceResolverFactory.create(budget, settings)
        val cacheKey = ArchitectureGraphCacheKey.from(project, budget, settings.attachedJars, sourceComponents.attachedJarIndex)
        return project.architectureIndexService().getCachedIndex(cacheKey) != null
    }

    /** 构造 Code Review 用的查询服务，集成 Git 基线映射。 */
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

    /** 失效所有缓存索引。 */
    fun invalidate() {
        project.architectureIndexService().invalidate()
    }

    /** 基于设置构造默认预算。 */
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

    /** 获取当前设置的非敏感快照（服务不可用时回退默认）。 */
    fun settingsSnapshot(): LinkGraphSettingsState =
        runCatching {
            ApplicationManager.getApplication().getService(LinkGraphSettingsService::class.java).nonSecretSnapshot()
        }.getOrDefault(LinkGraphSettingsState())

    private fun queryService(index: ArchitectureGraphIndex): ArchitectureGraphQueryService =
        ArchitectureGraphQueryService(
            index = index,
            memorySnapshot = project.architectureIndexService().memorySnapshot(),
        )

    /** 把构建出的索引与切片清单记录到内存快照，并按需写入持久化片段。 */
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
                val relationOwnerSliceIds = relationOwnerSliceIds(manifest, index)
                manifest.slices.forEach { slice ->
                    val fragmentKey = fragmentCacheKey(cacheKey, slice)
                    if (persistentCacheStore.read(fragmentKey) != null) {
                        hits += 1
                    } else {
                        misses += 1
                        persistentCacheStore.write(fragmentKey, fragmentForSlice(slice, index, relationOwnerSliceIds))
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
        val candidates = linkedMapOf<String, ProjectSliceInputFileCandidate>()
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
                    candidates.putIfAbsent(
                        relativePath,
                        ProjectSliceInputFileCandidate(
                            moduleName = projectFileIndex.getModuleForFile(file)?.name,
                            contentRoot = project.basePath ?: root.path,
                            relativePath = relativePath,
                            size = file.length,
                            modifiedAtMillis = file.timeStamp,
                            virtualFile = file,
                        ),
                    )
                    true
                }
            }
        }
        candidates.values.forEach { candidate ->
            val contentSha256 = diskPath(candidate.relativePath)
                ?.takeIf(Files::isRegularFile)
                ?.let(ProjectSliceFingerprint::contentSha256)
                ?: runCatching {
                    candidate.virtualFile.inputStream.use(ProjectSliceFingerprint::contentSha256)
                }.getOrNull()
            files[candidate.relativePath] = ProjectSliceInputFile(
                moduleName = candidate.moduleName,
                contentRoot = candidate.contentRoot,
                relativePath = candidate.relativePath,
                size = candidate.size,
                modifiedAtMillis = candidate.modifiedAtMillis,
                contentSha256 = contentSha256,
            )
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
        // 防御性过滤：即便上游 restoreCompleteIndex 已跳过 stale slice，这里再按 rebuildSliceIds
        // 过滤一次，确保任何"标记为待重建"的 slice 的旧 fragment 不会混入合并结果造成幽灵符号。
        val trustedCachedFragments = cachedFragments.filter { fragment -> fragment.sliceId !in rebuildSliceIds }
        if (trustedCachedFragments.isEmpty()) {
            return null
        }
        val cachedMerged = com.charmnight.linkgraph.architecture.memory.ArchitectureIndexFragmentMerger().merge(trustedCachedFragments)
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
        writeProjectSliceFragments(manifest, cacheKey, index, rebuildSliceIds)
        return index
    }

    private fun writeProjectSliceFragments(
        manifest: com.charmnight.linkgraph.architecture.memory.ProjectSliceManifest,
        cacheKey: ArchitectureGraphCacheKey,
        index: ArchitectureGraphIndex,
        rebuildSliceIds: Set<String> = emptySet(),
    ) {
        val relationOwnerSliceIds = relationOwnerSliceIds(manifest, index)
        manifest.slices
            .forEach { slice ->
                // rebuildSliceIds 为空表示全量构建，所有 slice 都要写；
                // 非空时只写重建范围内的 slice，未触及的 slice 保留磁盘缓存（避免无谓 IO）。
                if (rebuildSliceIds.isNotEmpty() && slice.id !in rebuildSliceIds) {
                    return@forEach
                }
                val fragment = fragmentForSlice(slice, index, relationOwnerSliceIds)
                val sliceCacheKey = fragmentCacheKey(cacheKey, slice)
                // 仅重建范围内的 slice 才需要考虑"重建后内容为空 → 删除缓存"。
                // 不在 rebuildSliceIds 中的 slice 保持原有 write 语义（即使是空 fragment 也写回，
                // 用作显式的"已知空"标记）。
                if (slice.id in rebuildSliceIds && fragment.isEmpty()) {
                    persistentCacheStore.delete(sliceCacheKey)
                } else {
                    persistentCacheStore.write(sliceCacheKey, fragment)
                }
            }
    }

    /** 判断片段内容是否完全为空（无符号/关系/资源/服务提供者）。 */
    private fun com.charmnight.linkgraph.architecture.memory.ArchitectureIndexSliceFragment.isEmpty(): Boolean =
        symbols.isEmpty() &&
            relations.isEmpty() &&
            resources.isEmpty() &&
            serviceProviders.isEmpty()

    private fun mergeSymbolIndexes(
        cached: JvmSymbolIndex,
        rebuilt: JvmSymbolIndex,
    ): JvmSymbolIndex =
        com.charmnight.linkgraph.architecture.mergeSymbolIndexes(cached, rebuilt)

    private fun isIndexAffectingFile(file: VirtualFile, relativePath: String): Boolean =
        com.charmnight.linkgraph.architecture.isIndexAffectingFile(file, relativePath)

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
            val metadata = fileFingerprint(relativePath)
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
            fileHash = ProjectSliceFingerprint.fileHash(slice.files),
            attachedJarFingerprint = slice.files
                .firstOrNull { slice.kind == ProjectSliceKind.ATTACHED_JAR.name }
                ?.contentSha256,
        )

    private fun fragmentForSlice(
        slice: ProjectSlice,
        index: ArchitectureGraphIndex,
    ): ArchitectureIndexSliceFragment =
        fragmentForSlice(slice, index, relationOwnerSliceIds = emptyMap())

    private fun fragmentForSlice(
        slice: ProjectSlice,
        index: ArchitectureGraphIndex,
        relationOwnerSliceIds: Map<String, Set<String>>,
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
            .filter { relation ->
                // 跨 slice 关系：from/to 任一端在本 slice 内即纳入（避免一端 slice 失效后关系消失）
                relationOwnerSliceIds[relation.id]?.let { ownerSliceIds ->
                    return@filter slice.id in ownerSliceIds
                }
                relation.fromSymbolId in localSymbolIds
            }
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

    private fun sliceSymbolIds(
        slice: ProjectSlice,
        index: ArchitectureGraphIndex,
    ): Set<String> =
        com.charmnight.linkgraph.architecture.sliceSymbolIds(slice, index, project.basePath)

    private fun relationOwnerSliceIds(
        manifest: com.charmnight.linkgraph.architecture.memory.ProjectSliceManifest,
        index: ArchitectureGraphIndex,
    ): Map<String, Set<String>> =
        com.charmnight.linkgraph.architecture.relationOwnerSliceIds(manifest, index, project.basePath)

    private fun persistedSourcePath(source: JvmSourceRef?): String? =
        com.charmnight.linkgraph.architecture.persistedSourcePath(source, project.basePath)

    private fun persistedDisplayPath(displayPath: String?): String =
        com.charmnight.linkgraph.architecture.persistedDisplayPath(displayPath, project.basePath)

    private fun sourceVirtualFileUrl(source: JvmSourceRef?): String? =
        source?.virtualFileUrl

    private fun fieldTypeReferenceFragments(symbol: JvmFieldSymbol): List<FieldTypeReferenceSliceFragment> =
        com.charmnight.linkgraph.architecture.fieldTypeReferenceFragments(symbol)

    private fun pathMatchesSliceFiles(path: String, sliceFiles: Set<String>): Boolean =
        com.charmnight.linkgraph.architecture.pathMatchesSliceFiles(path, sliceFiles)

    private fun toProjectRelativePath(displayPath: String?): String =
        com.charmnight.linkgraph.architecture.toProjectRelativePath(displayPath, project.basePath)

    private fun fileFingerprint(
        relativePath: String,
    ): ProjectFileFingerprint {
        val path = diskPath(relativePath)
        val diskFile = path?.takeIf(Files::isRegularFile)
        val size = diskFile?.let(Files::size) ?: 0L
        val modifiedAtMillis = diskFile?.let { file -> runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrNull() }
            ?: 0L
        return ProjectFileFingerprint(
            relativePath = relativePath,
            size = size,
            modifiedAtMillis = modifiedAtMillis,
            contentSha256 = diskFile?.let(ProjectSliceFingerprint::contentSha256),
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

    private fun stableSha256(value: String): String =
        com.charmnight.linkgraph.architecture.stableSha256(value)


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
        return project.architectureIndexService().getOrBuildCachedIndex(
            cacheKey = cacheKey,
            recordAsCurrent = recordAsCurrent,
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

/** 获取当前项目的架构索引运行时服务。 */
fun Project.architectureIndexRuntime(): ArchitectureIndexRuntime =
    getService(ArchitectureIndexRuntime::class.java)
