package com.charmnight.linkgraph.architecture

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

    @Volatile
    private var lastIndex: ArchitectureGraphIndex? = null

    init {
        val connection = project.messageBus.connect(project)
        connection.subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any(::isIndexAffectingEvent)) {
                        invalidate()
                    }
                }
            },
        )
        connection.subscribe(
            ModuleRootListener.TOPIC,
            object : ModuleRootListener {
                override fun rootsChanged(event: ModuleRootEvent) {
                    invalidate()
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
                    invalidate()
                }
            },
        )
    }

    fun currentIndex(): ArchitectureGraphIndex? = lastIndex

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
            }
        }
    }

    fun getCachedIndex(cacheKey: ArchitectureGraphCacheKey): ArchitectureGraphIndex? =
        cache.get(cacheKey)?.index

    fun recordCurrentIndex(index: ArchitectureGraphIndex, recordAsCurrent: Boolean = true): ArchitectureGraphIndex =
        index.also {
            if (recordAsCurrent) {
                lastIndex = it
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

    fun invalidate() {
        cache.invalidate()
        lastIndex = null
    }

    fun defaultBudget(): JvmResolutionBudget {
        return project.architectureIndexRuntime().defaultBudget()
    }

    private fun isIndexAffectingEvent(event: VFileEvent): Boolean {
        val path = (event.file?.path ?: event.path).replace('\\', '/')
        if (path.isBlank()) {
            return false
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
        return underProject && (sourceLike || path.contains("/META-INF/services/"))
    }
}

fun Project.architectureIndexService(): ArchitectureIndexService =
    getService(ArchitectureIndexService::class.java)
