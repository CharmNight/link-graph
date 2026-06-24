package com.charmnight.linkgraph.application.usecase

import com.charmnight.linkgraph.application.edit.GraphEditApplier
import com.charmnight.linkgraph.application.edit.GraphEditPermissionPolicy
import com.charmnight.linkgraph.application.edit.GraphEditScriptValidator
import com.charmnight.linkgraph.application.model.GraphEditIssue
import com.charmnight.linkgraph.application.model.GraphEditIssueCode
import com.charmnight.linkgraph.application.model.GraphEditRejected
import com.charmnight.linkgraph.application.model.GraphEditRequest
import com.charmnight.linkgraph.application.model.GraphEditTransaction
import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.application.model.currentVisibleGraph
import com.charmnight.linkgraph.application.workflow.FrontendGraphMutationSanitizer
import com.charmnight.linkgraph.diff.GraphDiffer
import com.charmnight.linkgraph.diff.GraphDifferResult
import com.charmnight.linkgraph.mermaid.MermaidExporter
import com.charmnight.linkgraph.mermaid.MermaidImporter
import com.charmnight.linkgraph.mermaid.MermaidIssue
import com.charmnight.linkgraph.mermaid.MermaidValidator
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.sync.SyncPreviewItem
import com.charmnight.linkgraph.sync.SyncPreviewPlanner

/**
 * 工作台图用例结果 sealed 接口：用统一类型表示各类操作（加载、编辑、布局、导入导出、差异、同步预览等）的结果，
 * 便于上层做穷尽式分支处理。
 */
sealed interface WorkspaceGraphUseCaseResult {
    /** 图加载成功，携带加载到的图文档以及来源标识（如文件路径或快照名）。 */
    data class Loaded(val graph: GraphDocument, val source: String) : WorkspaceGraphUseCaseResult
    /** 图编辑请求被拒绝，包含拒绝详情（错误码、信息、是否可重试等）。 */
    data class EditRejected(val rejection: GraphEditRejected) : WorkspaceGraphUseCaseResult
    /** 图编辑成功应用，携带期望的快照版本号、新图、当前选中的方法签名以及编辑事务（用于撤销/审计）。 */
    data class EditApplied(
        val expectedSnapshotRevision: Long,
        val graph: GraphDocument,
        val selectedMethodSignature: String?,
        val transaction: GraphEditTransaction,
    ) : WorkspaceGraphUseCaseResult
    /** 布局变更结果，携带节点位置映射（部分显示模式下不会返回位置变更）。 */
    data class LayoutChanged(
        val positions: Map<String, com.charmnight.linkgraph.application.model.GraphLayoutPosition>,
    ) : WorkspaceGraphUseCaseResult
    /** Mermaid 导入结果，包含原始文本、解析得到的图文档以及校验产生的问题列表。 */
    data class MermaidImported(
        val mermaid: String,
        val graph: GraphDocument,
        val issues: List<MermaidIssue>,
    ) : WorkspaceGraphUseCaseResult
    /** Mermaid 导出结果，仅携带导出后的 Mermaid 文本。 */
    data class MermaidExported(val exported: String) : WorkspaceGraphUseCaseResult
    /** 差异模式无法展示：缺少必要的输入（如代码事实图或设计基线图为空）。 */
    data object MissingDiffInputs : WorkspaceGraphUseCaseResult
    /** 差异模式成功展示，携带代码事实图与设计基线图差异计算的结果。 */
    data class DiffShown(val result: GraphDifferResult) : WorkspaceGraphUseCaseResult
    /** 同步预览已准备好，携带需要同步的差异条目列表。 */
    data class SyncPreviewReady(val items: List<SyncPreviewItem>) : WorkspaceGraphUseCaseResult
}

/**
 * 工作台图用例：聚合图加载、图编辑请求、Mermaid 导入导出、布局调整、差异展示与同步预览等核心工作台操作，
 * 是工作台前端与底层数据/校验/编辑链路之间的应用服务层。
 */
class WorkspaceGraphUseCase internal constructor(
    private val mermaidImporter: MermaidImporter,
    private val mermaidValidator: MermaidValidator,
    private val mermaidExporter: MermaidExporter,
    private val graphDiffer: GraphDiffer,
    private val syncPreviewPlanner: SyncPreviewPlanner,
    private val frontendGraphMutationSanitizer: FrontendGraphMutationSanitizer = FrontendGraphMutationSanitizer(),
    private val graphEditScriptValidator: GraphEditScriptValidator = GraphEditScriptValidator(),
    private val graphEditPermissionPolicy: GraphEditPermissionPolicy = GraphEditPermissionPolicy(),
) {
    /**
     * 串行化图编辑的 check-and-apply，避免两个并发请求都通过 baseWorkspaceRevision 校验后双双落到 commit。
     *
     * 旧实现 check 与 apply 不在同一临界区：A、B 两个线程同时读到同一 baseRevision 时会都通过校验，
     * 最终两次 commit 都生效，工作区 revision 只 +1 而不是 +2，导致其中一个编辑被静默吞掉。
     * 这里用实例级 lock 把 check → permission → validate → apply 整段保护起来，
     * 同时用 [lastAcceptedBaseRevision] 记录最近一次成功 apply 的 baseRevision —— 同一 baseRevision
     * 的后续请求视为已过期（STALE_BASE_REVISION），保证同一基线只会被消费一次。
     * 注意：这只保护 use case 内的检查；真正的 CAS 仍需在 commit 阶段做（不在本层职责内）。
     */
    private val workspaceEditLock = Any()

    @Volatile
    private var lastAcceptedBaseRevision: Long = Long.MIN_VALUE
    // 图编辑应用器：在权限和校验通过后真正把编辑脚本应用到图上
    private val graphEditApplier = GraphEditApplier(frontendGraphMutationSanitizer)

    /**
     * 将给定的图文档包装为加载成功结果。
     * @param graph 已加载的图文档
     * @param source 来源标识（用于前端展示，例如文件路径或快照名）
     */
    fun loadGraph(graph: GraphDocument, source: String): WorkspaceGraphUseCaseResult.Loaded =
        WorkspaceGraphUseCaseResult.Loaded(graph, source)

    /**
     * 应用图编辑请求：依次执行基准版本检查、权限策略评估、脚本校验，通过后才真正应用编辑脚本并构造编辑事务。
     * 任意一步失败都会以 [WorkspaceGraphUseCaseResult.EditRejected] 形式返回。
     * @param snapshot 当前工作台编辑器快照
     * @param request 待应用的图编辑请求
     */
    fun applyGraphEditRequest(
        snapshot: WorkflowEditorSnapshot,
        request: GraphEditRequest,
    ): WorkspaceGraphUseCaseResult = synchronized(workspaceEditLock) {
        if (snapshot.workspaceRevision != request.baseWorkspaceRevision) {
            return@synchronized editRejected(
                snapshot,
                GraphEditIssue(
                    code = GraphEditIssueCode.STALE_BASE_REVISION,
                    message = "图编辑基准版本已过期，请重新读取当前图后重试。",
                    retryable = true,
                ),
            )
        }
        // 同一 baseRevision 只允许消费一次：另一个并发请求即便拿着同一 snapshot 进入临界区，
        // 也会因为 lastAcceptedBaseRevision 已被前一个请求更新而判定为 STALE。
        if (request.baseWorkspaceRevision == lastAcceptedBaseRevision) {
            return@synchronized editRejected(
                snapshot,
                GraphEditIssue(
                    code = GraphEditIssueCode.STALE_BASE_REVISION,
                    message = "图编辑基准版本已被另一并发请求消费，请重新读取当前图后重试。",
                    retryable = true,
                ),
            )
        }
        val permissionDecision = graphEditPermissionPolicy.evaluate(snapshot, request)
        if (permissionDecision.issues.isNotEmpty()) {
            return@synchronized editRejected(snapshot, permissionDecision.issues)
        }
        val validationIssues = graphEditScriptValidator.validate(
            snapshot.workspaceGraph,
            request,
            permissionDecision.resolution,
        )
        if (validationIssues.isNotEmpty()) {
            return@synchronized editRejected(snapshot, validationIssues)
        }
        val applierResult = graphEditApplier.apply(snapshot, request, permissionDecision.resolution)
        if (applierResult.issues.isNotEmpty()) {
            return@synchronized editRejected(snapshot, applierResult.issues)
        }
        val graph = applierResult.graph
        lastAcceptedBaseRevision = request.baseWorkspaceRevision
        WorkspaceGraphUseCaseResult.EditApplied(
            expectedSnapshotRevision = snapshot.snapshotRevision,
            graph = graph,
            selectedMethodSignature = snapshot.selectedMethodSignature,
            transaction = GraphEditTransaction(
                graphBeforeApply = snapshot.workspaceGraph,
                graphAfterApply = graph,
                request = request,
                appliedOperations = request.operations,
                source = request.source,
                workspaceRevisionBefore = snapshot.workspaceRevision,
                workspaceRevisionAfter = snapshot.workspaceRevision + 1,
            ),
        )
    }

    /**
     * 导入 Mermaid 文本：先解析为图文档，再对结果做校验，得到校验问题列表后返回。
     * @param mermaid 待导入的 Mermaid 文本
     */
    fun importMermaid(mermaid: String): WorkspaceGraphUseCaseResult.MermaidImported {
        val parseResult = mermaidImporter.import(mermaid)
        val issues = mermaidValidator.validate(parseResult.document, parseResult.issues)
        return WorkspaceGraphUseCaseResult.MermaidImported(mermaid, parseResult.document, issues)
    }

    /**
     * 应用布局变更：当处于架构图/类图/审查图等不可调整布局的显示模式时返回空映射，否则原样返回传入的位置映射。
     * @param snapshot 当前工作台编辑器快照
     * @param positions 前端提交的节点位置映射（按节点 ID 索引）
     */
    fun changeLayout(
        snapshot: WorkflowEditorSnapshot,
        positions: Map<String, com.charmnight.linkgraph.application.model.GraphLayoutPosition>,
    ): WorkspaceGraphUseCaseResult.LayoutChanged {
        if (snapshot.analysisDisplayMode == AnalysisDisplayMode.ARCHITECTURE_GRAPH ||
            snapshot.analysisDisplayMode == AnalysisDisplayMode.CLASS_DIAGRAM ||
            snapshot.analysisDisplayMode == AnalysisDisplayMode.REVIEW_GRAPH
        ) {
            return WorkspaceGraphUseCaseResult.LayoutChanged(emptyMap())
        }
        return WorkspaceGraphUseCaseResult.LayoutChanged(positions)
    }

    /**
     * 将当前工作台视图导出为 Mermaid 文本：优先使用设计基线图，其次在流程图模式下取非空流程图全图，最后回退到当前可见图。
     * @param snapshot 当前工作台编辑器快照
     */
    fun exportMermaid(snapshot: WorkflowEditorSnapshot): WorkspaceGraphUseCaseResult.MermaidExported {
        val document = snapshot.designBaselineGraph
            ?: if (snapshot.analysisDisplayMode == AnalysisDisplayMode.FLOWCHART) {
                snapshot.flowchartView.fullGraph.takeIf { graph -> graph.nodes.isNotEmpty() || graph.edges.isNotEmpty() }
            } else {
                null
            }
            ?: currentVisibleGraph(snapshot)
        return WorkspaceGraphUseCaseResult.MermaidExported(mermaidExporter.export(document))
    }

    /**
     * 进入差异模式：若代码事实图或设计基线图为空则返回 [WorkspaceGraphUseCaseResult.MissingDiffInputs]，
     * 否则计算两者的差异并返回展示结果。
     * @param snapshot 当前工作台编辑器快照
     */
    fun showDiffMode(snapshot: WorkflowEditorSnapshot): WorkspaceGraphUseCaseResult {
        val codeGraph = snapshot.semanticFactGraph.takeIf { it.nodes.isNotEmpty() || it.edges.isNotEmpty() }
            ?: return WorkspaceGraphUseCaseResult.MissingDiffInputs
        val designGraph = snapshot.designBaselineGraph ?: return WorkspaceGraphUseCaseResult.MissingDiffInputs
        return WorkspaceGraphUseCaseResult.DiffShown(graphDiffer.diff(codeGraph, designGraph))
    }

    /**
     * 计算同步预览：优先使用已存在的差异图与差异结果；否则用代码事实图与设计基线图现算差异；两者皆无时返回空列表。
     * @param snapshot 当前工作台编辑器快照
     */
    fun requestSyncPreview(snapshot: WorkflowEditorSnapshot): WorkspaceGraphUseCaseResult.SyncPreviewReady {
        val previewItems = when {
            snapshot.diffGraph != null && snapshot.diff != null -> syncPreviewPlanner.plan(snapshot.diffGraph, snapshot.diff)
            snapshot.semanticFactGraph.nodes.isNotEmpty() || snapshot.semanticFactGraph.edges.isNotEmpty() -> {
                val designBaseline = snapshot.designBaselineGraph ?: return WorkspaceGraphUseCaseResult.SyncPreviewReady(emptyList())
                val result = graphDiffer.diff(snapshot.semanticFactGraph, designBaseline)
                syncPreviewPlanner.plan(result.graph, result.diff)
            }
            else -> emptyList()
        }
        return WorkspaceGraphUseCaseResult.SyncPreviewReady(previewItems)
    }

    /**
     * 单条问题便捷构造方法：把单个 [GraphEditIssue] 包装成列表后调用列表版本。
     */
    private fun editRejected(
        snapshot: WorkflowEditorSnapshot,
        issue: GraphEditIssue,
    ): WorkspaceGraphUseCaseResult.EditRejected = editRejected(snapshot, listOf(issue))

    /**
     * 把问题列表组装成图编辑拒绝结果，并补上当前工作台版本号供前端刷新基准。
     */
    private fun editRejected(
        snapshot: WorkflowEditorSnapshot,
        issues: List<GraphEditIssue>,
    ): WorkspaceGraphUseCaseResult.EditRejected =
        WorkspaceGraphUseCaseResult.EditRejected(
            GraphEditRejected(
                issues = issues,
                currentWorkspaceRevision = snapshot.workspaceRevision,
            ),
        )
}
