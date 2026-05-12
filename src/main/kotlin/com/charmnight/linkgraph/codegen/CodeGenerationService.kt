package com.charmnight.linkgraph.codegen

import com.charmnight.linkgraph.llm.GenerationContext
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.GenerationPlanItem
import com.charmnight.linkgraph.llm.EditScope
import com.charmnight.linkgraph.llm.LlmGateway
import com.charmnight.linkgraph.llm.LlmPromptFactory
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.llm.LlmUserMessageFormatter
import com.charmnight.linkgraph.llm.LlmStructuredSchemas
import com.charmnight.linkgraph.llm.RemoteStructuredResponseSupport
import com.charmnight.linkgraph.llm.RoutingLlmGateway
import com.charmnight.linkgraph.llm.remoteConnectionOrNull
import com.charmnight.linkgraph.llm.remoteLlmSetupHint
import com.charmnight.linkgraph.llm.usesRemoteProvider
import com.charmnight.linkgraph.model.DiffStatus
import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry
import java.nio.file.Paths

/** 单个可写入项目目录的代码草案。 */
data class GeneratedCodeDraft(
    /** 草稿稳定 ID。 */
    val id: String,
    /** 产生该草稿的来源节点 ID。 */
    val sourceNodeId: String,
    /** 前端展示标题。 */
    val title: String,
    /** 相对项目根目录的目标路径。 */
    val targetPath: String,
    /** 新文件草稿完整内容；existing-file 结构化改写时为空。 */
    val content: String? = null,
    /** 现有文件结构化改写操作。 */
    val editOperations: List<CodeEditOperation> = emptyList(),
    /** 已授权的精确编辑作用域。 */
    val editScopes: List<EditScope> = emptyList(),
    /** 基于当前本地文件准备出的局部 patch 预览。 */
    val preparedEdits: List<PreparedCodeEdit> = emptyList(),
    /** 草稿级别的警告信息。 */
    val warnings: List<String> = emptyList(),
)

/** 草案生成阶段的汇总结果。 */
data class CodeGenerationResult(
    /** 生成出的草稿列表。 */
    val drafts: List<GeneratedCodeDraft>,
    /** 生成阶段的全局警告列表。 */
    val warnings: List<String> = emptyList(),
    /** 失败或回退时保留的结构化诊断详情。 */
    val diagnosticDetail: String? = null,
    /** 结果来源，标记是远程还是本地模板。 */
    val source: LlmResultSource = LlmResultSource.LOCAL_RULE,
    /** 本次生成使用的提示词预览。 */
    val promptPreview: String? = null,
)

/** 当前结果是否包含可写入或可展示的代码草稿。 */
internal fun CodeGenerationResult.hasUsableDrafts(): Boolean = drafts.isNotEmpty()

/** 为“生成完成但没有任何可用草稿”的场景构造统一报错文案。 */
internal fun CodeGenerationResult.emptyResultMessage(): String {
    val leadingWarning = warnings.firstOrNull()?.takeIf(String::isNotBlank)
    return when {
        leadingWarning != null -> "未生成任何可用代码草稿：$leadingWarning"
        source == LlmResultSource.REMOTE -> "远程 LLM 未返回任何可用代码草稿。"
        else -> "当前上下文未生成任何可用代码草稿。"
    }
}

/** 把当前结果里的警告拼成详细说明，供失败态展示。 */
internal fun CodeGenerationResult.emptyResultDetailMessage(): String? {
    val warningDetail = warnings
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .joinToString("\n")
        .takeIf(String::isNotEmpty)
    val diagnostic = diagnosticDetail?.trim()?.takeIf(String::isNotEmpty)
    return listOfNotNull(warningDetail, diagnostic)
        .joinToString("\n\n")
        .takeIf(String::isNotEmpty)
}

/** 草案写入项目目录后的结果回执。 */
data class GeneratedCodeDraftWriteReport(
    /** 实际写入成功的文件列表。 */
    val writtenFiles: List<String> = emptyList(),
    /** 因冲突或安全原因跳过的文件列表。 */
    val skippedFiles: List<String> = emptyList(),
    /** 写入过程中的警告信息。 */
    val warnings: List<String> = emptyList(),
)

/**
 * 基于当前链路图、diff 与 generation plan 生成代码草案。
 * 当前优先尝试真实远程 LLM 生成，失败时回退到本地可回溯模板，避免链路中断。
 */
class CodeGenerationService(
    /** 负责构造生成提示词的工厂。 */
    private val promptFactory: LlmPromptFactory = LlmPromptFactory(),
    /** 负责发起远程生成请求的网关。 */
    private val gateway: LlmGateway = RoutingLlmGateway(),
) {
    /** 负责结构化响应请求和解析的辅助组件。 */
    private val responseSupport = RemoteStructuredResponseSupport(gateway)

    /** 基于当前上下文生成代码草稿，优先远程，失败回退本地模板。 */
    fun generateDrafts(
        context: GenerationContext,
        plan: GenerationPlan?,
        settings: LinkGraphSettingsState = LinkGraphSettingsState(),
        onPreview: ((String, Boolean) -> Unit)? = null,
    ): CodeGenerationResult {
        /** 清洗后的设置快照。 */
        val sanitized = settings.sanitized()
        /** 当前上下文对应的提示词包。 */
        val promptPackage = promptFactory.buildCodeGenerationPromptPackage(context, plan, sanitized)
        if (sanitized.usesRemoteProvider()) {
            /** 远程生成可用时的连接配置。 */
            val remoteConnection = sanitized.remoteConnectionOrNull()
            if (remoteConnection == null) {
                /** 配置不完整时回退的本地结果。 */
                val localResult = generateLocalDrafts(context, plan, promptPackage.preview)
                return localResult.copy(
                    warnings = listOf(
                        sanitized.remoteLlmSetupHint("本地模板代码生成"),
                    ) + localResult.warnings,
                )
            }
            runCatching {
                responseSupport.request(
                    remoteConnection.toRequest(
                        systemPrompt = promptPackage.systemPrompt,
                        userPrompt = promptPackage.userPrompt,
                    ),
                    scene = "代码生成",
                    schema = LlmStructuredSchemas.CODE_GENERATION_RESULT,
                    preferStreaming = remoteConnection.preset.capabilities.supportsStreaming,
                    onPreview = onPreview,
                ) { content ->
                    RemoteCodeGenerationResultParser.parse(content, promptPackage.preview)
                }
            }.onSuccess { remoteResult ->
                val normalizedRemoteResult = remoteResult.value
                    .attachAuthorizedScopes(context.confirmedChanges)
                    .rejectUnsafeExistingFileContentDrafts(context.confirmedChanges)
                    .withPrependedWarnings(remoteResult.warnings)
                if (normalizedRemoteResult.hasUsableDrafts()) {
                    return normalizedRemoteResult
                }
                /** 远程返回空结果时回退到本地模板，避免把空 drafts 伪装成成功。 */
                val localResult = generateLocalDrafts(context, plan, promptPackage.preview)
                val fallbackMessage = if (localResult.hasUsableDrafts()) {
                    "远程 LLM 未返回任何可用代码草稿，已回退为本地模板。"
                } else {
                    "远程 LLM 未返回任何可用代码草稿。"
                }
                return localResult.copy(
                    warnings = listOf(fallbackMessage) + normalizedRemoteResult.warnings + localResult.warnings,
                )
            }.onFailure { error ->
                /** 远程失败后的本地回退结果。 */
                val localResult = generateLocalDrafts(context, plan, promptPackage.preview)
                return localResult.copy(
                    warnings = listOf(
                        "远程 LLM 代码生成失败，已回退为本地模板：${LlmUserMessageFormatter.describe(error)}",
                    ) + localResult.warnings,
                    diagnosticDetail = error.message?.trim(),
                )
            }
        }

        return generateLocalDrafts(context, plan, promptPackage.preview)
    }

    /** 使用本地模板为新增节点生成基础草稿。 */
    private fun generateLocalDrafts(
        context: GenerationContext,
        plan: GenerationPlan?,
        promptPreview: String,
    ): CodeGenerationResult {
        /** 指向现有源码的确认项，当前本地规则无法可靠合成其方法体。 */
        val modificationWarnings = buildModificationWarnings(context.confirmedChanges, context.graph)
        /** 仅保留 Mermaid 中新增的节点条目。 */
        val draftEntries = context.diff.entries.filter {
            it.elementKind == GraphDiffElementKind.NODE && it.status == DiffStatus.ONLY_IN_MERMAID
        }
        if (draftEntries.isEmpty()) {
            return CodeGenerationResult(
                drafts = emptyList(),
                warnings = modificationWarnings,
                source = LlmResultSource.LOCAL_RULE,
                promptPreview = promptPreview,
            )
        }

        /** 当前工作图中节点 ID 到节点对象的映射。 */
        val nodesById = context.graph.nodes.associateBy { it.id }
        /** 累积生成出的草稿列表。 */
        val drafts = mutableListOf<GeneratedCodeDraft>()
        /** 生成阶段的警告列表。 */
        val warnings = mutableListOf<String>()
        draftEntries.forEach { entry ->
            /** 当前 diff 条目对应的节点。 */
            val node = nodesById[entry.elementId]
            if (node == null) {
                warnings += "节点 '${entry.elementId}' 不在当前图快照中，无法生成草稿。"
                return@forEach
            }
            when (node.type) {
                NodeType.UNCERTAIN_LINK -> warnings += "节点 '${node.id}' 的类型为 ${node.type.name}，暂时无法做确定性生成。"
                else -> generateDraft(node, context.graph, plan)
                    ?.let(drafts::add)
                    ?: run {
                        warnings += "节点 '${node.id}' 的类型为 ${node.type.name}，当前没有可用的草稿模板。"
                    }
            }
        }
        return CodeGenerationResult(
            drafts = drafts,
            warnings = modificationWarnings + warnings,
            source = LlmResultSource.LOCAL_RULE,
            promptPreview = promptPreview,
        )
    }

    /** 为本地规则模式生成“无法安全改写现有方法”的显式警告。 */
    private fun buildModificationWarnings(
        confirmedChanges: List<DraftWorkbenchEntry>,
        graph: GraphDocument,
    ): List<String> {
        if (confirmedChanges.isEmpty()) {
            return emptyList()
        }
        val nodeById = graph.nodes.associateBy(GraphNode::id)
        return confirmedChanges.mapNotNull { change ->
            val targetPath = change.targetNodeIds.firstNotNullOfOrNull { nodeId ->
                nodeById[nodeId]?.metadata?.get("source.filePath")
                    ?: nodeById[nodeId]?.location?.substringBefore(':')
            } ?: return@mapNotNull null
            "已确认变更“${change.title}”指向现有源码 $targetPath；当前本地规则无法安全改写现有方法，也无法生成结构化 edit ops，请启用远程 LLM 代码生成。"
        }.distinct()
    }

    /** 把远程返回的警告插入到结果警告列表前部。 */
    private fun CodeGenerationResult.withPrependedWarnings(extraWarnings: List<String>): CodeGenerationResult {
        if (extraWarnings.isEmpty()) {
            return this
        }
        return copy(warnings = extraWarnings + warnings)
    }

    /** 用已确认变更里的 authoritative scopes 回填 existing-file draft 的授权范围。 */
    private fun CodeGenerationResult.attachAuthorizedScopes(confirmedChanges: List<DraftWorkbenchEntry>): CodeGenerationResult {
        val confirmedScopes = confirmedChanges
            .flatMap(DraftWorkbenchEntry::editScopes)
            .distinctBy(EditScope::scopeId)
        if (confirmedScopes.isEmpty()) {
            return this
        }
        val scopesByFilePath = confirmedScopes.groupBy(EditScope::filePath)
        val patchedDrafts = drafts.map { draft ->
            if (draft.editOperations.isEmpty() || draft.editScopes.isNotEmpty()) {
                return@map draft
            }
            val operationScopeIds = draft.editOperations.mapNotNull(CodeEditOperation::scopeId).toSet()
            val matchedScopes = if (operationScopeIds.isNotEmpty()) {
                confirmedScopes.filter { scope -> scope.scopeId in operationScopeIds }
            } else {
                scopesByFilePath[draft.targetPath].orEmpty()
            }.distinctBy(EditScope::scopeId)
            if (matchedScopes.isEmpty()) {
                draft.copy(
                    warnings = draft.warnings + "existing-file draft '${draft.targetPath}' 未匹配到本地 authoritative edit scope。",
                )
            } else {
                val matchedScopeById = matchedScopes.associateBy(EditScope::scopeId)
                val authoritativeTargetPath = matchedScopes.firstOrNull()?.filePath ?: draft.targetPath
                draft.copy(
                    targetPath = authoritativeTargetPath,
                    editOperations = draft.editOperations.map { operation ->
                        val authoritativePath = operation.scopeId
                            ?.let(matchedScopeById::get)
                            ?.filePath
                            ?: authoritativeTargetPath
                        operation.copy(filePath = authoritativePath)
                    },
                    editScopes = matchedScopes,
                )
            }
        }
        return copy(drafts = patchedDrafts)
    }

    /** 拒绝把现有文件的局部片段当整文件 content 返回，避免后续 merge 删除未授权逻辑。 */
    private fun CodeGenerationResult.rejectUnsafeExistingFileContentDrafts(
        confirmedChanges: List<DraftWorkbenchEntry>,
    ): CodeGenerationResult {
        val authorizedExistingFiles = confirmedChanges
            .flatMap(DraftWorkbenchEntry::editScopes)
            .map { scope -> normalizeGeneratedDraftPath(scope.filePath) }
            .toSet()
        if (authorizedExistingFiles.isEmpty()) {
            return this
        }

        val retainedDrafts = mutableListOf<GeneratedCodeDraft>()
        val rejectionWarnings = mutableListOf<String>()
        drafts.forEach { draft ->
            val draftPath = normalizeGeneratedDraftPath(draft.targetPath)
            val operationPaths = draft.editOperations
                .map { operation -> normalizeGeneratedDraftPath(operation.filePath) }
                .toSet()
            val touchesExistingAuthorizedFile = draftPath in authorizedExistingFiles ||
                operationPaths.any { operationPath -> operationPath in authorizedExistingFiles }
            if (!touchesExistingAuthorizedFile) {
                retainedDrafts += draft
                return@forEach
            }

            if (draft.editOperations.isEmpty() && draft.content != null) {
                rejectionWarnings += "已拒绝 existing-file draft '${draft.targetPath}'：现有文件必须使用结构化 editOperations，不能用 content 作为整文件 merge 预览。"
                return@forEach
            }
            if (draft.editOperations.isNotEmpty() && draft.content != null) {
                retainedDrafts += draft.copy(
                    content = null,
                    warnings = draft.warnings + "已忽略 existing-file draft '${draft.targetPath}' 的 content；以结构化 editOperations 为准。",
                )
                return@forEach
            }
            retainedDrafts += draft
        }

        return copy(
            drafts = retainedDrafts,
            warnings = warnings + rejectionWarnings,
        )
    }

    private fun normalizeGeneratedDraftPath(path: String): String = path.replace('\\', '/').trim()

    /** 为单个节点生成草稿文件。 */
    private fun generateDraft(
        node: GraphNode,
        graph: GraphDocument,
        plan: GenerationPlan?,
    ): GeneratedCodeDraft? {
        /** 与当前节点最匹配的计划项。 */
        val planItem = resolvePlanItem(node, plan)
        /** 该草稿应写入的目标路径。 */
        val targetPath = resolveTargetPath(node, graph, planItem)
        /** 按节点类型生成的具体内容。 */
        val content = when (node.type) {
            NodeType.SQL -> sqlDraft(node)
            NodeType.CONFIG_ITEM -> propertiesDraft(node)
            NodeType.XML_RESOURCE -> xmlDraft(node)
            NodeType.DOC_PAGE -> markdownDraft(node)
            else -> javaDraft(node, graph, targetPath)
        }
        return GeneratedCodeDraft(
            id = "draft:${node.id}",
            sourceNodeId = node.id,
            title = Paths.get(targetPath).fileName.toString(),
            targetPath = targetPath,
            content = content,
        )
    }

    /** 在生成计划中寻找与节点最接近的计划项。 */
    private fun resolvePlanItem(
        node: GraphNode,
        plan: GenerationPlan?,
    ): GenerationPlanItem? {
        if (plan == null) {
            return null
        }
        /** 节点标题归一化后的对比键。 */
        val normalizedTitle = normalizeToken(node.title)
        return plan.items.firstOrNull { item ->
            /** 计划项目标文件名归一化后的对比键。 */
            val targetName = item.targetPath?.substringAfterLast('/')?.substringBeforeLast('.')?.let(::normalizeToken)
            /** 计划项标题归一化后的对比键。 */
            val itemTitle = normalizeToken(item.title)
            targetName == normalizedTitle || itemTitle.contains(normalizedTitle) || normalizedTitle.contains(itemTitle)
        }
    }

    /** 推断节点对应的目标文件路径。 */
    private fun resolveTargetPath(
        node: GraphNode,
        graph: GraphDocument,
        planItem: GenerationPlanItem?,
    ): String {
        planItem?.targetPath?.takeIf { it.isNotBlank() }?.let { return it }
        return when (node.type) {
            NodeType.SQL -> "src/main/resources/sql/${slug(node.title)}.sql"
            NodeType.CONFIG_ITEM -> "src/main/resources/linkgraph/generated.properties"
            NodeType.XML_RESOURCE -> "src/main/resources/linkgraph/${slug(node.title)}.xml"
            NodeType.DOC_PAGE -> "docs/linkgraph/${slug(node.title)}.md"
            else -> {
                val packageName = inferPackageName(node, graph)
                val simpleClassName = resolveJavaOwnerClass(node)
                "src/main/java/${packageName.replace('.', '/')}/$simpleClassName.java"
            }
        }
    }

    /** 为 Java 节点生成类或方法草稿。 */
    private fun javaDraft(
        node: GraphNode,
        graph: GraphDocument,
        targetPath: String,
    ): String {
        /** 目标类所属包名。 */
        val packageName = targetPath.substringAfter("src/main/java/").substringBeforeLast('/').replace('/', '.')
        /** 目标类名。 */
        val className = targetPath.substringAfterLast('/').substringBeforeLast('.')
        /** 节点文档说明。 */
        val doc = node.doc?.trim().orEmpty()
        /** 需要插入到 Javadoc 中的文档片段。 */
        val headerDoc = if (doc.isNotBlank()) {
            " * $doc\n"
        } else {
            ""
        }

        return if (node.type == NodeType.METHOD) {
            val methodSpec = resolveMethodSpec(node)
            """
                package $packageName;

                /**
                ${headerDoc} * 由链路图根据 Mermaid 设计生成。
                 */
                public class $className {

                    /**
                    ${headerDoc}     * 由 Mermaid 设计生成的方法草稿。
                     */
                    public ${methodSpec.returnType} ${methodSpec.methodName}(${methodSpec.parameters}) {
                        throw new UnsupportedOperationException("由链路图生成");
                    }
                }
            """.trimIndent()
        } else {
            """
                package $packageName;

                /**
                ${headerDoc} * 由链路图根据 Mermaid 设计生成。
                 */
                public class $className {
                }
            """.trimIndent()
        }
    }

    /** 为 SQL 节点生成占位草稿。 */
    private fun sqlDraft(node: GraphNode): String {
        /** SQL 标题，缺失时使用保底名称。 */
        val title = node.title.ifBlank { "generated_statement" }
        /** 节点文档转换后的 SQL 注释。 */
        val docLine = node.doc?.takeIf { it.isNotBlank() }?.let { "-- $it\n" }.orEmpty()
        return """
            ${docLine}-- 由链路图根据 Mermaid 设计生成。
            -- TODO: 补充 $title 的 SQL 实现
        """.trimIndent()
    }

    /** 为配置节点生成 properties 草稿。 */
    private fun propertiesDraft(node: GraphNode): String {
        /** 由标题转换出的配置键。 */
        val key = slug(node.title).replace('-', '.')
        /** 节点文档转换后的 properties 注释。 */
        val docLine = node.doc?.takeIf { it.isNotBlank() }?.let { "# $it\n" }.orEmpty()
        return """
            ${docLine}# 由链路图根据 Mermaid 设计生成。
            $key=
        """.trimIndent()
    }

    /** 为 XML 资源节点生成草稿。 */
    private fun xmlDraft(node: GraphNode): String {
        /** 节点文档转换后的 XML 注释。 */
        val comment = node.doc?.takeIf { it.isNotBlank() }?.let { "<!-- $it -->\n" }.orEmpty()
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            ${comment}<generated-resource name="${escapeXml(node.title)}">
            </generated-resource>
        """.trimIndent()
    }

    /** 为文档节点生成 Markdown 草稿。 */
    private fun markdownDraft(node: GraphNode): String {
        /** Markdown 正文，优先使用节点文档。 */
        val docBlock = node.doc?.takeIf { it.isNotBlank() } ?: "由链路图根据 Mermaid 设计生成。"
        return """
            # ${node.title}

            $docBlock
        """.trimIndent()
    }

    /** 推断 Java 目标文件应使用的包名。 */
    private fun inferPackageName(
        node: GraphNode,
        graph: GraphDocument,
    ): String {
        packageFromSignature(node.signature)?.let { return it }
        packageFromLocation(node.location)?.let { return it }

        /** 与当前节点直接相邻的邻居节点集合。 */
        val neighbors = neighborNodes(node.id, graph)
        neighbors.asSequence().mapNotNull { packageFromSignature(it.signature) }.firstOrNull()?.let { return it }
        neighbors.asSequence().mapNotNull { packageFromLocation(it.location) }.firstOrNull()?.let { return it }

        graph.nodes.asSequence().mapNotNull { packageFromSignature(it.signature) }.firstOrNull()?.let { return it }
        graph.nodes.asSequence().mapNotNull { packageFromLocation(it.location) }.firstOrNull()?.let { return it }

        return DEFAULT_PACKAGE
    }

    /** 收集与指定节点直接相连的邻居节点。 */
    private fun neighborNodes(
        nodeId: String,
        graph: GraphDocument,
    ): List<GraphNode> {
        /** 图中节点 ID 到节点对象的映射。 */
        val nodesById = graph.nodes.associateBy { it.id }
        return graph.edges.asSequence()
            .filter { it.fromNodeId == nodeId || it.toNodeId == nodeId }
            .flatMap { edge ->
                listOfNotNull(nodesById[edge.fromNodeId], nodesById[edge.toNodeId]).asSequence()
            }
            .filter { it.id != nodeId }
            .distinctBy { it.id }
            .toList()
    }

    /** 推断 Java 草稿所属的顶层类名。 */
    private fun resolveJavaOwnerClass(node: GraphNode): String {
        if (node.type == NodeType.METHOD) {
            parseMethodSignature(node.signature)?.ownerClassFqcn?.let { ownerClass ->
                return ownerClass.substringAfterLast('.')
            }
            val title = node.title.substringBefore('.')
            if (title.isNotBlank()) {
                return sanitizeJavaIdentifier(title, "GeneratedService")
            }
        }
        parseClassLikeSignature(node.signature)?.let { fqcn ->
            return fqcn.substringAfterLast('.')
        }
        return sanitizeJavaIdentifier(node.title.substringAfterLast('.'), "GeneratedType")
    }

    /** 推断方法草稿的方法名、返回类型和参数列表。 */
    private fun resolveMethodSpec(node: GraphNode): MethodSpec {
        /** 按签名解析出的结果。 */
        val parsedSignature = parseMethodSignature(node.signature)
        if (parsedSignature != null) {
            return MethodSpec(
                methodName = sanitizeJavaIdentifier(parsedSignature.methodName, "generatedMethod"),
                returnType = simpleJavaType(parsedSignature.returnType),
                parameters = parsedSignature.parameterTypes
                    .mapIndexed { index, type -> "${simpleJavaType(type)} arg$index" }
                    .joinToString(),
            )
        }

        /** 节点原始签名文本。 */
        val signature = node.signature.orEmpty().trim()
        if (signature.isBlank()) {
            return MethodSpec(
                methodName = sanitizeJavaIdentifier(node.title.substringAfterLast('.').replaceFirstChar { it.lowercase() }, "generatedMethod"),
                returnType = "void",
                parameters = node.inputs.mapIndexed { index, type -> "${simpleJavaType(type)} arg$index" }.joinToString(),
            )
        }

        return MethodSpec(
            methodName = sanitizeJavaIdentifier(node.title.substringAfterLast('.').replaceFirstChar { it.lowercase() }, "generatedMethod"),
            returnType = node.outputs.firstOrNull()?.let(::simpleJavaType) ?: "void",
            parameters = node.inputs.mapIndexed { index, type -> "${simpleJavaType(type)} arg$index" }.joinToString(),
        )
    }

    /** 从方法或类签名中提取包名。 */
    private fun packageFromSignature(signature: String?): String? {
        parseMethodSignature(signature)?.ownerClassFqcn
            ?.substringBeforeLast('.', "")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        return parseClassLikeSignature(signature)
            ?.substringBeforeLast('.', "")
            ?.takeIf { it.isNotBlank() }
    }

    /** 解析 `owner.method(param):returnType` 形式的方法签名。 */
    private fun parseMethodSignature(signature: String?): ParsedMethodSignature? {
        /** 去除空白后的签名文本。 */
        val normalized = signature?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (!normalized.contains('(')) {
            return null
        }
        /** 参数前的拥有者与方法名片段。 */
        val beforeParams = normalized.substringBefore('(')
        /** 参数列表片段。 */
        val paramsPart = normalized.substringAfter('(', "").substringBefore(')', "")
        /** 所属类全限定名。 */
        val ownerClass = beforeParams.substringBeforeLast('.', "")
        /** 方法名。 */
        val methodName = beforeParams.substringAfterLast('.', "")
        if (ownerClass.isBlank() || methodName.isBlank()) {
            return null
        }
        return ParsedMethodSignature(
            ownerClassFqcn = ownerClass,
            methodName = methodName,
            parameterTypes = paramsPart.split(',')
                .mapNotNull { it.trim().takeIf(String::isNotBlank) },
            returnType = normalized.substringAfter(':', "void"),
        )
    }

    /** 解析类级签名并返回全限定类名。 */
    private fun parseClassLikeSignature(signature: String?): String? {
        /** 去除空白后的签名文本。 */
        val normalized = signature?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (normalized.contains('(')) {
            return null
        }
        return normalized.substringBefore(':').takeIf { it.contains('.') }
    }

    /** 描述解析后的方法签名信息。 */
    private data class ParsedMethodSignature(
        /** 所属类的全限定名。 */
        val ownerClassFqcn: String,
        /** 方法名。 */
        val methodName: String,
        /** 参数类型列表。 */
        val parameterTypes: List<String>,
        /** 返回类型。 */
        val returnType: String,
    )

    /** 从源码定位字符串中提取包名。 */
    private fun packageFromLocation(location: String?): String? {
        /** 去除行号后的文件路径。 */
        val path = location?.substringBefore(':')?.trim()?.takeIf { it.isNotBlank() } ?: return null
        /** Java 源码目录标记。 */
        val marker = "src/main/java/"
        /** 源码目录在路径中的起始位置。 */
        val start = path.indexOf(marker)
        if (start < 0) {
            return null
        }
        /** 相对源码目录的路径片段。 */
        val relative = path.substring(start + marker.length)
        /** 包路径片段。 */
        val packagePath = relative.substringBeforeLast('/', "")
        return packagePath.replace('/', '.').takeIf { it.isNotBlank() }
    }

    /** 把全限定类型压缩成更适合草稿展示的 Java 类型名。 */
    private fun simpleJavaType(rawType: String): String {
        /** 去除空白后的类型文本。 */
        val normalized = rawType.trim()
        if (normalized.isBlank()) {
            return "Object"
        }
        return when (normalized) {
            "void" -> "void"
            "int", "long", "double", "float", "boolean", "byte", "short", "char" -> normalized
            else -> normalized.substringAfterLast('.')
        }
    }

    /** 把任意文本清洗成合法的 Java 标识符。 */
    private fun sanitizeJavaIdentifier(
        raw: String,
        fallback: String,
    ): String {
        /** 仅保留字母、数字和下划线后的驼峰结果。 */
        val cleaned = raw.replace(Regex("[^A-Za-z0-9_]"), " ").trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString("") { part ->
                part.replaceFirstChar { it.uppercase() }
            }
        return cleaned.takeIf { it.isNotBlank() && it.firstOrNull()?.isLetter() == true } ?: fallback
    }

    /** 把标题压缩为适合路径使用的 slug。 */
    private fun slug(value: String): String {
        return value.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "generated" }
    }

    /** 归一化文本，便于做标题匹配。 */
    private fun normalizeToken(value: String): String {
        return value.lowercase().replace(Regex("[^a-z0-9]+"), "")
    }

    /** 对 XML 属性值做必要转义。 */
    private fun escapeXml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    /** 描述待生成方法的签名信息。 */
    private data class MethodSpec(
        /** 方法名。 */
        val methodName: String,
        /** 返回类型。 */
        val returnType: String,
        /** 参数列表文本。 */
        val parameters: String,
    )

    companion object {
        /** 无法推断时使用的默认包名。 */
        private const val DEFAULT_PACKAGE = "com.generated.linkgraph"
    }
}

/** 判断当前设置是否已经具备远程代码生成能力。 */
private fun LinkGraphSettingsState.isRemoteCodeGenerationReady(): Boolean = remoteConnectionOrNull() != null
