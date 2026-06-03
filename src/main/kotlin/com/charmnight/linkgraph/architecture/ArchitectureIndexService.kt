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

@Service(Service.Level.PROJECT)
class ArchitectureIndexService(
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

    fun currentIndex(): ArchitectureGraphIndex? = lastIndex

    fun freshness(): ArchitectureIndexFreshnessSnapshot = freshnessTracker.snapshot()

    fun memorySnapshot(): ArchitectureIndexMemorySnapshot = memorySnapshot

    fun getOrBuildIndex(budget: JvmResolutionBudget = defaultBudget()): ArchitectureGraphIndex {
        return project.architectureIndexRuntime().index(budget)
    }

    fun getOrBuildCachedIndex(
        cacheKey: ArchitectureGraphCacheKey,
        recordAsCurrent: Boolean = true,
        forceRebuild: Boolean = false,
        builder: () -> ArchitectureGraphIndex,
    ): ArchitectureGraphIndex {
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
            builder = builder,
        )
        return cached.index.also { index ->
            if (recordAsCurrent) {
                lastIndex = index
                freshnessTracker.markIndexed()
            }
        }
    }

    fun getCachedIndex(cacheKey: ArchitectureGraphCacheKey): ArchitectureGraphIndex? =
        cache.get(cacheKey)?.index

    fun recordCurrentIndex(index: ArchitectureGraphIndex, recordAsCurrent: Boolean = true): ArchitectureGraphIndex =
        index.also {
            if (recordAsCurrent) {
                lastIndex = it
                freshnessTracker.markIndexed()
            }
        }

    fun putCachedIndex(
        cacheKey: ArchitectureGraphCacheKey,
        index: ArchitectureGraphIndex,
        recordAsCurrent: Boolean = true,
    ): ArchitectureGraphIndex =
        cache.put(cacheKey, index).index.also {
            if (recordAsCurrent) {
                lastIndex = it
                freshnessTracker.markIndexed()
            }
        }

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

    internal fun clearHotCacheForTesting() {
        cache.invalidate()
        lastIndex = null
    }

    fun invalidate() {
        invalidate("EXPLICIT_INVALIDATE")
    }

    fun invalidate(dirtyReason: String, paths: List<String> = emptyList()) {
        freshnessTracker.markDirty(dirtyReason, paths)
        markMemoryStale(paths)
        cache.invalidate()
        lastIndex = null
    }

    fun recordMemorySnapshot(snapshot: ArchitectureIndexMemorySnapshot) {
        synchronized(memoryLock) {
            memorySnapshot = snapshot
        }
    }

    fun defaultBudget(): JvmResolutionBudget {
        return project.architectureIndexRuntime().defaultBudget()
    }

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

    private fun toProjectRelativePath(path: String): String {
        val normalized = path.replace('\\', '/')
        val basePath = project.basePath?.replace('\\', '/') ?: return normalized
        return normalized.removePrefix("$basePath/")
    }
}

fun Project.architectureIndexService(): ArchitectureIndexService =
    getService(ArchitectureIndexService::class.java)
