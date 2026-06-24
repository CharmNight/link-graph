package com.charmnight.linkgraph.application.port

import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.application.model.GraphEditTransaction
import com.charmnight.linkgraph.application.model.GraphEditRequestParseResult
import com.charmnight.linkgraph.application.model.GraphEditResult
import com.charmnight.linkgraph.llm.tools.ToolGraphSnapshot
import com.charmnight.linkgraph.model.GraphDocument

/**
 * 编辑器快照提供者。
 * 让应用层可以取得当前编辑器状态的不可变快照，而不绑定具体实现。
 */
fun interface EditorSnapshotProvider {
    /** 取当前编辑器快照。 */
    fun snapshot(): WorkflowEditorSnapshot
}

/** 应用层快照提供者，把应用状态以不可变形式暴露给消费者。 */
fun interface ApplicationSnapshotProvider {
    /** 取当前应用层快照。 */
    fun snapshot(): com.charmnight.linkgraph.application.model.ApplicationSnapshot
}

/** 工具图快照提供者，专供 LLM 工具上下文使用。 */
fun interface ToolGraphSnapshotProvider {
    /** 取当前工具图快照。 */
    fun snapshot(): ToolGraphSnapshot
}

/**
 * 工作台图提交器接口。
 *
 * 把工作台图的修改写回底层存储，并触发同步、协同更新等动作。
 * 参数众多是因为提交涉及多种语义（乐观并发、撤销栈、浏览器同步等），
 * 集中到一个方法可以避免分散的 setter 调用。
 */
interface WorkspaceGraphCommitter {
    /**
     * 提交一次工作台图变更。
     *
     * @param expectedSnapshotRevision 期望的快照版本；用于乐观并发控制，null 表示不检查
     * @param graph 新的工作台图
     * @param selectedMethodSignature 当前选中的方法签名；切换方法时需要同步更新
     * @param preserveDraftPatchUndo 是否保留草稿补丁撤销栈
     * @param workingGraphDirty 工作图是否被标记为已修改（脏）
     * @param syncBrowser 是否同步刷新前端浏览器
     * @param graphEditTransaction 图编辑事务；用于差异化提交
     * @return 是否成功提交（版本冲突等情况下返回 false）
     */
    fun commitWorkspaceGraph(
        expectedSnapshotRevision: Long? = null,
        graph: GraphDocument,
        selectedMethodSignature: String? = null,
        preserveDraftPatchUndo: Boolean = false,
        workingGraphDirty: Boolean = true,
        syncBrowser: Boolean = true,
        graphEditTransaction: GraphEditTransaction? = null,
    ): Boolean
}

/** 图编辑请求执行器，把编辑请求应用到当前图上并返回结果。 */
fun interface GraphEditRequestExecutor {
    /** 应用编辑请求；解析失败时 parseResult.issues 非空，由实现侧决定如何构造拒绝结果。 */
    fun apply(parseResult: GraphEditRequestParseResult): GraphEditResult
}
