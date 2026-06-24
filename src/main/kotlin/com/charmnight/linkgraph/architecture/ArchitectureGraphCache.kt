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

/**
 * 架构图缓存键：综合项目位置哈希、修改计数、预算参数与附加 jar 指纹构成缓存命中条件。
 */
data class ArchitectureGraphCacheKey(
    /** 项目位置哈希，区分不同项目。 */
    val projectLocationHash: Int,
    /** 项目根修改计数，用于感知根结构变化。 */
    val projectRootModificationCount: Long,
    /** PSI 修改计数，用于感知代码元素变更。 */
    val psiModificationCount: Long,
    /** 是否包含测试源码。 */
    val includeTests: Boolean,
    /** 是否包含外部依赖库。 */
    val includeExternalLibraries: Boolean,
    /** 是否包含 JDK。 */
    val includeJdk: Boolean,
    /** 是否包含用户附加的 jar。 */
    val includeUserAttachedJars: Boolean,
    /** 项目类数量上限。 */
    val maxProjectClasses: Int,
    /** 外部类数量上限。 */
    val maxExternalClasses: Int,
    /** 方法数量上限。 */
    val maxMethods: Int,
    /** 关系数量上限。 */
    val maxRelations: Int,
    /** 附加 jar 的指纹列表。 */
    val attachedJars: List<String>,
    /** 缓存用途标签。 */
    val purpose: String = PURPOSE_FULL,
) {
    companion object {
        /** 完整架构图用途。 */
        const val PURPOSE_FULL = "FULL"
        /** 架构概览用途。 */
        const val PURPOSE_ARCHITECTURE_OVERVIEW = "ARCHITECTURE_OVERVIEW"
        /** 类图结构用途。 */
        const val PURPOSE_CLASS_DIAGRAM_STRUCTURE = "CLASS_DIAGRAM_STRUCTURE"

        /** 基于项目、预算与附加 jar 列表构造缓存键。 */
        fun from(
            project: Project,
            budget: JvmResolutionBudget,
            attachedJars: List<AttachedJarEntry>,
            purpose: String = PURPOSE_FULL,
        ): ArchitectureGraphCacheKey {
            val attachedJarIndex = AttachedJarIndex.build(attachedJars)
            return from(project, budget, attachedJars, attachedJarIndex, purpose)
        }

        /** 基于预构建的附加 jar 索引构造缓存键，避免重复计算指纹。 */
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

/** 已缓存的架构图条目，包含图本身、可选的 GraphDocument、索引与时间戳。 */
data class CachedArchitectureGraph(
    /** 架构图结构。 */
    val architectureGraph: ArchitectureGraph,
    /** 同步生成的图文档（可空）。 */
    val graphDocument: GraphDocument?,
    /** 架构图索引，用于快速查询。 */
    val index: ArchitectureGraphIndex,
    /** 创建时间毫秒。 */
    val createdAtMillis: Long,
    /** 源码修改时间戳。 */
    val sourceModificationStamp: Long,
)

/**
 * 架构图缓存，按缓存键存放构建结果，支持并发构建加锁与整体失效。
 */
class ArchitectureGraphCache(
    /** 用于获取当前时间毫秒的时钟函数，便于测试替换。 */
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    /** 实际存储的缓存映射（按 key 替换实现整体失效）。 */
    @Volatile
    private var cached = ConcurrentHashMap<ArchitectureGraphCacheKey, CachedArchitectureGraph>()
    /** 每个 key 的构建锁，避免重复构建。 */
    private val buildLocks = ConcurrentHashMap<ArchitectureGraphCacheKey, Any>()
    /** 失效代际，用于在并发构建过程中检测整体失效。 */
    private val generation = AtomicLong()

    /** 读取缓存条目，不存在时返回 null。 */
    fun get(key: ArchitectureGraphCacheKey): CachedArchitectureGraph? =
        cached[key]

    /** 写入缓存条目并返回。 */
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

    /** 获取或构建并缓存架构图；支持强制重建以及并发去重构建。 */
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

    /** 失效缓存：传入 null 失效全部，否则仅失效指定键。 */
    fun invalidate(key: ArchitectureGraphCacheKey? = null) {
        if (key == null) {
            cached = ConcurrentHashMap()
            generation.incrementAndGet()
        } else {
            cached.remove(key)
        }
    }
}
