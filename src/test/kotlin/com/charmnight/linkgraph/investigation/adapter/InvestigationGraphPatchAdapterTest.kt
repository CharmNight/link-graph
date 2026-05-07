package com.charmnight.linkgraph.investigation.adapter

import com.charmnight.linkgraph.investigation.domain.EvidenceFact
import com.charmnight.linkgraph.investigation.domain.EvidenceLevel
import com.charmnight.linkgraph.investigation.domain.InvestigationStatus
import com.charmnight.linkgraph.investigation.domain.InvestigationTurnResult
import com.charmnight.linkgraph.workbench.QaMode
import com.charmnight.linkgraph.workbench.QaRequestKind
import com.charmnight.linkgraph.workbench.ReplayableQaRequest
import kotlin.test.Test
import kotlin.test.assertTrue

class InvestigationGraphPatchAdapterTest {
    @Test
    fun deterministicInvestigationResultDoesNotExposePromptPreview() {
        val result = InvestigationGraphPatchAdapter().toGraphPatchResult(
            request = ReplayableQaRequest(
                requestId = "request-1",
                kind = QaRequestKind.INVESTIGATE_THREAD,
                question = "请继续取证：定位 TaskQueueEventType.ADD。",
                mode = QaMode.AUTO,
                sourceThreadId = "thread-add-event-type",
            ),
            turnResult = InvestigationTurnResult(
                threadId = "thread-add-event-type",
                status = InvestigationStatus.RESOLVED,
                acceptedFacts = listOf(
                    EvidenceFact(
                        factId = "fact-add",
                        level = EvidenceLevel.DIRECT_SOURCE_RESOLVED,
                        resolverId = "java-enum",
                        symbolSignature = "com.example.TaskQueueEventType.ADD",
                        filePath = "src/main/java/com/example/TaskQueueEventType.java",
                        startLine = 3,
                        endLine = 3,
                        claim = "已确认 ADD 枚举常量存在。",
                        whyResolved = "PSI 直接解析到枚举常量。",
                    ),
                ),
                candidates = emptyList(),
                requiredEvidence = emptyList(),
                outcomes = emptyList(),
                summary = "本轮已确认直接证据。",
            ),
        )

        assertTrue(result.promptPreview.isBlank())
    }
}
