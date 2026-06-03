package com.charmnight.linkgraph.application.workflow.subject

import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.usecase.CurrentMethodNodeInput
import com.charmnight.linkgraph.application.usecase.SubjectGraphUseCase
import com.charmnight.linkgraph.foundation.debugLazy
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandle
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectHandle
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind
import com.charmnight.linkgraph.semantic.subject.canonicalTypeText

internal class SubjectNodeAppendWorkflow(
    private val dependencies: SubjectGraphWorkflowDependencies,
    private val subjectResolutionWorkflow: SubjectResolutionWorkflow,
    private val useCase: SubjectGraphUseCase,
) {
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
                        put("source.filePath", codeHandle.sourcePath)
                        put("source.startOffset", codeHandle.sourceRange.startOffset.toString())
                        put("source.endOffset", codeHandle.sourceRange.endOffset.toString())
                        codeHandle.sourceRange.startLine?.let { put("source.startLine", it.toString()) }
                        codeHandle.sourceRange.endLine?.let { put("source.endLine", it.toString()) }
                    },
                ),
                methodSignature = codeHandle.methodSignature,
                methodDisplayName = codeHandle.displayName,
            )
        }
    }

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
