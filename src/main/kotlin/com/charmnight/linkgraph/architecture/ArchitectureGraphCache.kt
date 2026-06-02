package com.charmnight.linkgraph.architecture

import com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.source.AttachedJarEntry
import com.charmnight.linkgraph.source.AttachedJarIndex
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.psi.util.PsiModificationTracker
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class ArchitectureGraphCacheKey(
    val projectLocationHash: Int,
    val projectRootModificationCount: Long,
    val psiModificationCount: Long,
    val includeTests: Boolean,
    val includeExternalLibraries: Boolean,
    val includeJdk: Boolean,
    val includeUserAttachedJars: Boolean,
    val maxProjectClasses: Int,
    val maxExternalClasses: Int,
    val maxMethods: Int,
    val maxRelations: Int,
    val attachedJars: List<String>,
    val purpose: String = PURPOSE_FULL,
) {
    companion object {
        const val PURPOSE_FULL = "FULL"
        const val PURPOSE_ARCHITECTURE_OVERVIEW = "ARCHITECTURE_OVERVIEW"
        const val PURPOSE_CLASS_DIAGRAM_STRUCTURE = "CLASS_DIAGRAM_STRUCTURE"

        fun from(
            project: Project,
            budget: JvmResolutionBudget,
            attachedJars: List<AttachedJarEntry>,
            purpose: String = PURPOSE_FULL,
        ): ArchitectureGraphCacheKey {
            val attachedJarIndex = AttachedJarIndex.build(attachedJars)
            return from(project, budget, attachedJars, attachedJarIndex, purpose)
        }

        fun from(
            project: Project,
            budget: JvmResolutionBudget,
            attachedJars: List<AttachedJarEntry>,
            attachedJarIndex: AttachedJarIndex,
            purpose: String = PURPOSE_FULL,
        ): ArchitectureGraphCacheKey =
            ArchitectureGraphCacheKey(
                projectLocationHash = (project.basePath ?: project.name).hashCode(),
                projectRootModificationCount = ProjectRootModificationTracker.getInstance(project).modificationCount,
                psiModificationCount = PsiModificationTracker.getInstance(project).modificationCount,
                includeTests = budget.includeTests,
                includeExternalLibraries = budget.includeExternalLibraries,
                includeJdk = budget.includeJdk,
                includeUserAttachedJars = budget.includeUserAttachedJars,
                maxProjectClasses = budget.maxProjectClasses,
                maxExternalClasses = budget.maxExternalClasses,
                maxMethods = budget.maxMethods,
                maxRelations = budget.maxRelations,
                attachedJars = attachedJarIndex.fingerprints
                    .map { fingerprint ->
                        listOf(
                            fingerprint.path,
                            fingerprint.sourceJarPath.orEmpty(),
                            fingerprint.classJarLastModifiedMillis.toString(),
                            fingerprint.classJarSize.toString(),
                            fingerprint.classJarSha256,
                            fingerprint.sourceJarLastModifiedMillis?.toString().orEmpty(),
                            fingerprint.sourceJarSize?.toString().orEmpty(),
                            fingerprint.sourceJarSha256.orEmpty(),
                        ).joinToString("|")
                    }
                    .ifEmpty {
                        attachedJars
                            .filter(AttachedJarEntry::enabled)
                            .map { entry -> "${entry.path}|${entry.sourceJarPath.orEmpty()}" }
                    }
                    .sorted(),
                purpose = purpose,
            )
    }
}

data class CachedArchitectureGraph(
    val architectureGraph: ArchitectureGraph,
    val graphDocument: GraphDocument?,
    val index: ArchitectureGraphIndex,
    val createdAtMillis: Long,
    val sourceModificationStamp: Long,
)

class ArchitectureGraphCache(
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var cached = ConcurrentHashMap<ArchitectureGraphCacheKey, CachedArchitectureGraph>()
    private val buildLocks = ConcurrentHashMap<ArchitectureGraphCacheKey, Any>()
    private val generation = AtomicLong()

    fun get(key: ArchitectureGraphCacheKey): CachedArchitectureGraph? =
        cached[key]

    fun put(
        key: ArchitectureGraphCacheKey,
        index: ArchitectureGraphIndex,
        graphDocument: GraphDocument? = null,
        sourceModificationStamp: Long = 0L,
    ): CachedArchitectureGraph {
        val value = CachedArchitectureGraph(
            architectureGraph = index.graph,
            graphDocument = graphDocument,
            index = index,
            createdAtMillis = clockMillis(),
            sourceModificationStamp = sourceModificationStamp,
        )
        cached[key] = value
        return value
    }

    fun getOrBuild(
        key: ArchitectureGraphCacheKey,
        graphDocument: GraphDocument? = null,
        sourceModificationStamp: Long = 0L,
        forceRebuild: Boolean = false,
        builder: () -> ArchitectureGraphIndex,
    ): CachedArchitectureGraph {
        if (forceRebuild) {
            val index = builder()
            return put(
                key = key,
                index = index,
                graphDocument = graphDocument,
                sourceModificationStamp = sourceModificationStamp,
            )
        }
        val lock = buildLocks.computeIfAbsent(key) { Any() }
        return try {
            synchronized(lock) {
                if (!forceRebuild) {
                    cached[key]?.let { cachedGraph ->
                        return@synchronized cachedGraph
                    }
                }
                val generationAtStart = generation.get()
                val index = builder()
                val cachedGraph = CachedArchitectureGraph(
                    architectureGraph = index.graph,
                    graphDocument = graphDocument,
                    index = index,
                    createdAtMillis = clockMillis(),
                    sourceModificationStamp = sourceModificationStamp,
                )
                if (generation.get() == generationAtStart) {
                    cached[key] = cachedGraph
                }
                cachedGraph
            }
        } finally {
            buildLocks.remove(key, lock)
        }
    }

    fun invalidate(key: ArchitectureGraphCacheKey? = null) {
        if (key == null) {
            cached = ConcurrentHashMap()
            generation.incrementAndGet()
        } else {
            cached.remove(key)
        }
    }
}
