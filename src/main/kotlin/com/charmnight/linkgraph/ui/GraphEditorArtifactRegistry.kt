package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.foundation.utf8ByteLengthAtMost
import com.charmnight.linkgraph.source.SourceArchiveReadLimits
import java.security.MessageDigest

/**
 * 管理传输中按需下发的大文本产物。
 * 启动载荷 / 工作流切片只传稳定引用，真正文本按需通过 ARTIFACT_SLICE 回填。
 */
class GraphEditorArtifactRegistry {
    /**
     * 单个助手结果相关的产物引用集合，覆盖 QA、解释、检查、生成方案、生成讨论等场景下的提示词预览，
     * 以及生成代码草稿内容与其按草稿 ID 索引的映射。
     */
    data class AssistantResultArtifacts(
        val qaPromptPreviewArtifactId: String? = null,
        val explanationPromptPreviewArtifactId: String? = null,
        val checkPromptPreviewArtifactId: String? = null,
        val generationPlanPromptPreviewArtifactId: String? = null,
        val generationDiscussionPromptPreviewArtifactId: String? = null,
        val codeDraftContentArtifactIds: Map<String, String> = emptyMap(),
    )

    /**
     * 一次快照对外暴露的全部产物引用，覆盖各种提示词预览与生成代码草稿内容映射，
     * 同时按结果 ID 聚合对应的助手结果产物引用。
     */
    data class SnapshotArtifacts(
        val qaPromptPreviewArtifactId: String? = null,
        val diffReviewPromptPreviewArtifactId: String? = null,
        val beautificationPromptPreviewArtifactId: String? = null,
        val generationPlanPromptPreviewArtifactId: String? = null,
        val generationPlanDiscussionPromptPreviewArtifactId: String? = null,
        val generatedCodeDraftPromptPreviewArtifactId: String? = null,
        val generatedCodeDraftContentArtifactIds: Map<String, String> = emptyMap(),
        val assistantResultArtifacts: Map<String, AssistantResultArtifacts> = emptyMap(),
    ) {
        companion object {
            val EMPTY = SnapshotArtifacts()
        }
    }

    /** 一次预计算结果：包含对外暴露的产物引用以及产物 ID 到内容的实际映射。 */
    data class PreparedSnapshotArtifacts(
        val refs: SnapshotArtifacts,
        val contents: Map<String, String>,
    )

    @Volatile
    private var artifacts: Map<String, String> = emptyMap()

    /**
     * 扫描快照内所有大文本字段（提示词预览、生成代码草稿内容等），逐项登记为产物，
     * 返回对外引用与实际内容映射的预计算结果，便于后续选择性回填。
     */
    fun prepare(snapshot: GraphEditorStateSnapshot): PreparedSnapshotArtifacts {
        val nextArtifacts = linkedMapOf<String, String>()

        fun register(kind: String, ownerKey: String, content: String?): String? {
            if (content.isNullOrBlank()) {
                return null
            }
            val withinLimit = utf8ByteLengthAtMost(content, MAX_ARTIFACT_CONTENT_BYTES)
            val fingerprintSource = if (withinLimit) {
                content
            } else {
                "${content.take(OVERSIZED_FINGERPRINT_SAMPLE_CHARS)}:${content.length}:oversized"
            }
            val artifactId = "$kind:${sanitize(ownerKey)}:${fingerprint(fingerprintSource)}"
            nextArtifacts[artifactId] = if (withinLimit) content else oversizedArtifactContent()
            return artifactId
        }

        val draftContentArtifactIds = snapshot.generatedCodeDrafts.mapNotNull { draft ->
            register(
                kind = "draft-content",
                ownerKey = draft.id,
                content = (draft.command as? com.charmnight.linkgraph.codegen.CodeDraftCommand.CreateFile)?.content,
            )?.let { artifactId ->
                draft.id to artifactId
            }
        }.toMap()

        val snapshotArtifacts = SnapshotArtifacts(
            qaPromptPreviewArtifactId = register(
                kind = "qa-prompt",
                ownerKey = "qa-result",
                content = snapshot.qaResult?.promptPreview,
            ),
            diffReviewPromptPreviewArtifactId = register(
                kind = "diff-prompt",
                ownerKey = "diff-review-result",
                content = snapshot.diffReviewResult?.promptPreview,
            ),
            beautificationPromptPreviewArtifactId = register(
                kind = "beautification-prompt",
                ownerKey = "beautification-result",
                content = snapshot.graphBeautificationResult?.promptPreview,
            ),
            generationPlanPromptPreviewArtifactId = register(
                kind = "generation-plan-prompt",
                ownerKey = "generation-plan",
                content = snapshot.generationPlan?.promptPreview,
            ),
            generationPlanDiscussionPromptPreviewArtifactId = register(
                kind = "generation-plan-discussion-prompt",
                ownerKey = "generation-plan-discussion",
                content = snapshot.generationPlanDiscussionSession?.promptPreview,
            ),
            generatedCodeDraftPromptPreviewArtifactId = register(
                kind = "generated-draft-prompt",
                ownerKey = "generated-code-draft",
                content = snapshot.generatedCodeDraftPromptPreview,
            ),
            generatedCodeDraftContentArtifactIds = draftContentArtifactIds,
            assistantResultArtifacts = snapshot.assistantResultStore.results.mapValues { (resultId, entry) ->
                AssistantResultArtifacts(
                    qaPromptPreviewArtifactId = register(
                        kind = "assistant-qa-prompt",
                        ownerKey = "$resultId:qa",
                        content = entry.qa?.promptPreview,
                    ),
                    explanationPromptPreviewArtifactId = register(
                        kind = "assistant-explanation-prompt",
                        ownerKey = "$resultId:explanation",
                        content = entry.explanation?.promptPreview,
                    ),
                    checkPromptPreviewArtifactId = register(
                        kind = "assistant-check-prompt",
                        ownerKey = "$resultId:check",
                        content = entry.check?.promptPreview,
                    ),
                    generationPlanPromptPreviewArtifactId = register(
                        kind = "assistant-generation-plan-prompt",
                        ownerKey = "$resultId:generation-plan",
                        content = entry.generationPlan?.promptPreview,
                    ),
                    generationDiscussionPromptPreviewArtifactId = register(
                        kind = "assistant-generation-discussion-prompt",
                        ownerKey = "$resultId:generation-discussion",
                        content = entry.generationDiscussionSession?.promptPreview,
                    ),
                    codeDraftContentArtifactIds = entry.codeDrafts.mapNotNull { draft ->
                        register(
                            kind = "assistant-draft-content",
                            ownerKey = "$resultId:${draft.id}",
                            content = (draft.command as? com.charmnight.linkgraph.codegen.CodeDraftCommand.CreateFile)?.content,
                        )?.let { artifactId -> draft.id to artifactId }
                    }.toMap(),
                )
            },
        )
        return PreparedSnapshotArtifacts(
            refs = snapshotArtifacts,
            contents = nextArtifacts,
        )
    }

    /** 基于快照重新计算并替换当前注册表内容，返回对外暴露的产物引用集合。 */
    fun replaceWith(snapshot: GraphEditorStateSnapshot): SnapshotArtifacts {
        val prepared = prepare(snapshot)
        replaceWith(prepared)
        return prepared.refs
    }

    /** 用一份已预计算好的产物集合整体替换当前注册表内容。 */
    fun replaceWith(prepared: PreparedSnapshotArtifacts) {
        artifacts = prepared.contents
    }

    /** 按产物 ID 读取单个产物内容，未登记时返回 null。 */
    fun read(artifactId: String): String? = artifacts[artifactId]

    /** 批量按产物 ID 读取内容，仅返回当前注册表中存在的项，保持入参顺序。 */
    fun readAll(artifactIds: Collection<String>): Map<String, String> {
        val selected = linkedMapOf<String, String>()
        val currentArtifacts = artifacts
        artifactIds.forEach { artifactId ->
            currentArtifacts[artifactId]?.let { content ->
                selected[artifactId] = content
            }
        }
        return selected
    }

    /** 把所有不适合用作 ID 的字符替换为短横线，确保 owner key 可以安全嵌入产物 ID。 */
    private fun sanitize(value: String): String = value.replace(NON_ID_CHAR_REGEX, "-")

    /** 计算内容的 SHA-256 指纹并截取前 8 字节的小写十六进制表示，用于稳定标识产物。 */
    private fun fingerprint(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(content.toByteArray(Charsets.UTF_8))
        return bytes.take(8).joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun oversizedArtifactContent(): String =
        "内容过大，已省略展示。最大允许 $MAX_ARTIFACT_CONTENT_BYTES bytes。"

    internal companion object {
        const val MAX_ARTIFACT_CONTENT_BYTES: Int = SourceArchiveReadLimits.MAX_TEXT_ENTRY_BYTES
        private const val OVERSIZED_FINGERPRINT_SAMPLE_CHARS: Int = 4096
        private val NON_ID_CHAR_REGEX = Regex("[^A-Za-z0-9._-]")
    }
}
