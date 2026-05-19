package com.charmnight.linkgraph.architecture

import com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.source.AttachedJarEntry
import com.charmnight.linkgraph.source.AttachedJarIndex
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.psi.util.PsiModificationTracker
import java.util.concurrent.ConcurrentHashMap

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
) {
    companion object {
        fun from(
            project: Project,
            budget: JvmResolutionBudget,
            attachedJars: List<AttachedJarEntry>,
        ): ArchitectureGraphCacheKey {
            val attachedJarIndex = AttachedJarIndex.build(attachedJars)
            return from(project, budget, attachedJars, attachedJarIndex)
        }

        fun from(
            project: Project,
            budget: JvmResolutionBudget,
            attachedJars: List<AttachedJarEntry>,
            attachedJarIndex: AttachedJarIndex,
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
    private val cached = ConcurrentHashMap<ArchitectureGraphCacheKey, CachedArchitectureGraph>()

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

    fun invalidate(key: ArchitectureGraphCacheKey? = null) {
        if (key == null) {
            cached.clear()
        } else {
            cached.remove(key)
        }
    }
}
