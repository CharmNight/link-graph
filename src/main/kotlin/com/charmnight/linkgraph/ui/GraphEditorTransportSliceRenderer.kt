package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.foundation.LinkGraphRenderTrace
import com.charmnight.linkgraph.json.JsonCodec
import java.security.MessageDigest

/**
 * 渲染前后端之间的权威快照 transport。
 * 除按需回填 artifact 外，每个 snapshotRevision 只发送一份完整快照。
 */
class GraphEditorTransportSliceRenderer(
    /** 负责把快照转成前端可消费的完整 payload 的页面渲染器。 */
    private val pageRenderer: GraphEditorPageRenderer = GraphEditorPageRenderer(),
    /** 管理 artifact 注册表，跟踪 prompt 预览、生成代码草稿等大对象。 */
    private val artifactRegistry: GraphEditorArtifactRegistry = GraphEditorArtifactRegistry(),
    /** 可选的运行时 trace 回调，用于记录每个渲染阶段的耗时与上下文。 */
    private val runtimeTrace: ((() -> String) -> Unit)? = null,
) {
    /** 构建 artifact slice 增量 payload 的工具，复用 pageRenderer 的渲染细节。 */
    private val artifactSlicePayloadBuilder = GraphEditorArtifactSlicePayloadBuilder(pageRenderer)

    /**
     * 已渲染的快照脚本结果。
     *
     * 携带最终写入前端的 JS 文本、对应的传输信封、本次涉及的 artifact 引用与预备内容，
     * 以及本次 payload 的摘要哈希；外部据此判断是否需要提交/记录最新状态。
     */
    class RenderedSnapshotScript internal constructor(
        /** 当前快照的版本号。 */
        val revision: Long,
        /** 注入到前端的脚本字符串。 */
        val script: String,
        /** 本次脚本包含的全部传输信封。 */
        val envelopes: List<GraphEditorTransportEnvelope>,
        /** 本次涉及的 artifact 引用集合。 */
        internal val artifactRefs: GraphEditorArtifactRegistry.SnapshotArtifacts,
        /** 本次涉及的预备 artifact 内容，可能为 null 表示无需提交。 */
        internal val artifactContents: GraphEditorArtifactRegistry.PreparedSnapshotArtifacts?,
        /** 本次 payload 的摘要哈希，null 表示未重新计算哈希。 */
        internal val payloadHash: String?,
    )

    /** 当前已经生效的 artifact 引用集合，用于增量比对。 */
    @Volatile
    private var currentArtifactRefs: GraphEditorArtifactRegistry.SnapshotArtifacts =
        GraphEditorArtifactRegistry.SnapshotArtifacts.EMPTY
    /** 上次成功渲染的 payload 哈希，用于检测可跳过的等价渲染。 */
    @Volatile
    private var lastRenderedPayloadHash: String? = null

    /**
     * 为快照准备 artifact（注册但不一定提交），返回当前的 artifact 引用集合。
     *
     * 调用方可以基于返回的引用集合进行后续判断，再决定是否要 commit 到注册表中。
     */
    fun prepareSnapshotArtifacts(
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
    ): GraphEditorArtifactRegistry.SnapshotArtifacts {
        /** 当前阶段的开始时间，用于 trace 耗时统计。 */
        val artifactStartedAt = System.nanoTime()
        /** 准备好的 artifact 集合，包含引用与具体内容。 */
        val preparedArtifacts = artifactRegistry.prepare(snapshot)
        traceStage(
            stage = "transport.prepareSnapshotArtifacts",
            startedAtNanos = artifactStartedAt,
        ) {
            snapshotDetails(snapshot)
        }
        commitPreparedSnapshotArtifacts(preparedArtifacts)
        return preparedArtifacts.refs
    }

    /**
     * 为 bootstrap 渲染准备 artifact，并预先计算 payload 哈希作为后续增量比对的基线。
     *
     * 返回准备好的 artifact 引用集合；调用方在执行 bootstrap 渲染后即可复用这些引用。
     */
    fun prepareBootstrapSnapshotArtifacts(
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
    ): GraphEditorArtifactRegistry.SnapshotArtifacts {
        /** 准备 artifact 阶段的开始时间。 */
        val artifactStartedAt = System.nanoTime()
        val preparedArtifacts = artifactRegistry.prepare(snapshot)
        traceStage(
            stage = "transport.prepareBootstrapSnapshotArtifacts",
            startedAtNanos = artifactStartedAt,
        ) {
            snapshotDetails(snapshot)
        }
        /** payload 渲染阶段的开始时间。 */
        val payloadStartedAt = System.nanoTime()
        val state = pageRenderer.bootstrapPayload(snapshot, preparedArtifacts.refs)
        traceStage(
            stage = "transport.bootstrap.payload",
            startedAtNanos = payloadStartedAt,
        ) {
            snapshotDetails(snapshot) + payloadDetails(state)
        }
        commitPreparedSnapshotArtifacts(preparedArtifacts)
        lastRenderedPayloadHash = payloadHash(state)
        return preparedArtifacts.refs
    }

    /**
     * 渲染页面首次加载所需的 bootstrap 脚本。
     *
     * 该脚本会向 window 派发携带完整 state 的 bootstrap 事件，让前端在冷启动时一次性拿到全部所需数据。
     */
    fun renderBootstrapInitScript(
        sessionId: String,
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
    ): String {
        val artifactRefs = prepareBootstrapSnapshotArtifacts(snapshot)
        val payloadStartedAt = System.nanoTime()
        val state = pageRenderer.bootstrapPayload(snapshot, artifactRefs)
        lastRenderedPayloadHash = payloadHash(state)
        traceStage(
            stage = "transport.bootstrap.payload",
            startedAtNanos = payloadStartedAt,
        ) {
            snapshotDetails(snapshot) + payloadDetails(state)
        }
        return renderScript(
            listOf(
                GraphEditorTransportEnvelope.Snapshot(
                    sessionId = sessionId,
                    revision = snapshot.snapshotRevision,
                    state = state,
                ),
            ),
        )
    }

    /**
     * 渲染相邻快照之间的增量信封列表。
     *
     * 仅返回增量信封本身（不含脚本），便于上层在不重写脚本时复用信封。
     * 没有可生成的增量时返回空列表。
     */
    fun renderIncrementalEnvelopes(
        sessionId: String,
        previousSnapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
    ): List<GraphEditorTransportEnvelope> =
        renderIncrementalSnapshotScript(
            sessionId = sessionId,
            previousSnapshot = previousSnapshot,
            snapshot = snapshot,
        )?.envelopes.orEmpty()

    /**
     * 渲染相邻快照之间的增量脚本。
     *
     * 内部按"反馈增量 → artifact 增量 → 完整快照 → 跳过等价"的顺序选择传输策略，
     * 仅在确认需要变更时才返回 [RenderedSnapshotScript]，否则返回 null。
     */
    fun renderIncrementalSnapshotScript(
        sessionId: String,
        previousSnapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
    ): RenderedSnapshotScript? {
        if (snapshot.snapshotRevision == previousSnapshot.snapshotRevision) {
            traceStage(
                stage = "transport.payload.compare",
                startedAtNanos = System.nanoTime(),
            ) {
                listOf(
                    "unchanged=true",
                    "reason=sameRevision",
                    "revision=${snapshot.snapshotRevision}",
                )
            }
            return null
        }
        // 仅反馈信息（用户提示、消息类型）发生变化时，生成最小化的反馈增量信封。
        buildFeedbackSliceEnvelope(sessionId, previousSnapshot, snapshot)?.let { envelope ->
            return RenderedSnapshotScript(
                revision = snapshot.snapshotRevision,
                script = renderScript(listOf(envelope)),
                envelopes = listOf(envelope),
                artifactRefs = currentArtifactRefs,
                artifactContents = null,
                payloadHash = null,
            )
        }
        // 仅有 artifact 变化时，生成只携带新增 artifact 的增量信封，避免重发完整 payload。
        buildArtifactSliceEnvelope(sessionId, previousSnapshot, snapshot)?.let { (envelope, preparedArtifacts) ->
            return RenderedSnapshotScript(
                revision = snapshot.snapshotRevision,
                script = renderScript(listOf(envelope)),
                envelopes = listOf(envelope),
                artifactRefs = preparedArtifacts.refs,
                artifactContents = preparedArtifacts,
                payloadHash = null,
            )
        }
        /** 当前快照 payload 渲染阶段的开始时间。 */
        val currentStartedAt = System.nanoTime()
        val preparedArtifacts = artifactRegistry.prepare(snapshot)
        val currentPayload = pageRenderer.bootstrapPayload(snapshot, preparedArtifacts.refs)
        val currentPayloadHash = payloadHash(currentPayload)
        traceStage(
            stage = "transport.payload.current",
            startedAtNanos = currentStartedAt,
        ) {
            snapshotDetails(snapshot) + payloadDetails(currentPayload)
        }
        /** payload 比对阶段的开始时间。 */
        val compareStartedAt = System.nanoTime()
        // 通过哈希判断渲染结果是否与上次等价，等价时跳过本轮以减少前端冗余更新。
        val unchanged = currentPayloadHash == lastRenderedPayloadHash
        traceStage(
            stage = "transport.payload.compare",
            startedAtNanos = compareStartedAt,
        ) {
            listOf(
                "unchanged=$unchanged",
                "previousRevision=${previousSnapshot.snapshotRevision}",
                "currentRevision=${snapshot.snapshotRevision}",
                "strategy=currentPayloadHash",
            )
        }
        if (unchanged) {
            return null
        }
        // 完整 payload 增量：把整份 state 通过 Snapshot 信封重新发送。
        val envelopes = listOf(
            GraphEditorTransportEnvelope.Snapshot(
                sessionId = sessionId,
                revision = snapshot.snapshotRevision,
                state = currentPayload,
            ),
        )
        return RenderedSnapshotScript(
            revision = snapshot.snapshotRevision,
            script = renderScript(envelopes),
            envelopes = envelopes,
            artifactRefs = preparedArtifacts.refs,
            artifactContents = preparedArtifacts,
            payloadHash = currentPayloadHash,
        )
    }

    /**
     * 渲染增量脚本，仅返回脚本字符串。
     *
     * 没有可生成的增量时返回 null，调用方据此决定是否向客户端推送。
     */
    fun renderIncrementalScript(
        sessionId: String,
        previousSnapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
    ): String? {
        return renderIncrementalSnapshotScript(
            sessionId = sessionId,
            previousSnapshot = previousSnapshot,
            snapshot = snapshot,
        )?.script
    }

    /**
     * 提交已渲染快照的副作用。
     *
     * 若脚本中带有预备 artifact 内容，则替换注册表；同时更新上次渲染的 payload 哈希，
     * 让后续增量比对使用最新基线。
     */
    fun commitRenderedSnapshot(renderedSnapshotScript: RenderedSnapshotScript) {
        renderedSnapshotScript.artifactContents?.let(::commitPreparedSnapshotArtifacts)
        renderedSnapshotScript.payloadHash?.let { payloadHash ->
            lastRenderedPayloadHash = payloadHash
        }
    }

    /**
     * 把预备好的 artifact 内容真正提交到注册表，并更新当前 artifact 引用基线。
     */
    private fun commitPreparedSnapshotArtifacts(
        preparedArtifacts: GraphEditorArtifactRegistry.PreparedSnapshotArtifacts,
    ) {
        artifactRegistry.replaceWith(preparedArtifacts)
        currentArtifactRefs = preparedArtifacts.refs
    }

    /**
     * 把传输信封列表序列化为可注入前端的脚本字符串。
     *
     * 每个信封会被转换为一次 `link-graph-bootstrap` 自定义事件派发，
     * 前端通过监听该事件完成状态初始化或增量更新。
     */
    fun renderScript(envelopes: List<GraphEditorTransportEnvelope>): String {
        val startedAt = System.nanoTime()
        val script = envelopes.joinToString(separator = "\n") { envelope ->
            // 不同信封类型在 payload 中携带的字段略有不同，这里根据类型组装对应的 JSON 结构。
            val payload = when (envelope) {
                is GraphEditorTransportEnvelope.Snapshot -> SnapshotEnvelopePayloadDto(
                    sessionId = envelope.sessionId,
                    revision = envelope.revision,
                    state = envelope.state,
                )
                is GraphEditorTransportEnvelope.ArtifactSlice -> ArtifactSliceEnvelopePayloadDto(
                    type = envelope.transportType!!,
                    sessionId = envelope.sessionId,
                    revision = envelope.revision,
                    state = envelope.state,
                )
                is GraphEditorTransportEnvelope.FeedbackSlice -> FeedbackSliceEnvelopePayloadDto(
                    type = envelope.transportType!!,
                    sessionId = envelope.sessionId,
                    revision = envelope.revision,
                    state = envelope.state,
                )
            }
            val envelopeJson = JsonCodec.toScriptSafeJson(payload)
            """window.dispatchEvent(new CustomEvent("link-graph-bootstrap", { detail: $envelopeJson }));"""
        }
        traceStage(
            stage = "transport.renderScript",
            startedAtNanos = startedAt,
        ) {
            listOf(
                "envelopes=${envelopes.size}",
                "scriptChars=${script.length}",
                "revisions=${envelopes.joinToString(separator = "|") { it.revision.toString() }}",
            )
        }
        return script
    }

    /**
     * 按 artifact ID 批量读取其内容，供前端按需获取 prompt 预览或生成草稿。
     */
    fun artifactContents(artifactIds: Collection<String>): Map<String, String> {
        return artifactRegistry.readAll(artifactIds)
    }

    /** 返回当前已生效的 artifact 引用集合，便于外部展示或诊断。 */
    fun currentArtifactRefs(): GraphEditorArtifactRegistry.SnapshotArtifacts = currentArtifactRefs

    /**
     * 构造"仅反馈发生变化"的增量信封。
     *
     * 判定方式：把前一份快照的反馈字段替换为当前值后与当前快照比较，若完全相同则视为只反馈变化。
     * 不满足该条件时返回 null，由上层继续尝试其他增量策略。
     */
    private fun buildFeedbackSliceEnvelope(
        sessionId: String,
        previousSnapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
    ): GraphEditorTransportEnvelope.FeedbackSlice? {
        val previousWithoutFeedback = previousSnapshot.copy(
            snapshotRevision = snapshot.snapshotRevision,
            operationFeedback = snapshot.operationFeedback,
            lastMessageType = snapshot.lastMessageType,
        )
        if (previousWithoutFeedback != snapshot) {
            return null
        }
        val state = FeedbackSlicePayloadDto(
            snapshotRevision = snapshot.snapshotRevision,
            operationFeedback = snapshot.operationFeedback?.let { feedback ->
                OperationFeedbackDto(feedback.level.name, feedback.message)
            },
            lastMessageType = snapshot.lastMessageType,
        )
        return GraphEditorTransportEnvelope.FeedbackSlice(
            sessionId = sessionId,
            revision = snapshot.snapshotRevision,
            state = state,
        )
    }

    /**
     * 构造"仅 artifact 发生变化"的增量信封。
     *
     * 仅当非 artifact 部分完全相同，并且新出现了尚未提交过的 artifact 时，
     * 才构造该增量信封并返回配套的预备 artifact 集合，否则返回 null。
     */
    private fun buildArtifactSliceEnvelope(
        sessionId: String,
        previousSnapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
    ): Pair<GraphEditorTransportEnvelope.ArtifactSlice, GraphEditorArtifactRegistry.PreparedSnapshotArtifacts>? {
        val preparedArtifacts = artifactRegistry.prepare(snapshot)
        if (preparedArtifacts.refs == currentArtifactRefs) {
            return null
        }
        // 去除 artifact 内容后再比较两份快照，确认其余字段确实一致。
        if (stripArtifactPayloads(previousSnapshot) != stripArtifactPayloads(snapshot)) {
            return null
        }
        // 只挑选当前 artifact 引用集合里尚未出现的 artifact，避免重复传输。
        val artifactContents = preparedArtifacts.contents
            .filterKeys { artifactId -> currentArtifactRefs.containsArtifactId(artifactId).not() }
        if (artifactContents.isEmpty()) {
            return null
        }
        val state = artifactSlicePayloadBuilder.build(
            snapshot = snapshot,
            previousArtifactRefs = currentArtifactRefs,
            artifactRefs = preparedArtifacts.refs,
            artifactContents = artifactContents,
        )
        return GraphEditorTransportEnvelope.ArtifactSlice(
            sessionId = sessionId,
            revision = snapshot.snapshotRevision,
            state = state,
        ) to preparedArtifacts
    }

    /**
     * 把单个阶段的耗时与详情记录到运行时 trace 中。
     */
    private fun traceStage(
        stage: String,
        startedAtNanos: Long,
        details: () -> List<String>,
    ) {
        val trace = runtimeTrace ?: return
        LinkGraphRenderTrace.stage(
            enabled = true,
            log = { message -> trace { message } },
            stage = stage,
            startedAtNanos = startedAtNanos,
            details = details,
        )
    }

    /** 生成快照在 trace 中常用的概要字段（覆盖各视图与版本号），便于排查渲染问题。 */
    private fun snapshotDetails(snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot): List<String> = listOf(
        "snapshotRevision=${snapshot.snapshotRevision}",
        "lastMessageType=${snapshot.lastMessageType}",
        "workspace=${LinkGraphRenderTrace.graphSummary(snapshot.workspaceGraph)}",
        "workspaceBase=${LinkGraphRenderTrace.graphSummary(snapshot.workspaceBaseGraph)}",
        "semanticFact=${LinkGraphRenderTrace.graphSummary(snapshot.semanticFactGraph)}",
        "factVisible=${LinkGraphRenderTrace.graphSummary(snapshot.factGraphView.visibleGraph)}",
        "flowVisible=${LinkGraphRenderTrace.graphSummary(snapshot.flowchartView.visibleGraph)}",
        "resourceVisible=${LinkGraphRenderTrace.graphSummary(snapshot.resourceRelationView.visibleGraph)}",
        "architectureVisible=${LinkGraphRenderTrace.graphSummary(snapshot.architectureGraphView.visibleGraph)}",
        "classDiagramVisible=${LinkGraphRenderTrace.graphSummary(snapshot.classDiagramView.visibleGraph)}",
    )

    /** 生成 payload 在 trace 中常用的概要字段（覆盖关键 key 是否存在），便于排查 payload 内容。 */
    private fun payloadDetails(payload: BootstrapPayloadDto): List<String> = listOf(
        "payloadKeys=${payload.javaClass.declaredFields.size}",
        "hasWorkspaceGraph=true",
        "hasFlowchartView=true",
        "hasArchitectureGraphView=true",
        "hasClassDiagramView=true",
    )

    /** 计算 payload 的 SHA-256 哈希，用作增量比对时的"等价性指纹"。 */
    private fun payloadHash(payload: BootstrapPayloadDto): String =
        MessageDigest.getInstance("SHA-256")
            .digest(JsonCodec.toJson(payload).toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    /**
     * 判断当前 artifact 引用集合中是否已经包含某个 artifact ID。
     *
     * 覆盖各类 prompt 预览、生成草稿内容以及助手结果下的 artifact，用于在增量 artifact 信封中过滤已发送过的项。
     */
    private fun GraphEditorArtifactRegistry.SnapshotArtifacts.containsArtifactId(artifactId: String): Boolean {
        if (artifactId == qaPromptPreviewArtifactId ||
            artifactId == diffReviewPromptPreviewArtifactId ||
            artifactId == beautificationPromptPreviewArtifactId ||
            artifactId == generationPlanPromptPreviewArtifactId ||
            artifactId == generationPlanDiscussionPromptPreviewArtifactId ||
            artifactId == generatedCodeDraftPromptPreviewArtifactId
        ) {
            return true
        }
        if (artifactId in generatedCodeDraftContentArtifactIds.values) {
            return true
        }
        return assistantResultArtifacts.values.any { artifacts ->
            artifactId == artifacts.qaPromptPreviewArtifactId ||
                artifactId == artifacts.explanationPromptPreviewArtifactId ||
                artifactId == artifacts.checkPromptPreviewArtifactId ||
                artifactId == artifacts.generationPlanPromptPreviewArtifactId ||
                artifactId == artifacts.generationDiscussionPromptPreviewArtifactId ||
                artifactId in artifacts.codeDraftContentArtifactIds.values
        }
    }

    /**
     * 把快照中所有 artifact 相关的大对象清空，仅保留结构信息，用于 artifact 增量比对。
     */
    private fun stripArtifactPayloads(
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
    ): com.charmnight.linkgraph.ui.GraphEditorStateSnapshot {
        return snapshot.copy(
            snapshotRevision = 0,
            qaResult = snapshot.qaResult?.copy(promptPreview = ""),
            diffReviewResult = snapshot.diffReviewResult?.copy(promptPreview = ""),
            graphBeautificationResult = snapshot.graphBeautificationResult?.copy(promptPreview = ""),
            generationPlan = snapshot.generationPlan?.copy(promptPreview = ""),
            generationPlanDiscussionSession = snapshot.generationPlanDiscussionSession?.copy(promptPreview = null),
            generatedCodeDrafts = snapshot.generatedCodeDrafts.map { draft -> draft.copy(content = null) },
            generatedCodeDraftPromptPreview = null,
            assistantResultStore = stripAssistantArtifactPayloads(snapshot.assistantResultStore),
        )
    }

    /**
     * 清空助手结果存储中所有 artifact 相关大对象，配合 [stripArtifactPayloads] 使用。
     */
    private fun stripAssistantArtifactPayloads(
        store: com.charmnight.linkgraph.workbench.AssistantResultStore,
    ): com.charmnight.linkgraph.workbench.AssistantResultStore {
        return store.copy(
            results = store.results.mapValues { (_, entry) ->
                entry.copy(
                    qa = entry.qa?.copy(promptPreview = ""),
                    explanation = entry.explanation?.copy(promptPreview = ""),
                    generationPlan = entry.generationPlan?.copy(promptPreview = ""),
                    generationDiscussionSession = entry.generationDiscussionSession?.copy(promptPreview = null),
                    codeDrafts = entry.codeDrafts.map { draft -> draft.copy(content = null) },
                    check = entry.check?.copy(promptPreview = ""),
                )
            },
        )
    }
}
