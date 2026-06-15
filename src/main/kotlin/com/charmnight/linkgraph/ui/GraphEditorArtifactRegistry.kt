package com.charmnight.linkgraph.ui

import java.security.MessageDigest

/**
 * 管理 transport 中按需下发的大文本 artifact。
 * bootstrap / workflow slice 只传稳定引用，真正文本按需通过 ARTIFACT_SLICE 回填。
 */
class GraphEditorArtifactRegistry {
    data class AssistantResultArtifacts(
        val qaPromptPreviewArtifactId: String? = null,
        val explanationPromptPreviewArtifactId: String? = null,
        val checkPromptPreviewArtifactId: String? = null,
        val generationPlanPromptPreviewArtifactId: String? = null,
        val generationDiscussionPromptPreviewArtifactId: String? = null,
        val codeDraftContentArtifactIds: Map<String, String> = emptyMap(),
    )

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

    data class PreparedSnapshotArtifacts(
        val refs: SnapshotArtifacts,
        val contents: Map<String, String>,
    )

    @Volatile
    private var artifacts: Map<String, String> = emptyMap()

    fun prepare(snapshot: GraphEditorStateSnapshot): PreparedSnapshotArtifacts {
        val nextArtifacts = linkedMapOf<String, String>()

        fun register(kind: String, ownerKey: String, content: String?): String? {
            if (content.isNullOrBlank()) {
                return null
            }
            val artifactId = "$kind:${sanitize(ownerKey)}:${fingerprint(content)}"
            nextArtifacts[artifactId] = content
            return artifactId
        }

        val draftContentArtifactIds = snapshot.generatedCodeDrafts.mapNotNull { draft ->
            register(
                kind = "draft-content",
                ownerKey = draft.id,
                content = draft.content,
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
                            content = draft.content,
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

    fun replaceWith(snapshot: GraphEditorStateSnapshot): SnapshotArtifacts {
        val prepared = prepare(snapshot)
        replaceWith(prepared)
        return prepared.refs
    }

    fun replaceWith(prepared: PreparedSnapshotArtifacts) {
        artifacts = prepared.contents
    }

    fun read(artifactId: String): String? = artifacts[artifactId]

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

    private fun sanitize(value: String): String = value.replace(NON_ID_CHAR_REGEX, "-")

    private fun fingerprint(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(content.toByteArray(Charsets.UTF_8))
        return bytes.take(8).joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private companion object {
        private val NON_ID_CHAR_REGEX = Regex("[^A-Za-z0-9._-]")
    }
}
