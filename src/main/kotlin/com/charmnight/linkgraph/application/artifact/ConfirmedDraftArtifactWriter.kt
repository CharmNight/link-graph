package com.charmnight.linkgraph.application.artifact

import com.charmnight.linkgraph.agent.artifact.ArtifactStore
import com.charmnight.linkgraph.agent.artifact.ArtifactStorePruner
import com.charmnight.linkgraph.agent.artifact.ConfirmedIntentArtifact
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry

/**
 * 把已确认草稿条目写入产物仓库（或从中删除）。
 *
 * 当用户确认/撤销某个草稿时，对应产物也需要落库或移除，
 * 保证 runtime 在生成计划/代码时能通过 artifactId 找到最新意图。
 * 用 provider 模式取得 ArtifactStore，避免在构造时就绑定具体实例。
 */
class ConfirmedDraftArtifactWriter(
    /** 返回当前可用的产物仓库；惰性取得以避免早期初始化顺序问题。 */
    private val artifactStoreProvider: () -> ArtifactStore,
) {
    /** 修剪器单例引用，便于复用统一的删除逻辑。 */
    private val artifactStorePruner = ArtifactStorePruner

    /**
     * 把一条已确认草稿写入仓库。
     * 入口为 null 时直接跳过，便于上游不做 null 判断。
     */
    fun recordConfirmedEntry(entry: DraftWorkbenchEntry?) {
        entry ?: return
        artifactStoreProvider().save(
            ConfirmedIntentArtifact(
                // 用 entryId 派生 artifactId，保证同一条草稿多次确认幂等
                artifactId = "confirmed-${entry.entryId}",
                entry = entry,
            ),
        )
    }

    /**
     * 按 entryId 从仓库中移除已确认草稿。
     * 用于用户撤销确认时保持仓库与工作台一致。
     */
    fun removeConfirmedEntry(entryId: String) {
        artifactStorePruner.removeConfirmedIntent(
            artifactStore = artifactStoreProvider(),
            entryId = entryId,
        )
    }
}
