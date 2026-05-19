package com.charmnight.linkgraph.architecture

import com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.settings.LinkGraphSettingsChangedNotifier
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.workspace.WorkspaceModelChangeListener
import com.intellij.platform.backend.workspace.WorkspaceModelTopics
import com.intellij.platform.workspace.storage.VersionedStorageChange
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
            WorkspaceModelTopics.CHANGED,
            object : WorkspaceModelChangeListener {
                override fun changed(event: VersionedStorageChange) {
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
        builder: () -> ArchitectureGraphIndex,
    ): ArchitectureGraphIndex {
        cache.get(cacheKey)?.index?.let { index ->
            lastIndex = index
            return index
        }
        return builder().also { index ->
            cache.put(cacheKey, index)
            lastIndex = index
        }
    }

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
