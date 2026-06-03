package com.charmnight.linkgraph.workbench

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros

data class WorkbenchLayoutPreferencesState(
    var sectionPreferences: LinkedHashMap<String, Boolean> = linkedMapOf(),
) {
    fun sanitized(): WorkbenchLayoutPreferencesState {
        val normalized = linkedMapOf<String, Boolean>()
        sectionPreferences.forEach { (sectionId, expanded) ->
            if (sectionId in VALID_WORKBENCH_SECTION_IDS) {
                normalized[sectionId] = expanded
            }
        }
        return copy(sectionPreferences = normalized)
    }
}

private val VALID_WORKBENCH_SECTION_IDS = linkedSetOf(
    "explanation.step-list",
    "explanation.step-detail",
    "qa.request-status",
    "qa.thread",
    "qa.composer",
    "qa.candidate-changes",
    "qa.investigation-threads",
    "draft.change-list",
    "draft.note-list",
    "draft.detail",
)

@State(
    name = "LinkGraphWorkbenchLayoutPreferences",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
@Service(Service.Level.PROJECT)
class WorkbenchLayoutPreferencesService : PersistentStateComponent<WorkbenchLayoutPreferencesState> {
    private var state = WorkbenchLayoutPreferencesState()

    override fun getState(): WorkbenchLayoutPreferencesState = state

    override fun loadState(state: WorkbenchLayoutPreferencesState) {
        this.state = state.sanitized()
    }

    fun snapshot(): Map<String, Boolean> = LinkedHashMap(state.sanitized().sectionPreferences)

    fun update(sectionId: String, expanded: Boolean): Map<String, Boolean> {
        if (sectionId !in VALID_WORKBENCH_SECTION_IDS) {
            return snapshot()
        }
        val nextPreferences = LinkedHashMap(state.sanitized().sectionPreferences)
        nextPreferences[sectionId] = expanded
        state = WorkbenchLayoutPreferencesState(sectionPreferences = nextPreferences)
        return snapshot()
    }
}
