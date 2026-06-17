package com.charmnight.linkgraph.codegen

import com.charmnight.linkgraph.llm.EditScope
import com.charmnight.linkgraph.llm.LlmJsonCodec
import com.charmnight.linkgraph.llm.LlmResultSource
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
        /** 草稿结构化编辑操作。 */
        val editOperations = when (val operations = raw["editOperations"]) {
            null -> emptyList()
            is List<*> -> operations.mapIndexed { operationIndex, operation ->
                parseEditOperation(operation as? Map<*, *>, index, operationIndex)
            }
            else -> error("LLM response draft[$index].editOperations must be an array when present.")
        }
        if (content == null && editOperations.isEmpty()) {
            error("LLM response draft[$index] must provide content or editOperations.")
        }
        /** 草稿局部警告列表。 */
        val warnings = (raw["warnings"] as? List<*>).orEmpty().mapNotNull { it as? String }
        /** 草稿级授权 scope，优先解析，主链仍会再用本地 plan 回填。 */
        val editScopes = (raw["editScopes"] as? List<*>).orEmpty().mapNotNull { parseEditScope(it as? Map<*, *>) }
        return GeneratedCodeDraft(
            id = draftId,
            sourceNodeId = sourceNodeId,
            title = title,
            targetPath = targetPath,
            content = content,
            editOperations = editOperations,
            editScopes = editScopes,
            warnings = warnings,
        )
    }

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
        val warnings = (raw["warnings"] as? List<*>).orEmpty().mapNotNull { it as? String }
        return CodeEditOperation(
            operationId = operationId,
            filePath = filePath,
            scopeId = raw["scopeId"] as? String,
            kind = CodeEditOperationKind.entries.firstOrNull { it.name == kindName }
                ?: error("LLM response draft[$draftIndex].editOperations[$operationIndex].kind '$kindName' is unsupported."),
            payload = CodeEditPayloadNormalizer.normalize(payload),
            warnings = warnings,
        )
    }

    private fun parseEditScope(raw: Map<*, *>?): EditScope? {
        raw ?: return null
        return EditScope(
            scopeId = raw["scopeId"] as? String ?: return null,
            targetNodeId = raw["targetNodeId"] as? String ?: return null,
            filePath = raw["filePath"] as? String ?: return null,
            language = raw["language"] as? String ?: "TEXT",
            symbolKind = raw["symbolKind"] as? String ?: "UNKNOWN",
            symbolSignature = raw["symbolSignature"] as? String,
            startOffset = (raw["startOffset"] as? Number)?.toInt(),
            endOffset = (raw["endOffset"] as? Number)?.toInt(),
            startLine = (raw["startLine"] as? Number)?.toInt(),
            endLine = (raw["endLine"] as? Number)?.toInt(),
            allowedChangeKinds = (raw["allowedChangeKinds"] as? List<*>).orEmpty().mapNotNull { it as? String },
            supportingFindingIds = (raw["supportingFindingIds"] as? List<*>).orEmpty().mapNotNull { it as? String },
        )
    }

    /** 提取可能被代码块包裹的纯 JSON 文本。 */
    private fun unwrapJson(content: String): String {
        return RemoteStructuredJsonExtractor.extract(content)
    }
}
