package com.charmnight.linkgraph.application.workflow.subject

import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.usecase.CurrentMethodNodeInput
import com.charmnight.linkgraph.application.usecase.SubjectGraphUseCase
import com.charmnight.linkgraph.foundation.debugLazy
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceLocation
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.model.putSourceLocation
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandle
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectHandle
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind
import com.charmnight.linkgraph.semantic.subject.canonicalTypeText

/**
 * 主题节点追加工作流：负责把当前光标所在方法或当前资源主题转换并追加为图谱节点，
 * 并把变更提交到工作台图谱、发送反馈或事件。
 */
internal class SubjectNodeAppendWorkflow(
    private val dependencies: SubjectGraphWorkflowDependencies,
    private val subjectResolutionWorkflow: SubjectResolutionWorkflow,
    private val useCase: SubjectGraphUseCase,
) {
    /**
     * 追加当前光标所在方法为图谱节点。当未显式传入 handle 时，会基于光标位置自动解析；
     * 解析失败或抛出异常时通过反馈通道告知调用方，并返回 false 表示未追加。
     */
    fun addCurrentMethodNode(handle: CodeSubjectHandle? = null): Boolean {
        debugLazy(dependencies.logger.isDebugEnabled, dependencies.logger::debug) { "开始追加当前方法节点" }
        val currentMethodNode = try {
            computeCurrentMethodNode(handle)
        } catch (throwable: Throwable) {
            dependencies.logger.warn("追加当前方法节点失败", throwable)
            dependencies.emitFeedback(
                ApplicationFeedbackLevel.ERROR,
                "追加当前方法节点失败：${throwable.message ?: throwable.javaClass.simpleName}",
            )
            return false
        } ?: run {
            dependencies.emitFeedback(
                ApplicationFeedbackLevel.WARNING,
                "当前光标不在方法内，请先把光标放到方法签名或方法体内。",
            )
            return false
        }

        val result = useCase.applyCurrentMethodNode(
            snapshot = dependencies.snapshotProvider.snapshot(),
            currentMethodNode = CurrentMethodNodeInput(
                node = currentMethodNode.node,
                methodSignature = currentMethodNode.methodSignature,
                methodDisplayName = currentMethodNode.methodDisplayName,
            ),
        )
        dependencies.emitFeedback(ApplicationFeedbackLevel.SUCCESS, result.statusMessage)
        dependencies.workspaceGraphCommitter.commitWorkspaceGraph(
            graph = result.graph,
            selectedMethodSignature = result.selectedMethodSignature,
            preserveDraftPatchUndo = false,
            syncBrowser = true,
        )
        debugLazy(dependencies.logger.isDebugEnabled, dependencies.logger::debug) {
            "当前方法节点追加完成: signature=${currentMethodNode.methodSignature}"
        }
        return true
    }

    /**
     * 追加指定资源主题为图谱节点：依据 handle 生成节点后交由用例应用、提交到工作台图谱，
     * 并发出资源节点已追加的事件，最终返回 true 表示成功追加。
     */
    fun addCurrentResourceNode(handle: ResourceSubjectHandle): Boolean {
        val node = resourceNodeForHandle(handle)
        debugLazy(dependencies.logger.isDebugEnabled, dependencies.logger::debug) {
            "开始追加当前节点: nodeId=${node.id}, title=${node.title}, kind=${handle.kind}"
        }
        val result = useCase.applyResourceNode(dependencies.snapshotProvider.snapshot(), handle, node)
        dependencies.workspaceGraphCommitter.commitWorkspaceGraph(
            graph = result.graph,
            selectedMethodSignature = null,
            preserveDraftPatchUndo = false,
            syncBrowser = false,
        )
        dependencies.emit(
            GraphEditorApplicationEvent.ResourceNodeAdded(
                selectedNodeId = result.selectedNodeId,
                statusMessage = result.statusMessage,
            ),
        )
        debugLazy(dependencies.logger.isDebugEnabled, dependencies.logger::debug) { "当前节点追加完成: nodeId=${node.id}" }
        return true
    }

    /** 解析当前光标对应的代码主题并构建方法节点输入，整个过程在读线程上异步执行；光标不在方法内时返回 null。 */
    private fun computeCurrentMethodNode(handle: CodeSubjectHandle? = null): CurrentMethodNode? {
        val codeHandle = handle ?: subjectResolutionWorkflow.locateCurrentCodeSubject() ?: return null
        return dependencies.asyncRequestLifecycle.computeOnBackgroundReadThread {
            val method = codeHandle.methodPointer.element ?: return@computeOnBackgroundReadThread null
            val location = buildString {
                append(codeHandle.sourcePath)
                codeHandle.sourceRange.startLine?.let { line ->
                    append(':')
                    append(line)
                    append(':')
                    append(1)
                }
            }
            CurrentMethodNode(
                node = GraphNode(
                    id = GraphNode.stableId(NodeType.METHOD, codeHandle.methodSignature),
                    type = NodeType.METHOD,
                    title = codeHandle.displayName,
                    location = location,
                    doc = method.docComment
                        ?.descriptionElements
                        ?.joinToString(separator = "") { element -> element.text }
                        ?.trim()
                        ?.ifBlank { null },
                    signature = codeHandle.methodSignature,
                    inputs = method.parameterList.parameters.map { parameter -> canonicalTypeText(parameter.type) },
                    outputs = listOf(canonicalTypeText(method.returnType)),
                    sourceKind = codeHandle.kind.name,
                    metadata = buildMap {
                        putSourceLocation(
                            GraphSourceLocation(
                                filePath = codeHandle.sourcePath,
                                startOffset = codeHandle.sourceRange.startOffset,
                                endOffset = codeHandle.sourceRange.endOffset,
                                startLine = codeHandle.sourceRange.startLine,
                                endLine = codeHandle.sourceRange.endLine,
                            ),
                        )
                    },
                ),
                methodSignature = codeHandle.methodSignature,
                methodDisplayName = codeHandle.displayName,
            )
        }
    }

    /** 把资源主题 handle 转换为图谱节点：依据资源种类选择节点类型、组装元数据、定位字符串与稳定节点 ID。 */
    private fun resourceNodeForHandle(handle: ResourceSubjectHandle): GraphNode {
        val nodeType = when (handle.kind) {
            ResourceSubjectKind.CONFIG_ITEM -> NodeType.CONFIG_ITEM
            ResourceSubjectKind.MYBATIS_STATEMENT,
            ResourceSubjectKind.SQL_FILE,
            -> NodeType.SQL
            ResourceSubjectKind.XML_RESOURCE -> NodeType.XML_RESOURCE
            ResourceSubjectKind.MARKDOWN_PAGE -> NodeType.DOC_PAGE
        }
        val location = handle.sourceRange.startLine?.let { line -> "${handle.sourcePath}:$line" } ?: handle.sourcePath
        val metadata = buildMap {
            putAll(handle.attributes)
            when (handle.kind) {
                ResourceSubjectKind.CONFIG_ITEM -> put("file", handle.sourcePath)
                else -> putIfAbsent("path", handle.sourcePath)
            }
        }
        val rawKey = when (handle.kind) {
            ResourceSubjectKind.MYBATIS_STATEMENT,
            ResourceSubjectKind.SQL_FILE,
            -> handle.attributes["statementId"]
                ?: handle.attributes["path"]
                ?: handle.subjectId
            ResourceSubjectKind.CONFIG_ITEM -> handle.attributes["key"] ?: handle.subjectId
            ResourceSubjectKind.XML_RESOURCE,
            ResourceSubjectKind.MARKDOWN_PAGE,
            -> handle.sourcePath
        }
        val ownerContext = when (handle.kind) {
            ResourceSubjectKind.MYBATIS_STATEMENT -> handle.attributes["statementType"]
            ResourceSubjectKind.SQL_FILE -> "file"
            else -> null
        }
        return GraphNode(
            id = GraphNode.stableId(nodeType, rawKey, ownerContext = ownerContext),
            type = nodeType,
            title = handle.displayName,
            location = location,
            sourceKind = handle.kind.name,
            metadata = metadata,
        )
    }
}
