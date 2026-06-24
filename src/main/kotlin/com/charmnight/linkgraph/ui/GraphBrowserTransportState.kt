package com.charmnight.linkgraph.ui

import java.util.UUID
import kotlin.math.max

/**
 * 维护 JCEF 页面与前端之间的快照分发状态。
 */
class GraphBrowserTransportState(
    /** 保存当前浏览器会话的唯一标识。 */
    val sessionId: String = UUID.randomUUID().toString(),
) {
    /**
     * 表示已经下发到前端的一次快照传输内容。
     *
     * 携带修订号和实际要执行的脚本，便于在重试或确认场景下统一比对。
     */
    data class DispatchedTransport(
        /** 本次下发对应的快照修订号。 */
        val revision: Long,
        /** 真正投递到前端执行的脚本文本。 */
        val script: String,
    )

    /**
     * 描述一次"新快照可用"事件被接收/拒绝后的处理结果。
     *
     * 通过 accepted 标记快照是否被记录为待发送，并附带本次立即投递的传输内容（若条件满足）。
     */
    data class SnapshotAvailability(
        /** 快照是否被接收为待发送状态。 */
        val accepted: Boolean,
        /** 若立即完成投递，则携带下发内容；否则为 null。 */
        val transport: DispatchedTransport?,
    )

    /**
     * 表示等待发送到前端的最新快照脚本。
     */
    private data class PendingSnapshot(
        /** 记录快照的修订号。 */
        val revision: Long,
        /** 记录真正发送到前端执行的脚本文本。 */
        val transport: DispatchedTransport,
    )

    /** 标记主框架页面是否已经开始可用。 */
    private var mainFrameLoaded: Boolean = false
    /** 标记前端桥接层是否已经完成初始化。 */
    private var frontendReady: Boolean = false
    /** 记录前端最近一次确认接收的修订号。 */
    private var lastAckedRevision: Long = Long.MIN_VALUE
    /** 记录最近一次已下发的修订号。 */
    private var lastDispatchedRevision: Long = Long.MIN_VALUE
    /** 保存等待发送的最新快照。 */
    private var latestPendingSnapshot: PendingSnapshot? = null

    /**
     * 在主框架重新加载开始时重置传输状态。
     */
    @Synchronized
    fun onMainFrameLoadStarted() {
        // 页面重新加载后，旧的加载状态和修订记录都不再可信，需要整体清空。
        mainFrameLoaded = false
        frontendReady = false
        lastAckedRevision = Long.MIN_VALUE
        lastDispatchedRevision = Long.MIN_VALUE
    }

    /**
     * 在主框架加载完成时尝试发送最新快照。
     */
    @Synchronized
    fun onMainFrameLoadEnded(): DispatchedTransport? {
        mainFrameLoaded = true
        return flushLatestSnapshotIfReady()
    }

    /**
     * 在有新快照可用时记录最新版本，并尝试立即分发。
     */
    @Synchronized
    fun onSnapshotAvailable(
        revision: Long,
        script: String,
    ): SnapshotAvailability {
        // 仅保留修订号更新的快照，避免旧快照覆盖新状态。
        val deliveredFloor = max(lastAckedRevision, lastDispatchedRevision)
        val currentPendingRevision = latestPendingSnapshot?.revision ?: Long.MIN_VALUE
        if (revision <= deliveredFloor || revision < currentPendingRevision) {
            return SnapshotAvailability(
                accepted = false,
                transport = null,
            )
        }
        latestPendingSnapshot = PendingSnapshot(
            revision = revision,
            transport = DispatchedTransport(
                revision = revision,
                script = script,
            ),
        )
        return SnapshotAvailability(
            accepted = true,
            transport = flushLatestSnapshotIfReady(),
        )
    }

    /**
     * 在前端完成初始化后更新确认修订号，并尝试补发最新快照。
     */
    @Synchronized
    fun onFrontendReady(lastAppliedRevision: Long?): DispatchedTransport? {
        frontendReady = true
        if (lastAppliedRevision != null) {
            // 取最大值，避免因旧确认回包回退当前已确认进度。
            lastAckedRevision = max(lastAckedRevision, lastAppliedRevision)
        }
        return flushLatestSnapshotIfReady()
    }

    /**
     * 在前端确认某个快照后推进确认修订号。
     */
    @Synchronized
    fun onSnapshotAcknowledged(revision: Long): DispatchedTransport? {
        // 确认修订号只允许单调递增，避免乱序确认导致状态回退。
        lastAckedRevision = max(lastAckedRevision, revision)
        return flushLatestSnapshotIfReady()
    }

    /**
     * 仅在页面与前端均就绪时发送尚未投递过的最新快照。
     */
    private fun flushLatestSnapshotIfReady(): DispatchedTransport? {
        // 没有待发送快照时直接返回，避免无意义分发。
        val pendingSnapshot = latestPendingSnapshot ?: return null
        if (!mainFrameLoaded || !frontendReady) {
            return null
        }
        // 以“已确认”和“已投递”中的较大修订号作为投递下界，确保不会重复发送旧快照。
        val deliveredFloor = max(lastAckedRevision, lastDispatchedRevision)
        if (pendingSnapshot.revision <= deliveredFloor) {
            latestPendingSnapshot = null
            return null
        }
        // 一旦决定投递，先更新已投递修订号，再把脚本文本返回给调用方。
        lastDispatchedRevision = pendingSnapshot.revision
        latestPendingSnapshot = null
        return pendingSnapshot.transport
    }
}
