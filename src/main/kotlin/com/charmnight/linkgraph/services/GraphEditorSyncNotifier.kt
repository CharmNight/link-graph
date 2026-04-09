package com.charmnight.linkgraph.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

/**
 * 把编辑器状态变更后的前端同步请求从服务层解耦出来。
 * 服务层只发布“需要同步”的事实，Tool Window/JCEF 是否存在由 UI 侧自行决定。
 */
@Service(Service.Level.PROJECT)
internal class GraphEditorSyncNotifier(
    private val project: Project,
) {
    fun requestSync() {
        project.messageBus.syncPublisher(TOPIC).onSyncRequested()
    }

    interface Listener {
        fun onSyncRequested()
    }

    companion object {
        @JvmField
        val TOPIC: Topic<Listener> = Topic.create(
            "linkGraphEditorSyncRequested",
            Listener::class.java,
        )
    }
}
