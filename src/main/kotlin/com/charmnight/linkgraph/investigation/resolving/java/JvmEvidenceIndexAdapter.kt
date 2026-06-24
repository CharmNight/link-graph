package com.charmnight.linkgraph.investigation.resolving.java

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.architecture.architectureIndexRuntime
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project

/**
 * JVM 证据解析所用的架构索引适配器。
 *
 * 把"如何在同步读锁内/外取得架构索引"的细节封装起来，
 * 让上层证据解析只关心索引本身。
 *
 * 关键约束：不能在同步读锁内构造索引（会触发 assert），
 * 因此本类会根据当前是否持有读锁选择不同路径。
 */
class JvmEvidenceIndexAdapter(
    /** 取得（必要时构造）当前项目架构索引的回调。 */
    private val indexProvider: (Project) -> ArchitectureGraphIndex = { project ->
        project.architectureIndexRuntime().index()
    },
    /** 取得当前已缓存的架构索引（不触发构造）；不存在返回 null。 */
    private val currentIndexProvider: (Project) -> ArchitectureGraphIndex? = { project ->
        project.architectureIndexRuntime().currentIndex()
    },
) {
    /**
     * 取得架构索引。
     *
     * - 当前已持有读锁：直接用 currentIndex，若不存在则报错（不能在读锁内同步构造）；
     * - 未持有读锁：惰性构造索引。
     *
     * @param project 当前项目
     * @return 架构索引
     */
    fun acquireIndex(project: Project): ArchitectureGraphIndex {
        val application = ApplicationManager.getApplication()
        if (application.isReadAccessAllowed) {
            return currentIndexProvider(project)
                ?: error("Cannot build ArchitectureGraphIndex inside a synchronous read action without a cached current index.")
        }
        return indexProvider(project)
    }

    /**
     * 在读锁外构造索引。
     *
     * 即使当前持有读锁，也会切到 pooled thread 上异步构造再阻塞等待，
     * 避免在读锁内同步构造触发 assert。
     */
    fun buildIndexOutsideReadAction(project: Project): ArchitectureGraphIndex {
        val application = ApplicationManager.getApplication()
        // 未持有读锁：直接构造
        if (!application.isReadAccessAllowed) {
            return indexProvider(project)
        }
        // 持有读锁：切到 pooled thread 上构造，本线程阻塞等待结果
        return application.executeOnPooledThread<ArchitectureGraphIndex> {
            indexProvider(project)
        }.get()
    }

    /** 无条件构造索引。调用方必须保证当前不在读锁内。 */
    fun buildIndex(project: Project): ArchitectureGraphIndex {
        return indexProvider(project)
    }
}
