package com.charmnight.linkgraph.workbench

import kotlin.test.Test
import kotlin.test.assertEquals

class WorkbenchModelsTest {
    @Test
    fun `step and draft models expose stable ids and explicit kinds`() {
        val step = WorkbenchStep(
            stepId = "step-upload-2",
            title = "调用上传工具保存文件",
            granularity = StepGranularity.BUSINESS,
            kind = StepKind.BUSINESS_ACTION,
        )
        val change = CandidateDraftChange(
            changeId = "change-1",
            status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
        )

        assertEquals("step-upload-2", step.stepId)
        assertEquals(StepGranularity.BUSINESS, step.granularity)
        assertEquals(CandidateDraftChangeStatus.PENDING_CONFIRMATION, change.status)
    }
}
