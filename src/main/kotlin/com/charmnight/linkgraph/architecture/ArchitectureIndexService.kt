package com.charmnight.linkgraph.architecture

import com.charmnight.linkgraph.architecture.memory.ArchitectureIndexInvalidationPlanner
import com.charmnight.linkgraph.architecture.memory.ArchitectureIndexMemorySnapshot
import com.charmnight.linkgraph.architecture.memory.ProjectFileChange
import com.charmnight.linkgraph.architecture.memory.ProjectSlice
import com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.settings.LinkGraphSettingsChangedNotifier
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

/**
 * 项目级架构索引服务，维护缓存、新鲜度状态与内存快照，并监听 VFS/模块根/设置变更以触发失效。
 */
@Service(Service.Level.PROJECT)
class ArchitectureIndexService(
    /** 当前项目实例。 */
    private val project: Project,
) {
    private val cache = ArchitectureGraphCache()
    private val freshnessTracker = ArchitectureIndexFreshnessTracker()
    private val memoryLock = Any()

    @Volatile
    private var memorySnapshot: ArchitectureIndexMemorySnapshot = ArchitectureIndexMemorySnapshot()

    @Volatile
    private var lastIndex: ArchitectureGraphIndex? = null

    init {
        val connection = project.messageBus.connect(project)
        connection.subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val affectingPaths = events.mapNotNull(::indexAffectingPath)
                    if (affectingPaths.isNotEmpty()) {
                        invalidate("VFS_CHANGE", affectingPaths)
                    }
                }
            },
        )
        connection.subscribe(
            ModuleRootListener.TOPIC,
            object : ModuleRootListener {
                override fun rootsChanged(event: ModuleRootEvent) {
                    invalidate("MODULE_ROOTS_CHANGED")
                }
            },
        )
        ApplicationManager.getApplication().messageBus.connect(project).subscribe(
            LinkGraphSettingsChangedNotifier.TOPIC,
            object : LinkGraphSettingsChangedNotifier {
                override fun onArchitectureIndexSettingsChanged(
                    before: LinkGraphSettingsState,
                    after: LinkGraphSettingsState,
                ) {
                    invalidate("SETTINGS_CHANGED")
                }
            },
        )
    }

    /** 当前已记录的最新索引（可能为空）。 */
    fun currentIndex(): ArchitectureGraphIndex? = lastIndex

    /** 返回当前索引新鲜度快照。 */
    fun freshness(): ArchitectureIndexFreshnessSnapshot = freshnessTracker.snapshot()

    /** 返回索引内存快照（含切片清单与陈旧片段）。 */
    fun memorySnapshot(): ArchitectureIndexMemorySnapshot = memorySnapshot

    /** 通过运行时按预算构建或获取索引。 */
    fun getOrBuildIndex(budget: JvmResolutionBudget = defaultBudget()): ArchitectureGraphIndex {
        return project.architectureIndexRuntime().index(budget)
    }

    /** 获取或构建并写入缓存的索引，支持记录为当前索引并附带构建代际。 */
    fun getOrBuildCachedIndex(
        cacheKey: ArchitectureGraphCacheKey,
        recordAsCurrent: Boolean = true,
        forceRebuild: Boolean = false,
        builder: () -> ArchitectureGraphIndex,
    ): ArchitectureGraphIndex {
        val buildTokenRef = arrayOfNulls<Long>(1)
        if (!forceRebuild) {
            cache.get(cacheKey)?.index?.let { index ->
                if (recordAsCurrent) {
                    lastIndex = index
                    freshnessTracker.markIndexed()
                }
                return index
            }
        }
        val cached = cache.getOrBuild(
            key = cacheKey,
            forceRebuild = forceRebuild,
            builder = {
                val buildToken = beginCurrentIndexBuild(recordAsCurrent)
                buildTokenRef[0] = buildToken
                try {
                    builder()
                } catch (throwable: Throwable) {
                    recordCurrentIndexBuildFailed(buildToken)
                    throw throwable
                }
            },
        )
        return cached.index.also { index ->
            if (recordAsCurrent) {
                lastIndex = index
                freshnessTracker.markIndexed(buildTokenRef[0])
            }
        }
    }

    /** 从缓存读取索引，未命中返回 null。 */
    fun getCachedIndex(cacheKey: ArchitectureGraphCacheKey): ArchitectureGraphIndex? =
        cache.get(cacheKey)?.index

    /** 记录一个已构建的索引为当前索引（可选）。 */
    fun recordCurrentIndex(
        index: ArchitectureGraphIndex,
        recordAsCurrent: Boolean = true,
        buildToken: Long? = null,
    ): ArchitectureGraphIndex =
        index.also {
            if (recordAsCurrent) {
                lastIndex = it
                freshnessTracker.markIndexed(buildToken)
            }
        }

    /** 写入缓存并可选用作当前索引。 */
    fun putCachedIndex(
        cacheKey: ArchitectureGraphCacheKey,
        index: ArchitectureGraphIndex,
        recordAsCurrent: Boolean = true,
        buildToken: Long? = null,
    ): ArchitectureGraphIndex =
        cache.put(cacheKey, index).index.also {
            if (recordAsCurrent) {
                lastIndex = it
                freshnessTracker.markIndexed(buildToken)
            }
        }

    /** 获取或构建辅助用途的缓存索引（不会记录为当前索引）。 */
    fun getOrBuildAuxiliaryCachedIndex(
        cacheKey: ArchitectureGraphCacheKey,
        forceRebuild: Boolean = false,
        builder: () -> ArchitectureGraphIndex,
    ): ArchitectureGraphIndex =
        cache.getOrBuild(
            key = cacheKey,
            forceRebuild = forceRebuild,
            builder = builder,
        ).index

    /** 清空热缓存与当前索引，仅测试使用。 */
    internal fun clearHotCacheForTesting() {
        cache.invalidate()
        lastIndex = null
    }

    /** 失效全部缓存（默认原因）。 */
    fun invalidate() {
        invalidate("EXPLICIT_INVALIDATE")
    }

    /** 失效全部缓存，并按路径标记脏状态。 */
    fun invalidate(dirtyReason: String, paths: List<String> = emptyList()) {
        freshnessTracker.markDirty(dirtyReason, paths)
        markMemoryStale(paths)
        cache.invalidate()
        lastIndex = null
    }

    /** 标记开始构建当前索引，返回构建代际令牌。 */
    internal fun beginCurrentIndexBuild(recordAsCurrent: Boolean = true): Long? =
        if (recordAsCurrent) {
            freshnessTracker.markBuilding()
        } else {
            null
        }

    /** 记录当前索引构建失败。 */
    internal fun recordCurrentIndexBuildFailed(buildToken: Long?) {
        buildToken?.let(freshnessTracker::markBuildFailed)
    }

    /** 写入索引内存快照（线程安全）。 */
    fun recordMemorySnapshot(snapshot: ArchitectureIndexMemorySnapshot) {
        synchronized(memoryLock) {
            memorySnapshot = snapshot
        }
    }

    /** 返回运行时默认预算。 */
    fun defaultBudget(): JvmResolutionBudget {
        return project.architectureIndexRuntime().defaultBudget()
    }

    /** 判断 VFS 事件涉及的文件路径是否会影响索引（扩展名/META-INF services）。 */
    private fun indexAffectingPath(event: VFileEvent): String? {
        val path = (event.file?.path ?: event.path).replace('\\', '/')
        if (path.isBlank()) {
            return null
        }
        val basePath = project.basePath?.replace('\\', '/')
        val underProject = basePath == null || path == basePath || path.startsWith("$basePath/")
        val extension = path.substringAfterLast('/', path).substringAfterLast('.', missingDelimiterValue = "").lowercase()
        val sourceLike = extension in setOf(
            "java",
            "kt",
            "kts",
            "xml",
            "yml",
            "yaml",
            "properties",
            "sql",
            "md",
            "jar",
            "class",
        )
        return path.takeIf { underProject && (sourceLike || path.contains("/META-INF/services/")) }
    }

    /** 根据变更路径规划受影响的切片，更新内存快照中的陈旧切片集合。 */
    private fun markMemoryStale(paths: List<String>) {
        val current = memorySnapshot
        val manifest = current.manifest ?: return
        val relativeChanges = paths
            .map(::toProjectRelativePath)
            .filter(String::isNotBlank)
            .map(::ProjectFileChange)
        if (relativeChanges.isEmpty()) {
            synchronized(memoryLock) {
                memorySnapshot = current.copy(staleSliceIds = manifest.slices.map(ProjectSlice::id).sorted())
            }
            return
        }
        val plan = ArchitectureIndexInvalidationPlanner().plan(manifest, relativeChanges)
        synchronized(memoryLock) {
            memorySnapshot = current.copy(staleSliceIds = plan.staleSliceIds.sorted())
        }
    }

    /** 把绝对路径转换为项目相对路径。 */
    private fun toProjectRelativePath(path: String): String {
        val normalized = path.replace('\\', '/')
        val basePath = project.basePath?.replace('\\', '/') ?: return normalized
        return normalized.removePrefix("$basePath/")
    }
}

/** 获取当前项目的架构索引服务。 */
fun Project.architectureIndexService(): ArchitectureIndexService =
    getService(ArchitectureIndexService::class.java)
