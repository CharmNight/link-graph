package com.charmnight.linkgraph.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

/**
 * 把"编辑器状态变更后的前端同步请求"从服务层解耦出来。
 *
 * 服务层只发布"需要同步"的事实，Tool Window/JCEF 是否存在由 UI 侧自行决定。
 * 这种解耦让服务层不依赖具体 UI 实现（也便于在没有 UI 的测试中运行）。
 */
@Service(Service.Level.PROJECT)
internal class GraphEditorSyncNotifier(
    /** 当前项目；用于取得 message bus。 */
    private val project: Project,
) {
    /**
     * 发布一次同步请求。
     * 所有订阅 [TOPIC] 的监听者都会被通知。
     */
    fun requestSync() {
        project.messageBus.syncPublisher(TOPIC).onSyncRequested()
    }

    /** 同步请求监听者接口。 */
    interface Listener {
        /** 收到同步请求时被调用。 */
        fun onSyncRequested()
    }

    companion object {
        /** 同步请求的 message bus topic。监听者通过此 topic 订阅事件。 */
        @JvmField
        val TOPIC: Topic<Listener> = Topic.create(
            "linkGraphEditorSyncRequested",
            Listener::class.java,
        )
    }
}
