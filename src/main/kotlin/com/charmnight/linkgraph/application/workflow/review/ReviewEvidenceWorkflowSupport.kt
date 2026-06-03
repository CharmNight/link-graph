package com.charmnight.linkgraph.application.workflow.review

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.architecture.architectureIndexRuntime
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.review.GraphDiffChangedFileExtractor
import com.charmnight.linkgraph.review.ReviewEvidenceBundle
import com.charmnight.linkgraph.review.ReviewGraphQueryService
import com.charmnight.linkgraph.review.git.GitChangeSetProvider
import com.intellij.openapi.project.Project

internal class ReviewEvidenceWorkflowSupport(
    private val project: Project,
    private val changeSetProviderFactory: (String?) -> GitChangeSetProvider = ::GitChangeSetProvider,
) {
    fun reviewService(index: ArchitectureGraphIndex): ReviewGraphQueryService =
        project.architectureIndexRuntime().reviewQuery(index = index)

    fun buildEvidence(
        diff: GraphDiff?,
        selectedDiffItemIds: List<String>,
        reviewService: ReviewGraphQueryService,
    ): ReviewEvidenceBuildResult {
        val selection = diff?.selection(selectedDiffItemIds)
        val selectedPaths = selection?.selectedPaths.orEmpty()
        val gitChangeSet = when {
            selectedPaths.isNotEmpty() -> changeSetProviderFactory(project.basePath).workingTreeChangeSet(selectedPaths)
            selection?.hasSelection == true -> emptyList()
            else -> changeSetProviderFactory(project.basePath).workingTreeChangeSet()
        }
        val bundle = when {
            gitChangeSet.isNotEmpty() -> reviewService.buildEvidenceBundleForChangeSet(gitChangeSet)
            diff != null -> reviewService.buildEvidenceBundleForDiff(diff, selectedDiffItemIds)
            else -> reviewService.buildEvidenceBundle(emptyList<String>())
        }
        return ReviewEvidenceBuildResult(
            bundle = bundle,
            warnings = selection?.warnings.orEmpty(),
        )
    }

    private fun GraphDiff.selection(selectedDiffItemIds: List<String>): ReviewDiffSelection {
        val hasSelection = selectedDiffItemIds.isNotEmpty()
        val selectedEntries = if (hasSelection) {
            entries.filter { entry -> entry.elementId in selectedDiffItemIds }
        } else {
            entries
        }
        val selectedPaths = if (selectedEntries.isEmpty() && hasSelection) {
            emptyList()
        } else {
            GraphDiffChangedFileExtractor.changedFiles(copy(entries = selectedEntries))
        }
        val warnings = buildList {
            if (hasSelection && selectedEntries.isEmpty()) {
                add("选中的差异条目未命中当前 diff，已限定为所选 diff evidence，不再回退到全量工作区。")
            } else if (hasSelection && selectedPaths.isEmpty()) {
                add("选中的差异条目无法解析到工作区文件，已限定为所选 diff evidence，不再回退到全量工作区。")
            }
        }
        return ReviewDiffSelection(
            hasSelection = hasSelection,
            selectedPaths = selectedPaths,
            warnings = warnings,
        )
    }
}

internal data class ReviewEvidenceBuildResult(
    val bundle: ReviewEvidenceBundle,
    val warnings: List<String> = emptyList(),
)

private data class ReviewDiffSelection(
    val hasSelection: Boolean,
    val selectedPaths: List<String>,
    val warnings: List<String> = emptyList(),
)
