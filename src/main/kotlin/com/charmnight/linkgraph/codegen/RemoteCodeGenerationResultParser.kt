package com.charmnight.linkgraph.codegen

import com.charmnight.linkgraph.llm.LlmJsonCodec
import com.charmnight.linkgraph.agent.model.LlmResultSource
import com.charmnight.linkgraph.llm.RemoteStructuredJsonExtractor

/**
 * 解析远程 LLM 返回的代码草稿 JSON。
 * 这里要求结构稳定；如果格式不对，直接抛错并由上层回退到本地模板。
 */
internal object RemoteCodeGenerationResultParser {
    /** 把远程返回的 JSON 解析为代码草稿结果。 */
    fun parse(
        content: String,
        promptPreview: String,
    ): CodeGenerationResult {
        /** 解析后的 JSON 根对象。 */
        val root = LlmJsonCodec.parseObject(unwrapJson(content))
        /** 远程返回的警告列表。 */
        val warnings = (root["warnings"] as? List<*>).orEmpty().mapNotNull { it as? String }
        /** 远程返回的代码草稿列表。 */
        val rawDrafts = root["drafts"] as? List<*> ?: error("LLM response field 'drafts' must be an array.")
        requireListWithinLimit(
            rawDrafts,
            "LLM response drafts",
            CodeDraftContentLimits.MAX_DRAFTS_PER_RESPONSE,
        )
        val drafts = rawDrafts.mapIndexed { index, rawDraft ->
            parseDraft(rawDraft as? Map<*, *>, index)
        }
        return CodeGenerationResult(
            drafts = drafts,
            warnings = warnings,
            source = LlmResultSource.REMOTE,
            promptPreview = promptPreview,
        )
    }

    /** 解析单个代码草稿对象。 */
    private fun parseDraft(raw: Map<*, *>?, index: Int): GeneratedCodeDraft {
        raw ?: error("LLM response draft[$index] must be an object.")
        /** 草稿目标路径。 */
        val targetPath = raw["targetPath"] as? String ?: error("LLM response draft[$index].targetPath is required.")
        /** 草稿标题，缺失时回退到文件名。 */
        val title = raw["title"] as? String ?: targetPath.substringAfterLast('/')
        /** 草稿稳定 ID。 */
        val draftId = raw["id"] as? String ?: "draft:$targetPath"
        /** 来源节点 ID，缺失时复用草稿 ID。 */
        val sourceNodeId = raw["sourceNodeId"] as? String ?: draftId
        /** 草稿完整内容。 */
        val content = raw["content"] as? String
        requireDraftTextWithinLimit(content, "LLM response draft[$index].content")
        /** 草稿结构化编辑操作。 */
        val editOperations = when (val operations = raw["editOperations"]) {
            null -> emptyList()
            is List<*> -> {
                requireListWithinLimit(
                    operations,
                    "LLM response draft[$index].editOperations",
                    CodeDraftContentLimits.MAX_EDIT_OPERATIONS_PER_DRAFT,
                )
                operations.mapIndexed { operationIndex, operation ->
                    parseEditOperation(operation as? Map<*, *>, index, operationIndex)
                }
            }
            else -> error("LLM response draft[$index].editOperations must be an array when present.")
        }
        if (content == null && editOperations.isEmpty()) {
            error("LLM response draft[$index] must provide content or editOperations.")
        }
        /** 草稿局部警告列表。 */
        val warnings = (raw["warnings"] as? List<*>).orEmpty().mapNotNull { it as? String }
        if ("editScopes" in raw) {
            error("LLM response draft[$index].editScopes is unsupported; edit authorization is assigned locally.")
        }
        val command = normalizeCommand(
            targetPath = targetPath,
            content = content,
            editOperations = editOperations,
            draftIndex = index,
        )
        return GeneratedCodeDraft(
            id = draftId,
            sourceNodeId = sourceNodeId,
            title = title,
            command = command,
            warnings = warnings,
        )
    }

    private fun normalizeCommand(
        targetPath: String,
        content: String?,
        editOperations: List<CodeEditOperation>,
        draftIndex: Int,
    ): CodeDraftCommand {
        if (content != null && editOperations.isNotEmpty()) {
            error("LLM response draft[$draftIndex] cannot provide both content and editOperations.")
        }
        if (content != null) {
            if (content.isBlank()) {
                error("LLM response draft[$draftIndex].content must not be blank.")
            }
            return CodeDraftCommand.CreateFile(targetPath = targetPath, content = content)
        }
        if (editOperations.isEmpty()) {
            error("LLM response draft[$draftIndex] must provide content or editOperations.")
        }
        editOperations.forEachIndexed { operationIndex, operation ->
            if (!sameDraftPath(operation.filePath, targetPath)) {
                error(
                    "LLM response draft[$draftIndex].editOperations[$operationIndex].filePath " +
                        "must match draft targetPath.",
                )
            }
        }
        val createOperations = editOperations.filter { operation -> operation.kind == CodeEditOperationKind.CREATE_FILE }
        if (createOperations.isNotEmpty()) {
            if (editOperations.size != 1) {
                error("LLM response draft[$draftIndex] cannot mix CREATE_FILE with patch operations.")
            }
            val createOperation = createOperations.single()
            if (createOperation.payload.isBlank()) {
                error("LLM response draft[$draftIndex] CREATE_FILE payload must not be blank.")
            }
            if (createOperation.scopeId != null) {
                error("LLM response draft[$draftIndex] CREATE_FILE must not declare scopeId.")
            }
            return CodeDraftCommand.CreateFile(
                targetPath = targetPath,
                content = createOperation.payload,
            )
        }
        return CodeDraftCommand.PatchExistingFile(
            targetPath = targetPath,
            operations = editOperations,
            scopes = emptyList(),
        )
    }

    private fun sameDraftPath(left: String, right: String): Boolean =
        left.trim().replace('\\', '/') == right.trim().replace('\\', '/')

    /** 解析单个结构化编辑操作对象，校验必要字段并构造可执行的编辑动作。 */
    private fun parseEditOperation(
        raw: Map<*, *>?,
        draftIndex: Int,
        operationIndex: Int,
    ): CodeEditOperation {
        raw ?: error("LLM response draft[$draftIndex].editOperations[$operationIndex] must be an object.")
        val operationId = raw["operationId"] as? String
            ?: error("LLM response draft[$draftIndex].editOperations[$operationIndex].operationId is required.")
        val filePath = raw["filePath"] as? String
            ?: error("LLM response draft[$draftIndex].editOperations[$operationIndex].filePath is required.")
        val kindName = raw["kind"] as? String
            ?: error("LLM response draft[$draftIndex].editOperations[$operationIndex].kind is required.")
        val payload = raw["payload"] as? String
            ?: error("LLM response draft[$draftIndex].editOperations[$operationIndex].payload is required.")
        requireDraftTextWithinLimit(payload, "LLM response draft[$draftIndex].editOperations[$operationIndex].payload")
        val normalizedPayload = CodeEditPayloadNormalizer.normalize(payload)
        requireDraftTextWithinLimit(
            normalizedPayload,
            "LLM response draft[$draftIndex].editOperations[$operationIndex].payload",
        )
        val warnings = (raw["warnings"] as? List<*>).orEmpty().mapNotNull { it as? String }
        return CodeEditOperation(
            operationId = operationId,
            filePath = filePath,
            scopeId = raw["scopeId"] as? String,
            kind = CodeEditOperationKind.entries.firstOrNull { it.name == kindName }
                ?: error("LLM response draft[$draftIndex].editOperations[$operationIndex].kind '$kindName' is unsupported."),
            payload = normalizedPayload,
            warnings = warnings,
        )
    }

    /** 提取可能被代码块包裹的纯 JSON 文本。 */
    private fun unwrapJson(content: String): String {
        return RemoteStructuredJsonExtractor.extract(content)
    }

    private fun requireDraftTextWithinLimit(value: String?, fieldName: String) {
        if (!CodeDraftContentLimits.isWithinTextLimit(value)) {
            error("$fieldName is too large; maximum ${CodeDraftContentLimits.MAX_DRAFT_CONTENT_BYTES} UTF-8 bytes.")
        }
    }

    private fun requireListWithinLimit(values: List<*>, fieldName: String, maxItems: Int) {
        if (values.size > maxItems) {
            error("$fieldName exceeds limit: ${values.size} > $maxItems.")
        }
    }
}
