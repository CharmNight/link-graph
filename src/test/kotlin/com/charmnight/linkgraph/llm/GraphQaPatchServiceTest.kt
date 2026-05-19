package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphPatchAction
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.GraphUncertainty
import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.workbench.QaConversationMessage
import com.charmnight.linkgraph.workbench.QaConversationSession
import com.charmnight.linkgraph.workbench.QaMessageRole
import com.charmnight.linkgraph.workbench.CandidatePatchIntentMode
import com.charmnight.linkgraph.workbench.InvestigationThread
import com.charmnight.linkgraph.workbench.InvestigationThreadStatus
import com.charmnight.linkgraph.workbench.InvestigationTurnOutcomeStatus
import com.charmnight.linkgraph.workbench.QaMode
import java.net.http.HttpTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphQaPatchServiceTest {
    @Test
    fun answerModeDropsRemoteCandidateChangesAndInvestigationThreads() {
        val gateway = object : LlmGateway {
            override fun generate(request: LlmRequest): LlmResponse {
                return LlmResponse(
                    content = """
                        {
                          "answer": "这是直接回答，但远程错误地附带了候选和风险。",
                          "findings": [
                            {
                              "id": "direct-answer",
                              "claim": "当前源码片段说明了触发入口。",
                              "evidenceLevel": "DIRECT_SOURCE",
                              "references": [
                                {
                                  "nodeId": "method:scheduled-task",
                                  "filePath": "src/main/java/com/example/Task.java",
                                  "startLine": 10,
                                  "endLine": 14
                                }
                              ]
                            }
                          ],
                          "candidateChanges": [
                            {
                              "changeId": "remote-change",
                              "status": "PENDING_CONFIRMATION",
                              "claimType": "CODE_FACT",
                              "title": "不应保留的修改",
                              "targetNodeIds": ["method:scheduled-task"],
                              "reason": "ANSWER 模式不允许候选变更",
                              "impactSummary": "不应进入草稿",
                              "supportingFindingIds": ["direct-answer"]
                            }
                          ],
                          "investigationThreads": [
                            {
                              "threadId": "remote-thread",
                              "status": "OPEN",
                              "claimType": "RISK_HINT",
                              "title": "不应保留的风险线程",
                              "targetNodeIds": ["method:scheduled-task"],
                              "summary": "ANSWER 模式不允许新风险线程",
                              "evidenceGap": "无",
                              "recommendedQuestion": "无",
                              "supportingFindingIds": ["direct-answer"]
                            }
                          ],
                          "warnings": [],
                          "patch": null
                        }
                    """.trimIndent(),
                    model = request.model,
                )
            }
        }

        val result = GraphQaPatchService(gateway = gateway).answer(
            context = GraphQaContext(
                factGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:scheduled-task",
                            type = NodeType.METHOD,
                            title = "Task.run",
                            sourceTag = GraphSourceTag.FACT,
                        ),
                    ),
                ),
                selectedNodeIds = listOf("method:scheduled-task"),
                sourceContext = listOf(
                    SourceSnippetContext(
                        nodeId = "method:scheduled-task",
                        filePath = "src/main/java/com/example/Task.java",
                        startLine = 10,
                        endLine = 14,
                        snippet = "@Scheduled(cron = \"0 * * * * ?\")\nvoid run() {}",
                    ),
                ),
            ),
            question = "这个方法是如何触发的？",
            requestedMode = QaMode.AUTO,
            effectiveMode = QaMode.ANSWER,
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://localhost:8080/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
        )

        assertEquals(QaMode.AUTO, result.requestedMode)
        assertEquals(QaMode.ANSWER, result.effectiveMode)
        assertTrue(result.answer.contains("直接回答"))
        assertTrue(result.findings.isNotEmpty())
        assertTrue(result.sourceContext.isNotEmpty())
        assertTrue(result.evidenceTrace.isNotEmpty())
        assertTrue(result.candidateChanges.isEmpty())
        assertTrue(result.newCandidateChanges.isEmpty())
        assertTrue(result.investigationThreads.isEmpty())
    }

    @Test
    fun reviewModeKeepsRiskThreadsButDoesNotPromoteThemToCandidateChanges() {
        val gateway = object : LlmGateway {
            override fun generate(request: LlmRequest): LlmResponse {
                return LlmResponse(
                    content = """
                        {
                          "answer": "风险复核发现一条风险线程，但不应进入候选变更。",
                          "findings": [
                            {
                              "id": "direct-risk",
                              "claim": "当前图里能直接观察到待复核分支。",
                              "evidenceLevel": "DIRECT_GRAPH",
                              "references": [
                                {
                                  "nodeId": "method:review-target"
                                }
                              ]
                            }
                          ],
                          "candidateChanges": [
                            {
                              "changeId": "remote-review-change",
                              "status": "PENDING_CONFIRMATION",
                              "claimType": "CODE_FACT",
                              "title": "REVIEW 模式不应保留的候选",
                              "targetNodeIds": ["method:review-target"],
                              "reason": "REVIEW 模式只复核风险",
                              "impactSummary": "不应进入草稿",
                              "supportingFindingIds": ["direct-risk"]
                            }
                          ],
                          "investigationThreads": [
                            {
                              "threadId": "thread-direct-risk",
                              "status": "OPEN",
                              "claimType": "RISK_HINT",
                              "title": "待复核风险",
                              "targetNodeIds": ["method:review-target"],
                              "summary": "当前有直接图证据，但仍只作为风险线程展示。",
                              "evidenceGap": "需要用户确认是否调整代码。",
                              "recommendedQuestion": "需要修改时请切换到代码调整模式。",
                              "supportingFindingIds": ["direct-risk"]
                            }
                          ],
                          "warnings": [],
                          "patch": null
                        }
                    """.trimIndent(),
                    model = request.model,
                )
            }
        }

        val result = GraphQaPatchService(gateway = gateway).answer(
            context = GraphQaContext(
                factGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:review-target",
                            type = NodeType.METHOD,
                            title = "ReviewTarget.handle",
                            sourceTag = GraphSourceTag.FACT,
                        ),
                    ),
                ),
                selectedNodeIds = listOf("method:review-target"),
            ),
            question = "这里有没有问题？",
            requestedMode = QaMode.AUTO,
            effectiveMode = QaMode.REVIEW,
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://localhost:8080/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
            session = QaConversationSession(
                sessionId = "qa-review",
                scopeKey = "method:review-target",
            ),
            sourceThreadId = null,
        )

        assertEquals(QaMode.REVIEW, result.effectiveMode)
        assertTrue(result.investigationThreads.isNotEmpty())
        assertTrue(result.candidateChanges.isEmpty())
        assertTrue(result.newCandidateChanges.isEmpty())
    }

    @Test
    fun changeModeKeepsCandidateChangesAndRiskThreads() {
        val gateway = object : LlmGateway {
            override fun generate(request: LlmRequest): LlmResponse {
                return LlmResponse(
                    content = """
                        {
                          "answer": "代码调整模式保留候选变更，同时保留需要继续核对的风险线程。",
                          "findings": [
                            {
                              "id": "direct-change",
                              "claim": "当前源码片段直接锚定到可修改方法。",
                              "evidenceLevel": "DIRECT_SOURCE",
                              "references": [
                                {
                                  "nodeId": "method:change-target",
                                  "filePath": "src/main/java/com/example/ChangeTarget.java",
                                  "startLine": 8,
                                  "endLine": 14
                                }
                              ]
                            },
                            {
                              "id": "direct-risk",
                              "claim": "当前图里仍存在一个需要人工复核的分支。",
                              "evidenceLevel": "DIRECT_GRAPH",
                              "references": [
                                {
                                  "nodeId": "method:risk-target"
                                }
                              ]
                            }
                          ],
                          "candidateChanges": [
                            {
                              "changeId": "change-guard",
                              "status": "PENDING_CONFIRMATION",
                              "claimType": "CODE_FACT",
                              "title": "补充空值保护",
                              "targetNodeIds": ["method:change-target"],
                              "reason": "CHANGE 模式允许直接证据支撑的候选变更。",
                              "impactSummary": "避免空指针。",
                              "supportingFindingIds": ["direct-change"]
                            }
                          ],
                          "investigationThreads": [
                            {
                              "threadId": "thread-review-branch",
                              "status": "OPEN",
                              "claimType": "RISK_HINT",
                              "title": "继续核对异常分支",
                              "targetNodeIds": ["method:risk-target"],
                              "summary": "该分支仍需要保留为风险线程。",
                              "evidenceGap": "需要确认真实业务约束。",
                              "recommendedQuestion": "继续取证异常分支。",
                              "supportingFindingIds": ["direct-risk"]
                            }
                          ],
                          "warnings": [],
                          "patch": null
                        }
                    """.trimIndent(),
                    model = request.model,
                )
            }
        }

        val result = GraphQaPatchService(gateway = gateway).answer(
            context = GraphQaContext(
                factGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:change-target",
                            type = NodeType.METHOD,
                            title = "ChangeTarget.handle",
                            signature = "com.example.ChangeTarget.handle():void",
                            sourceTag = GraphSourceTag.FACT,
                            metadata = mapOf(
                                "source.filePath" to "src/main/java/com/example/ChangeTarget.java",
                                "source.startLine" to "8",
                                "source.endLine" to "14",
                            ),
                        ),
                        GraphNode(
                            id = "method:risk-target",
                            type = NodeType.METHOD,
                            title = "RiskTarget.handle",
                            sourceTag = GraphSourceTag.FACT,
                        ),
                    ),
                ),
                selectedNodeIds = listOf("method:change-target", "method:risk-target"),
                sourceContext = listOf(
                    SourceSnippetContext(
                        nodeId = "method:change-target",
                        filePath = "src/main/java/com/example/ChangeTarget.java",
                        startLine = 8,
                        endLine = 14,
                        snippet = "void handle() { service.call(); }",
                    ),
                ),
            ),
            question = "请调整这里的空值保护",
            requestedMode = QaMode.AUTO,
            effectiveMode = QaMode.CHANGE,
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://localhost:8080/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
        )

        assertEquals(QaMode.AUTO, result.requestedMode)
        assertEquals(QaMode.CHANGE, result.effectiveMode)
        assertEquals(1, result.candidateChanges.size)
        assertEquals("change-guard", result.candidateChanges.single().changeId)
        assertEquals(1, result.newCandidateChanges.size)
        assertEquals(1, result.investigationThreads.size)
        assertEquals("thread-review-branch", result.investigationThreads.single().threadId)
    }

    @Test
    fun buildsMockQaAnswerAndPatchPreview() {
        val result = GraphQaPatchService().answer(
            context = GraphQaContext(
                factGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:order-service-place",
                            type = NodeType.METHOD,
                            title = "OrderService.place",
                            sourceTag = GraphSourceTag.FACT,
                        ),
                        GraphNode(
                            id = "uncertain:channel-router",
                            type = NodeType.UNCERTAIN_LINK,
                            title = "ChannelStrategyRouter.resolve",
                            uncertainty = GraphUncertainty(reason = "运行时字符串路由"),
                            sourceTag = GraphSourceTag.UNCERTAIN_FACT,
                        ),
                    ),
                ),
                selectedNodeIds = listOf("uncertain:channel-router"),
                sourceContext = listOf(
                    SourceSnippetContext(
                        nodeId = "uncertain:channel-router",
                        filePath = "src/main/java/com/example/ChannelStrategyRouter.java",
                        startLine = 18,
                        endLine = 26,
                        snippet = "return strategy == null ? fallback() : strategy.resolve();",
                    ),
                ),
            ),
            question = "请围绕当前范围进行问答：这段链路是否遗漏了默认兜底逻辑？",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.MOCK.id,
            ),
        )

        assertEquals(LlmResultSource.LOCAL_RULE, result.source)
        assertTrue(result.answer.contains("当前轮结论"))
        assertTrue(result.answer.contains("处理建议"))
        assertTrue(result.answer.contains("默认兜底"))
        assertTrue(result.promptPreview.contains("当前范围"))
        assertEquals(null, result.patch)
        assertTrue(result.findings.any { finding ->
            finding.evidenceLevel == ResultEvidenceLevel.NOT_OBSERVED
        })
        assertTrue(result.candidateChanges.isEmpty())
        assertEquals(1, result.investigationThreads.size)
        assertTrue(result.investigationThreads.first().title.contains("默认兜底"))
        assertEquals("RISK_HINT", result.investigationThreads.first().claimType)
        assertEquals(
            ResultEvidenceLevel.NOT_OBSERVED,
            result.investigationThreads.first().evidence.firstOrNull()?.evidenceLevel,
        )
        assertEquals(1, result.sourceContext.size)
        assertEquals(1, result.evidenceTrace.size)
        assertEquals("本轮问答直接附带的源码片段", result.evidenceTrace.first().reason)
        assertNotNull(result.qaSession)
        assertEquals(2, result.qaSession.messages.size)
        assertEquals(QaMessageRole.USER, result.qaSession.messages.first().role)
        assertEquals(QaMessageRole.ASSISTANT, result.qaSession.messages.last().role)
    }

    @Test
    fun explanationStyleQuestionReturnsExplanationWithoutGenericCandidateChangesInMockMode() {
        val result = GraphQaPatchService().answer(
            context = GraphQaContext(
                factGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:upload-file",
                            type = NodeType.METHOD,
                            title = "CommonController.uploadFile",
                            sourceTag = GraphSourceTag.FACT,
                        ),
                        GraphNode(
                            id = "flow-action:transfer-to-upload-utils",
                            type = NodeType.FLOW_ACTION,
                            title = "FileUploadUtils.upload",
                            sourceTag = GraphSourceTag.FACT,
                        ),
                    ),
                    edges = listOf(
                        GraphEdge(
                            id = "call:upload-file->upload-utils",
                            type = EdgeType.CALL,
                            fromNodeId = "method:upload-file",
                            toNodeId = "flow-action:transfer-to-upload-utils",
                        ),
                    ),
                ),
                selectedNodeIds = listOf("method:upload-file"),
            ),
            question = "介绍下这个链路",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.MOCK.id,
            ),
        )

        assertEquals(LlmResultSource.LOCAL_RULE, result.source)
        assertTrue(result.answer.contains("当前范围说明"))
        assertTrue(result.answer.contains("CommonController.uploadFile"))
        assertTrue(result.candidateChanges.isEmpty())
        assertTrue(result.findings.isNotEmpty())
    }

    @Test
    fun explicitChangeRequestWithDirectSourceEvidenceBuildsCandidateChangeInMockMode() {
        val result = GraphQaPatchService().answer(
            context = GraphQaContext(
                factGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:file-download",
                            type = NodeType.METHOD,
                            title = "CommonController.fileDownload",
                            signature = "com.example.CommonController.fileDownload(java.lang.String, java.lang.Boolean):void",
                            sourceTag = GraphSourceTag.FACT,
                            metadata = mapOf(
                                "source.filePath" to "src/main/java/com/example/CommonController.java",
                                "source.startLine" to "42",
                                "source.endLine" to "88",
                            ),
                        ),
                    ),
                ),
                selectedNodeIds = listOf("method:file-download"),
                sourceContext = listOf(
                    SourceSnippetContext(
                        nodeId = "method:file-download",
                        filePath = "src/main/java/com/example/CommonController.java",
                        startLine = 42,
                        endLine = 88,
                        snippet = """
                        public void fileDownload(String fileName, Boolean delete) {
                            if (delete) {
                                FileUtils.deleteFile(filePath);
                            }
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
            question = "请把这里的 if(delete) 改成 delete == true，并在删除前校验 filePath 是否存在。",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.MOCK.id,
            ),
        )

        assertEquals(LlmResultSource.LOCAL_RULE, result.source)
        assertEquals(1, result.candidateChanges.size)
        assertTrue(result.investigationThreads.isEmpty())
        assertTrue(result.answer.contains("待确认变更"))
        assertTrue(result.findings.all { finding -> finding.evidenceLevel == ResultEvidenceLevel.DIRECT_SOURCE })
        assertEquals("method:file-download", result.candidateChanges.single().targetNodeIds.single())
        assertEquals("CODE_FACT", result.candidateChanges.single().claimType)
        assertEquals(1, result.candidateChanges.single().editScopes.size)
        assertEquals(
            "src/main/java/com/example/CommonController.java",
            result.candidateChanges.single().editScopes.single().filePath,
        )
    }

    @Test
    fun analysisStyleQuestionDoesNotPromoteDirectSourceEvidenceToCandidateChangeInMockMode() {
        val result = GraphQaPatchService().answer(
            context = GraphQaContext(
                factGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:file-download",
                            type = NodeType.METHOD,
                            title = "CommonController.fileDownload",
                            signature = "com.example.CommonController.fileDownload(java.lang.String, java.lang.Boolean):void",
                            sourceTag = GraphSourceTag.FACT,
                            metadata = mapOf(
                                "source.filePath" to "src/main/java/com/example/CommonController.java",
                                "source.startLine" to "42",
                                "source.endLine" to "88",
                            ),
                        ),
                    ),
                ),
                selectedNodeIds = listOf("method:file-download"),
                sourceContext = listOf(
                    SourceSnippetContext(
                        nodeId = "method:file-download",
                        filePath = "src/main/java/com/example/CommonController.java",
                        startLine = 42,
                        endLine = 88,
                        snippet = """
                        public void fileDownload(String fileName, Boolean delete) {
                            if (delete) {
                                FileUtils.deleteFile(filePath);
                            }
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
            question = "这里为什么要修改 delete 分支？请结合当前代码解释一下。",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.MOCK.id,
            ),
        )

        assertEquals(LlmResultSource.LOCAL_RULE, result.source)
        assertTrue(result.candidateChanges.isEmpty())
        assertEquals(1, result.investigationThreads.size)
        assertTrue(result.findings.all { finding -> finding.evidenceLevel == ResultEvidenceLevel.NOT_OBSERVED })
        assertTrue(result.answer.contains("风险线索"))
    }

    @Test
    fun requestsRemoteQaWhenOpenAiCompatibleProviderIsReady() {
        val gateway = object : LlmGateway {
            override fun generate(request: LlmRequest): LlmResponse {
                return LlmResponse(
                    content = """
                        {
                          "answer": "远程问答建议补一个默认兜底说明节点。",
                          "findings": [
                            {
                              "id": "fallback-missing",
                              "claim": "当前上下文没有直接观察到默认兜底分支。",
                              "evidenceLevel": "NOT_OBSERVED",
                              "references": [
                                {
                                  "nodeId": "method:order-service-place"
                                }
                              ]
                            }
                          ],
                          "candidateChanges": [
                            {
                              "changeId": "remote-risk-hint",
                              "status": "PENDING_CONFIRMATION",
                              "claimType": "RISK_HINT",
                              "title": "补一个默认兜底说明节点",
                              "targetNodeIds": ["method:order-service-place"],
                              "beforeState": "当前没有默认兜底说明",
                              "afterState": "补充说明这里只缺观测，不是已证实代码事实",
                              "reason": "当前没有直接观察到默认兜底分支。",
                              "impactSummary": "会影响这条问答建议的真实性边界。",
                              "supportingFindingIds": ["fallback-missing"]
                            }
                          ],
                          "warnings": ["远程回答只作为候选建议。"],
                          "patch": null
                        }
                    """.trimIndent(),
                    model = request.model,
                )
            }
        }

        val result = GraphQaPatchService(gateway = gateway).answer(
            context = GraphQaContext(
                factGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:order-service-place",
                            type = NodeType.METHOD,
                            title = "OrderService.place",
                            sourceTag = GraphSourceTag.FACT,
                        ),
                    ),
                ),
            ),
            question = "请复核整图是否遗漏默认兜底逻辑？",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://localhost:8080/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
        )

        assertEquals(LlmResultSource.REMOTE, result.source)
        assertTrue(result.answer.contains("默认兜底"))
        assertTrue(result.findings.any { finding ->
            finding.evidenceLevel == ResultEvidenceLevel.NOT_OBSERVED &&
                finding.references.any { reference -> reference.nodeId == "method:order-service-place" }
        })
        assertTrue(result.warnings.any { it.contains("候选建议") })
        assertEquals(null, result.patch)
        assertTrue(result.candidateChanges.isEmpty())
        assertEquals("补一个默认兜底说明节点", result.investigationThreads.firstOrNull()?.title)
        assertEquals("RISK_HINT", result.investigationThreads.firstOrNull()?.claimType)
        assertEquals("fallback-missing", result.investigationThreads.firstOrNull()?.evidence?.firstOrNull()?.id)
        assertEquals(
            ResultEvidenceLevel.NOT_OBSERVED,
            result.investigationThreads.firstOrNull()?.evidence?.firstOrNull()?.evidenceLevel,
        )
    }

    @Test
    fun remoteCandidateChangePreservesStructuredGraphPatch() {
        val gateway = object : LlmGateway {
            override fun generate(request: LlmRequest): LlmResponse {
                return LlmResponse(
                    content = """
                        {
                          "answer": "建议把上传路径固定为 /data/upload，并在图上展示调整说明。",
                          "findings": [
                            {
                              "id": "upload-path-found",
                              "claim": "当前源码里直接能看到上传路径常量。",
                              "evidenceLevel": "DIRECT_SOURCE",
                              "references": [
                                {
                                  "nodeId": "flow-action:upload-condition"
                                }
                              ]
                            }
                          ],
                          "candidateChanges": [
                            {
                              "changeId": "change-upload-path",
                              "status": "PENDING_CONFIRMATION",
                              "claimType": "CODE_FACT",
                              "title": "修改上传路径固定值",
                              "targetNodeIds": ["flow-action:upload-condition"],
                              "beforeState": "旧路径常量",
                              "afterState": "固定为 /data/upload",
                              "reason": "旧路径已经废弃。",
                              "impactSummary": "上传流程写入新目录。",
                              "supportingFindingIds": ["upload-path-found"],
                              "graphPatch": {
                                "summary": "补充上传路径调整说明节点",
                                "operations": [
                                  {
                                    "id": "patch-op-upload-note",
                                    "action": "ADD_ANNOTATION",
                                    "elementKind": "NODE",
                                    "elementId": "draft-note:change-upload-path",
                                    "title": "新增路径调整说明节点",
                                    "node": {
                                      "id": "draft-note:change-upload-path",
                                      "type": "DOC_PAGE",
                                      "title": "上传路径改为 /data/upload",
                                      "doc": "原路径已废弃，固定改为 /data/upload。",
                                      "sourceTag": "DRAFT_AI"
                                    }
                                  }
                                ],
                                "addedNodeIds": ["draft-note:change-upload-path"]
                              }
                            }
                          ],
                          "warnings": [],
                          "patch": null
                        }
                    """.trimIndent(),
                    model = request.model,
                )
            }
        }

        val result = GraphQaPatchService(gateway = gateway).answer(
            context = GraphQaContext(
                factGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "flow-action:upload-condition",
                            type = NodeType.FLOW_ACTION,
                            title = "上传条件判断",
                            sourceTag = GraphSourceTag.FACT,
                        ),
                    ),
                ),
            ),
            question = "请把上传路径调整方案写成可编辑图",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://localhost:8080/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
        )

        assertEquals(LlmResultSource.REMOTE, result.source)
        assertTrue(result.investigationThreads.isEmpty())
        assertEquals(1, result.candidateChanges.size)
        assertEquals("change-upload-path", result.candidateChanges.first().changeId)
        assertEquals("补充上传路径调整说明节点", result.candidateChanges.first().graphPatch?.summary)
        assertEquals("patch-op-upload-note", result.candidateChanges.first().graphPatch?.operations?.firstOrNull()?.id)
        assertEquals(GraphPatchAction.ADD_ANNOTATION, result.candidateChanges.first().graphPatch?.operations?.firstOrNull()?.action)
        assertEquals(GraphDiffElementKind.NODE, result.candidateChanges.first().graphPatch?.operations?.firstOrNull()?.elementKind)
        assertEquals("draft-note:change-upload-path", result.candidateChanges.first().graphPatch?.addedNodeIds?.single())
        assertNull(result.patch)
    }

    @Test
    fun remoteCandidateChangeParsesExplicitPatchIntentWithoutGraphPatch() {
        val gateway = object : LlmGateway {
            override fun generate(request: LlmRequest): LlmResponse {
                return LlmResponse(
                    content = """
                        {
                          "answer": "建议在删除前增加文件存在性判断。",
                          "findings": [
                            {
                              "id": "delete-flow-found",
                              "claim": "当前源码里直接能看到删除动作和后续 return。",
                              "evidenceLevel": "DIRECT_SOURCE",
                              "references": [
                                {
                                  "nodeId": "action:delete-file"
                                }
                              ]
                            }
                          ],
                          "candidateChanges": [
                            {
                              "changeId": "change-insert-file-exists-guard",
                              "status": "PENDING_CONFIRMATION",
                              "claimType": "STRUCTURAL_SUGGESTION",
                              "title": "在删除前增加文件存在性判断",
                              "targetNodeIds": ["action:delete-file", "terminal:return"],
                              "beforeState": "直接执行删除动作",
                              "afterState": "if (fileExists(filePath))",
                              "reason": "文件不存在时应跳过删除。",
                              "impactSummary": "新增一个显式决策节点。",
                              "supportingFindingIds": ["delete-flow-found"],
                              "patchIntent": {
                                "mode": "INSERT_NEW_DECISION",
                                "attachEdgeId": "edge:entry-delete",
                                "falseBranchTargetNodeId": "terminal:return"
                              }
                            }
                          ],
                          "warnings": [],
                          "patch": null
                        }
                    """.trimIndent(),
                    model = request.model,
                )
            }
        }

        val result = GraphQaPatchService(gateway = gateway).answer(
            context = GraphQaContext(
                factGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:file-download",
                            type = NodeType.METHOD,
                            title = "CommonController.fileDownload",
                            sourceTag = GraphSourceTag.FACT,
                        ),
                        GraphNode(
                            id = "action:delete-file",
                            type = NodeType.FLOW_ACTION,
                            title = "FileUtils.deleteFile(filePath)",
                            sourceTag = GraphSourceTag.FACT,
                            metadata = mapOf("flowchart.kind" to "PROCESS"),
                        ),
                        GraphNode(
                            id = "terminal:return",
                            type = NodeType.TERMINAL,
                            title = "return",
                            sourceTag = GraphSourceTag.FACT,
                            metadata = mapOf("flowchart.kind" to "TERMINAL"),
                        ),
                    ),
                    edges = listOf(
                        GraphEdge(
                            id = "edge:entry-delete",
                            type = EdgeType.CONTROL_FLOW,
                            fromNodeId = "method:file-download",
                            toNodeId = "action:delete-file",
                            sourceTag = GraphSourceTag.FACT,
                        ),
                    ),
                ),
            ),
            question = "请把删除前增加存在性判断的方案写成可编辑图。",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://localhost:8080/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
        )

        val change = result.candidateChanges.single()
        assertEquals(CandidatePatchIntentMode.INSERT_NEW_DECISION, change.patchIntent?.mode)
        assertEquals("edge:entry-delete", change.patchIntent?.attachEdgeId)
        assertEquals("terminal:return", change.patchIntent?.falseBranchTargetNodeId)
        assertNotNull(change.graphPatch)
        assertEquals(1, change.graphPatch?.addedNodeIds?.size)
        assertTrue(change.graphPatch?.operations?.any { operation -> operation.action == GraphPatchAction.ADD_NODE } == true)
    }

    @Test
    fun `follow-up qa with source thread id merges weak evidence back into the original risk thread`() {
        val gateway = object : LlmGateway {
            override fun generate(request: LlmRequest): LlmResponse {
                return LlmResponse(
                    content = """
                        {
                          "answer": "继续取证后，仍缺少 upload 实现里的直接源码证据。",
                          "findings": [
                            {
                              "id": "upload-impl-missing",
                              "claim": "当前仍未直接观察到路径校验实现。",
                              "evidenceLevel": "NOT_OBSERVED",
                              "references": [
                                {
                                  "nodeId": "flow-action:upload"
                                }
                              ]
                            }
                          ],
                          "investigationThreads": [
                            {
                              "threadId": "thread-upload-follow-up",
                              "status": "OPEN",
                              "claimType": "RISK_HINT",
                              "title": "上传路径校验仍待确认",
                              "targetNodeIds": ["flow-action:upload"],
                              "summary": "继续下钻后，仍然缺少直接源码证据。",
                              "evidenceGap": "没有看到真正执行落盘前的路径校验。",
                              "recommendedQuestion": "请继续取证：定位真正执行文件落盘前的路径校验逻辑。",
                              "supportingFindingIds": ["upload-impl-missing"]
                            }
                          ],
                          "warnings": [],
                          "patch": null
                        }
                    """.trimIndent(),
                    model = request.model,
                )
            }
        }

        val result = GraphQaPatchService(gateway = gateway).answer(
            context = GraphQaContext(
                factGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "flow-action:upload",
                            type = NodeType.FLOW_ACTION,
                            title = "FileUploadUtils.upload",
                            sourceTag = GraphSourceTag.FACT,
                        ),
                    ),
                    edges = listOf(
                        GraphEdge(
                            id = "call:controller->upload",
                            type = EdgeType.CALL,
                            fromNodeId = "method:upload-file",
                            toNodeId = "flow-action:upload",
                        ),
                    ),
                ),
                selectedNodeIds = listOf("flow-action:upload"),
                sourceContext = listOf(
                    SourceSnippetContext(
                        nodeId = "flow-action:upload",
                        filePath = "src/main/java/com/example/FileUploadUtils.java",
                        startLine = 88,
                        endLine = 126,
                        snippet = "public void upload(...) { transferTo(target); }",
                    ),
                ),
            ),
            question = "请继续取证：定位 upload 实现中的路径校验逻辑。",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://localhost:8080/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
            session = QaConversationSession(
                sessionId = "qa-upload",
                scopeKey = "flow-action:upload",
                messages = listOf(
                    QaConversationMessage(
                        messageId = "qa-upload-user-1",
                        role = QaMessageRole.USER,
                        content = "请继续取证：展开 upload 实现。",
                        focusTargetId = "thread-upload-risk",
                    ),
                ),
                investigationThreads = listOf(
                    InvestigationThread(
                        threadId = "thread-upload-risk",
                        status = InvestigationThreadStatus.OPEN,
                        title = "上传路径风险待确认",
                        targetNodeIds = listOf("flow-action:upload"),
                        summary = "当前只看到上传入口。",
                        evidenceGap = "还没有看到上传实现里的路径校验。",
                        recommendedQuestion = "请继续取证：展开 upload 实现。",
                        claimType = "RISK_HINT",
                        evidence = listOf(
                            ResultEvidenceFinding(
                                id = "upload-callsite",
                                claim = "这里只能看到上传调用点。",
                                evidenceLevel = ResultEvidenceLevel.CALLSITE_ONLY,
                                references = listOf(ResultEvidenceReference(nodeId = "flow-action:upload")),
                            ),
                        ),
                    ),
                ),
                focusTargetId = "thread-upload-risk",
            ),
            sourceThreadId = "thread-upload-risk",
        )

        assertEquals(1, result.investigationThreads.size)
        assertEquals("thread-upload-risk", result.investigationThreads.single().threadId)
        assertEquals("上传路径校验仍待确认", result.investigationThreads.single().title)
        assertEquals(2, result.investigationThreads.single().evidence.size)
        assertEquals(InvestigationTurnOutcomeStatus.OPEN_WITH_PROGRESS, result.latestTurnOutcome?.status)
        assertEquals("thread-upload-risk", result.qaSession?.focusTargetId)
    }

    @Test
    fun `direct evidence candidate derives edit scope from source reference`() {
        val gateway = object : LlmGateway {
            override fun generate(request: LlmRequest): LlmResponse {
                return LlmResponse(
                    content = """
                        {
                          "answer": "这里可以直接确认 fileDownload 方法需要调整。",
                          "findings": [
                            {
                              "id": "download-source",
                              "claim": "当前源码里直接能看到 fileDownload 方法。",
                              "evidenceLevel": "DIRECT_SOURCE",
                              "references": [
                                {
                                  "nodeId": "method:file-download",
                                  "filePath": "src/main/java/com/example/CommonController.java",
                                  "startLine": 42,
                                  "endLine": 88
                                }
                              ]
                            }
                          ],
                          "candidateChanges": [
                            {
                              "changeId": "change-file-download",
                              "status": "PENDING_CONFIRMATION",
                              "claimType": "CODE_FACT",
                              "title": "调整 fileDownload 里的路径规则",
                              "targetNodeIds": ["method:file-download"],
                              "reason": "当前源码里直接可见。",
                              "impactSummary": "影响下载路径解析。",
                              "supportingFindingIds": ["download-source"]
                            }
                          ],
                          "warnings": [],
                          "patch": null
                        }
                    """.trimIndent(),
                    model = request.model,
                )
            }
        }

        val result = GraphQaPatchService(gateway = gateway).answer(
            context = GraphQaContext(
                factGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:file-download",
                            type = NodeType.METHOD,
                            title = "CommonController.fileDownload",
                            signature = "com.example.CommonController.fileDownload(java.lang.String):void",
                            sourceTag = GraphSourceTag.FACT,
                            metadata = mapOf(
                                "source.filePath" to "src/main/java/com/example/CommonController.java",
                                "source.startOffset" to "1200",
                                "source.endOffset" to "1640",
                            ),
                        ),
                    ),
                ),
                selectedNodeIds = listOf("method:file-download"),
                sourceContext = listOf(
                    SourceSnippetContext(
                        nodeId = "method:file-download",
                        filePath = "src/main/java/com/example/CommonController.java",
                        startOffset = 1200,
                        endOffset = 1640,
                        startLine = 42,
                        endLine = 88,
                        snippet = "public void fileDownload(String baseUrl) { return; }",
                    ),
                ),
            ),
            question = "请确认这里的路径规则是否需要调整？",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://localhost:8080/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
        )

        assertEquals(1, result.candidateChanges.size)
        assertEquals(1, result.candidateChanges.single().editScopes.size)
        assertEquals(
            "src/main/java/com/example/CommonController.java",
            result.candidateChanges.single().editScopes.single().filePath,
        )
        assertEquals(
            "com.example.CommonController.fileDownload(java.lang.String):void",
            result.candidateChanges.single().editScopes.single().symbolSignature,
        )
    }

    @Test
    fun dropsRemoteCandidateChangesThatDoNotReferenceAnyFindings() {
        val gateway = object : LlmGateway {
            override fun generate(request: LlmRequest): LlmResponse {
                return LlmResponse(
                    content = """
                        {
                          "answer": "建议补一条风险说明。",
                          "findings": [
                            {
                              "id": "upload-callsite",
                              "claim": "这里只能看到上传工具调用点。",
                              "evidenceLevel": "CALLSITE_ONLY",
                              "references": [
                                {
                                  "nodeId": "method:upload-file"
                                }
                              ]
                            }
                          ],
                          "candidateChanges": [
                            {
                              "changeId": "ungrounded-suggestion",
                              "status": "PENDING_CONFIRMATION",
                              "claimType": "RISK_HINT",
                              "title": "直接判定存在目录逃逸",
                              "targetNodeIds": ["method:upload-file"],
                              "reason": "模型主观猜测。",
                              "impactSummary": "这条建议不应该被保留。"
                            }
                          ],
                          "warnings": [],
                          "patch": null
                        }
                    """.trimIndent(),
                    model = request.model,
                )
            }
        }

        val result = GraphQaPatchService(gateway = gateway).answer(
            context = GraphQaContext(
                factGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:upload-file",
                            type = NodeType.METHOD,
                            title = "CommonController.uploadFile",
                            sourceTag = GraphSourceTag.FACT,
                        ),
                    ),
                ),
            ),
            question = "这里是不是有问题？",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://localhost:8080/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
        )

        assertEquals(LlmResultSource.REMOTE, result.source)
        assertTrue(result.answer.contains("风险说明"))
        assertTrue(result.findings.any { it.id == "upload-callsite" })
        assertTrue(result.candidateChanges.isEmpty())
    }

    @Test
    fun keepsDirectEvidenceChangesAsCandidateChanges() {
        val gateway = object : LlmGateway {
            override fun generate(request: LlmRequest): LlmResponse {
                return LlmResponse(
                    content = """
                        {
                          "answer": "当前直接源码能确认上传条件判断错误。",
                          "findings": [
                            {
                              "id": "upload-direct-source",
                              "claim": "源码里直接能看到上传条件判断写成了 a > 10。",
                              "evidenceLevel": "DIRECT_SOURCE",
                              "references": [
                                {
                                  "nodeId": "flow-action:upload-condition"
                                }
                              ]
                            }
                          ],
                          "candidateChanges": [
                            {
                              "changeId": "change-upload-condition",
                              "status": "PENDING_CONFIRMATION",
                              "claimType": "CODE_FACT",
                              "title": "修正上传条件判断",
                              "targetNodeIds": ["flow-action:upload-condition"],
                              "beforeState": "if (a > 10)",
                              "afterState": "if (a < 100)",
                              "reason": "当前源码里直接能看到条件判断错误。",
                              "impactSummary": "会影响上传分支走向。",
                              "supportingFindingIds": ["upload-direct-source"]
                            }
                          ],
                          "warnings": [],
                          "patch": null
                        }
                    """.trimIndent(),
                    model = request.model,
                )
            }
        }

        val result = GraphQaPatchService(gateway = gateway).answer(
            context = GraphQaContext(
                factGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "flow-action:upload-condition",
                            type = NodeType.FLOW_ACTION,
                            title = "上传条件判断",
                            sourceTag = GraphSourceTag.FACT,
                        ),
                    ),
                ),
            ),
            question = "请围绕这里的条件判断进行问答，判断是否有误？",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://localhost:8080/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
        )

        assertEquals(1, result.candidateChanges.size)
        assertTrue(result.investigationThreads.isEmpty())
        assertEquals("change-upload-condition", result.candidateChanges.first().changeId)
    }

    @Test
    fun rewritesStructuralSuggestionFromTryScopeToDecisionNode() {
        val gateway = object : LlmGateway {
            override fun generate(request: LlmRequest): LlmResponse {
                return LlmResponse(
                    content = """
                        {
                          "answer": "建议收紧删除条件，在删除前校验文件存在。",
                          "findings": [
                            {
                              "id": "delete-guard-source",
                              "claim": "当前源码里直接能看到删除分支 if (delete)。",
                              "evidenceLevel": "DIRECT_SOURCE",
                              "references": [
                                {
                                  "nodeId": "scope:file-download-if"
                                }
                              ]
                            }
                          ],
                          "candidateChanges": [
                            {
                              "changeId": "change-delete-guard",
                              "status": "PENDING_CONFIRMATION",
                              "claimType": "STRUCTURAL_SUGGESTION",
                              "title": "收紧删除条件并在删除前校验文件存在",
                              "targetNodeIds": ["scope:file-download-try"],
                              "beforeState": "if (delete)",
                              "afterState": "if (Boolean.TRUE.equals(delete) && fileExists(filePath))",
                              "reason": "delete 为包装类型，且删除前缺少文件存在校验。",
                              "impactSummary": "删除分支需要更严格的进入条件。",
                              "supportingFindingIds": ["delete-guard-source"],
                              "graphPatch": {
                                "summary": "更新当前 try 作用域节点中的删除分支逻辑",
                                "operations": [
                                  {
                                    "id": "patch-op-update-try",
                                    "action": "UPDATE_NODE",
                                    "elementKind": "NODE",
                                    "elementId": "scope:file-download-try",
                                    "title": "更新 try 作用域节点",
                                    "summary": "把 if (delete) 收紧为显式 true 判断并补充文件存在校验。",
                                    "metadata": {
                                      "draft.claimType": "STRUCTURAL_SUGGESTION"
                                    },
                                    "node": {
                                      "id": "scope:file-download-try",
                                      "type": "FLOW_SCOPE",
                                      "title": "try",
                                      "doc": "删除分支逻辑更新为更严格的条件。",
                                      "sourceTag": "DRAFT_AI"
                                    }
                                  }
                                ]
                              }
                            }
                          ],
                          "warnings": [],
                          "patch": null
                        }
                    """.trimIndent(),
                    model = request.model,
                )
            }
        }

        val result = GraphQaPatchService(gateway = gateway).answer(
            context = GraphQaContext(
                factGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:file-download",
                            type = NodeType.METHOD,
                            title = "CommonController.fileDownload",
                            signature = "CommonController.fileDownload(java.lang.String, java.lang.Boolean):void",
                            sourceTag = GraphSourceTag.FACT,
                        ),
                        GraphNode(
                            id = "scope:file-download-try",
                            type = NodeType.FLOW_SCOPE,
                            title = "try",
                            sourceTag = GraphSourceTag.FACT,
                            metadata = mapOf(
                                "flowchart.kind" to "SCOPE",
                                "flow.ownerMethod" to "CommonController.fileDownload(java.lang.String, java.lang.Boolean):void",
                            ),
                        ),
                        GraphNode(
                            id = "scope:file-download-if",
                            type = NodeType.FLOW_SCOPE,
                            title = "if (delete)",
                            sourceTag = GraphSourceTag.FACT,
                            metadata = mapOf(
                                "flowchart.kind" to "DECISION",
                                "flow.ownerMethod" to "CommonController.fileDownload(java.lang.String, java.lang.Boolean):void",
                            ),
                        ),
                    ),
                ),
            ),
            question = "请把删除条件调整方案写成可编辑图。",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://localhost:8080/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
        )

        val change = result.candidateChanges.single()
        assertEquals(listOf("scope:file-download-if"), change.targetNodeIds)
        assertEquals("scope:file-download-if", change.graphPatch?.operations?.singleOrNull()?.elementId)
        assertEquals("scope:file-download-if", change.graphPatch?.operations?.singleOrNull()?.node?.id)
        assertEquals(
            "if (Boolean.TRUE.equals(delete) && fileExists(filePath))",
            change.graphPatch?.operations?.singleOrNull()?.node?.title,
        )
    }

    @Test
    fun promotesDirectEvidenceLeadToCandidateChangeWhenQuestionExplicitlyRequestsFix() {
        val gateway = object : LlmGateway {
            override fun generate(request: LlmRequest): LlmResponse {
                return LlmResponse(
                    content = """
                        {
                          "answer": "建议把删除条件收紧为显式 true，并在删除前校验文件存在。",
                          "findings": [
                            {
                              "id": "delete-branch-direct-source",
                              "claim": "当前源码里直接能看到 if (delete) 和 deleteFile(filePath) 删除分支。",
                              "evidenceLevel": "DIRECT_SOURCE",
                              "references": [
                                {
                                  "nodeId": "scope:file-download-if",
                                  "filePath": "src/main/java/com/example/CommonController.java",
                                  "startLine": 60,
                                  "endLine": 68
                                }
                              ]
                            }
                          ],
                          "candidateChanges": [],
                          "investigationThreads": [
                            {
                              "threadId": "thread-expand-delete-decision-node-for-patch",
                              "status": "OPEN",
                              "claimType": "STRUCTURAL_SUGGESTION",
                              "title": "收紧删除条件并在删除前校验文件存在",
                              "targetNodeIds": ["scope:file-download-if"],
                              "summary": "删除分支当前只判断 delete，缺少显式 true 判断和文件存在校验。",
                              "evidenceGap": "还没有把这条修改组织成候选变更。",
                              "recommendedQuestion": "请把删除分支修复方案写成待确认变更。",
                              "supportingFindingIds": ["delete-branch-direct-source"]
                            }
                          ],
                          "warnings": [],
                          "patch": null
                        }
                    """.trimIndent(),
                    model = request.model,
                )
            }
        }

        val result = GraphQaPatchService(gateway = gateway).answer(
            context = GraphQaContext(
                factGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:file-download",
                            type = NodeType.METHOD,
                            title = "CommonController.fileDownload",
                            signature = "CommonController.fileDownload(java.lang.String, java.lang.Boolean):void",
                            sourceTag = GraphSourceTag.FACT,
                            metadata = mapOf(
                                "source.filePath" to "src/main/java/com/example/CommonController.java",
                                "source.startLine" to "42",
                                "source.endLine" to "88",
                            ),
                        ),
                        GraphNode(
                            id = "scope:file-download-if",
                            type = NodeType.FLOW_SCOPE,
                            title = "if (delete)",
                            sourceTag = GraphSourceTag.FACT,
                            metadata = mapOf(
                                "flowchart.kind" to "DECISION",
                                "flow.ownerMethod" to "CommonController.fileDownload(java.lang.String, java.lang.Boolean):void",
                                "source.filePath" to "src/main/java/com/example/CommonController.java",
                                "source.startLine" to "60",
                                "source.endLine" to "68",
                            ),
                        ),
                    ),
                ),
                selectedNodeIds = listOf("scope:file-download-if"),
                sourceContext = listOf(
                    SourceSnippetContext(
                        nodeId = "scope:file-download-if",
                        filePath = "src/main/java/com/example/CommonController.java",
                        startLine = 60,
                        endLine = 68,
                        snippet = "if (delete) { FileUtils.deleteFile(filePath); }",
                    ),
                ),
            ),
            question = "请把这个删除分支修复成 delete == true，并在删除前校验文件存在。",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://localhost:8080/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
        )

        assertEquals(1, result.candidateChanges.size)
        assertEquals("scope:file-download-if", result.candidateChanges.single().targetNodeIds.single())
        assertEquals("STRUCTURAL_SUGGESTION", result.candidateChanges.single().claimType)
        assertEquals(
            "收紧删除条件并在删除前校验文件存在",
            result.candidateChanges.single().title,
        )
        assertEquals(1, result.candidateChanges.single().editScopes.size)
        assertEquals(
            "src/main/java/com/example/CommonController.java",
            result.candidateChanges.single().editScopes.single().filePath,
        )
        assertEquals(
            "CommonController.fileDownload(java.lang.String, java.lang.Boolean):void",
            result.candidateChanges.single().editScopes.single().symbolSignature,
        )
        assertEquals(1, result.investigationThreads.size)
        assertEquals(InvestigationThreadStatus.PROMOTED, result.investigationThreads.single().status)
    }

    @Test
    fun retriesOnceWhenRemoteQaResponseIsNotStructuredJson() {
        val requests = mutableListOf<LlmRequest>()
        val gateway = object : LlmGateway {
            private var callCount = 0

            override fun generate(request: LlmRequest): LlmResponse {
                requests += request
                callCount += 1
                return if (callCount == 1) {
                    LlmResponse(
                        content = "我建议先补默认兜底节点，再人工确认真实实现。",
                        model = request.model,
                    )
                } else {
                    LlmResponse(
                        content = """
                            {
                              "answer": "远程问答建议补一个默认兜底说明节点。",
                              "warnings": [],
                              "patch": {
                                "summary": "远程问答草稿",
                                "operations": [
                                  {
                                    "id": "remote-qa-add-node",
                                    "action": "ADD_NODE",
                                    "elementKind": "NODE",
                                    "elementId": "doc:remote-fallback",
                                    "title": "新增远程建议节点",
                                    "summary": "补充默认兜底说明",
                                    "node": {
                                      "id": "doc:remote-fallback",
                                      "type": "DOC_PAGE",
                                      "title": "默认兜底说明",
                                      "doc": "远程 LLM 建议补齐默认兜底逻辑。",
                                      "sourceTag": "DRAFT_AI"
                                    }
                                  }
                                ],
                                "addedNodeIds": ["doc:remote-fallback"],
                                "removedNodeIds": [],
                                "addedEdgeIds": [],
                                "removedEdgeIds": []
                              }
                            }
                        """.trimIndent(),
                        model = request.model,
                    )
                }
            }
        }

        val result = GraphQaPatchService(gateway = gateway).answer(
            context = GraphQaContext(
                factGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:order-service-place",
                            type = NodeType.METHOD,
                            title = "OrderService.place",
                            sourceTag = GraphSourceTag.FACT,
                        ),
                    ),
                ),
            ),
            question = "请围绕整图进行问答，判断是否遗漏默认兜底逻辑？",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://localhost:8080/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
        )

        assertEquals(2, requests.size)
        assertEquals(LlmResultSource.REMOTE, result.source)
        assertTrue(result.answer.contains("默认兜底"))
        assertTrue(result.warnings.any { it.contains("自动修复") || it.contains("重试") })
        assertTrue(requests.last().systemPrompt.contains("JSON 修复"))
    }

    @Test
    fun derivesQuestionScopedCandidateReasonFromRemotePatchFallback() {
        val gateway = object : LlmGateway {
            override fun generate(request: LlmRequest): LlmResponse {
                return LlmResponse(
                    content = """
                        {
                          "answer": "远程问答建议补一个下载路径说明。",
                          "findings": [
                            {
                              "id": "download-direct-source",
                              "claim": "当前源码里直接能看到下载路径处理逻辑。",
                              "evidenceLevel": "DIRECT_SOURCE",
                              "references": [
                                {
                                  "nodeId": "method:file-download",
                                  "filePath": "src/main/java/com/example/CommonController.java",
                                  "startLine": 42,
                                  "endLine": 88
                                }
                              ]
                            }
                          ],
                          "warnings": [],
                          "patch": {
                            "summary": "远程问答草稿",
                            "operations": [
                              {
                                "id": "remote-question-add-doc",
                                "action": "ADD_NODE",
                                "elementKind": "NODE",
                                "elementId": "doc:download-path-note",
                                "title": "补充下载路径说明",
                                "summary": "说明当前下载路径拼接约束",
                                "node": {
                                  "id": "doc:download-path-note",
                                  "type": "DOC_PAGE",
                                  "title": "下载路径说明",
                                  "doc": "需要补充下载路径处理说明。",
                                  "sourceTag": "DRAFT_AI"
                                }
                              }
                            ],
                            "addedNodeIds": ["doc:download-path-note"],
                            "removedNodeIds": [],
                            "addedEdgeIds": [],
                            "removedEdgeIds": []
                          }
                        }
                    """.trimIndent(),
                    model = request.model,
                )
            }
        }

        val result = GraphQaPatchService(gateway = gateway).answer(
            context = GraphQaContext(
                factGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:file-download",
                            type = NodeType.METHOD,
                            title = "CommonController.fileDownload",
                            sourceTag = GraphSourceTag.FACT,
                        ),
                    ),
                ),
            ),
            question = "请判断这里是否需要补充下载路径说明？",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://localhost:8080/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
        )

        assertEquals(1, result.candidateChanges.size)
        assertEquals("由远程问答建议生成。", result.candidateChanges.single().reason)
    }

    @Test
    fun retriesOnceWhenRemoteQaRequestTimesOut() {
        var callCount = 0
        val result = GraphQaPatchService(
            gateway = object : LlmGateway {
                override fun generate(request: LlmRequest): LlmResponse {
                    callCount += 1
                    if (callCount == 1) {
                        throw HttpTimeoutException("request timed out")
                    }
                    return LlmResponse(
                        content = """
                            {
                              "answer": "远程问答建议补一个默认兜底说明节点。",
                              "warnings": [],
                              "patch": {
                                "summary": "远程问答草稿",
                                "operations": [
                                  {
                                    "id": "remote-qa-add-node",
                                    "action": "ADD_NODE",
                                    "elementKind": "NODE",
                                    "elementId": "doc:remote-fallback",
                                    "title": "新增远程建议节点",
                                    "summary": "补充默认兜底说明",
                                    "node": {
                                      "id": "doc:remote-fallback",
                                      "type": "DOC_PAGE",
                                      "title": "默认兜底说明",
                                      "doc": "远程 LLM 建议补齐默认兜底逻辑。",
                                      "sourceTag": "DRAFT_AI"
                                    }
                                  }
                                ],
                                "addedNodeIds": ["doc:remote-fallback"],
                                "removedNodeIds": [],
                                "addedEdgeIds": [],
                                "removedEdgeIds": []
                              }
                            }
                        """.trimIndent(),
                        model = request.model,
                    )
                }
            },
        ).answer(
            context = GraphQaContext(
                factGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:order-service-place",
                            type = NodeType.METHOD,
                            title = "OrderService.place",
                            sourceTag = GraphSourceTag.FACT,
                        ),
                    ),
                ),
            ),
            question = "请围绕整图进行问答，判断是否遗漏默认兜底逻辑？",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://localhost:8080/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
        )

        assertEquals(2, callCount)
        assertEquals(LlmResultSource.REMOTE, result.source)
        assertTrue(result.warnings.any { it.contains("超时") && it.contains("重试") })
        assertTrue(result.answer.contains("默认兜底"))
    }

    @Test
    fun reportsRetryAttemptWhenRemoteQaStillTimesOutAfterRetry() {
        var callCount = 0
        val result = GraphQaPatchService(
            gateway = object : LlmGateway {
                override fun generate(request: LlmRequest): LlmResponse {
                    callCount += 1
                    throw HttpTimeoutException("request timed out")
                }
            },
        ).answer(
            context = GraphQaContext(
                factGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:order-service-place",
                            type = NodeType.METHOD,
                            title = "OrderService.place",
                            sourceTag = GraphSourceTag.FACT,
                        ),
                    ),
                ),
            ),
            question = "请围绕整图进行问答，判断是否遗漏默认兜底逻辑？",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://localhost:8080/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
        )

        assertEquals(2, callCount)
        assertEquals(LlmResultSource.LOCAL_RULE, result.source)
        assertTrue(result.warnings.any { it.contains("重试 1 次后仍失败") })
        assertTrue(result.warnings.none { it.contains("请检查请求地址、鉴权和模型配置") })
    }

    @Test
    fun fallsBackToMockQaWhenRemoteQaFails() {
        val gateway = object : LlmGateway {
            override fun generate(request: LlmRequest): LlmResponse {
                error("Remote LLM request failed with HTTP 503 (model_not_found): No available channel for model gpt-5.4")
            }
        }

        val result = GraphQaPatchService(gateway = gateway).answer(
            context = GraphQaContext(
                factGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:order-service-place",
                            type = NodeType.METHOD,
                            title = "OrderService.place",
                            sourceTag = GraphSourceTag.FACT,
                        ),
                    ),
                ),
            ),
            question = "请围绕整图进行问答，判断是否遗漏默认兜底逻辑？",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://localhost:8080/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
        )

        assertEquals(LlmResultSource.LOCAL_RULE, result.source)
        assertTrue(result.warnings.any { it.contains("远程 LLM 问答失败") })
        assertTrue(result.warnings.any { it.contains("model_not_found") })
    }

    @Test
    fun explainsHowToFixRemoteQaConfigurationBeforeUse() {
        val result = GraphQaPatchService().answer(
            context = GraphQaContext(
                factGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:order-service-place",
                            type = NodeType.METHOD,
                            title = "OrderService.place",
                            sourceTag = GraphSourceTag.FACT,
                        ),
                    ),
                ),
            ),
            question = "请围绕整图进行问答。",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "",
                apiKey = "",
                model = "",
            ),
        )

        assertEquals(LlmResultSource.LOCAL_RULE, result.source)
        assertTrue(result.warnings.any { it.contains("请求地址") })
        assertTrue(result.warnings.any { it.contains("API 密钥") })
        assertTrue(result.warnings.any { it.contains("链路图设置") })
        assertTrue(result.warnings.any { it.contains("先验证") })
    }

    @Test
    fun usesDraftOnlySelectedNodeAsQaScopeWhenManualNodeIsNotInFactGraph() {
        val manualNode = GraphNode(
            id = "doc:manual-qa-note",
            type = NodeType.DOC_PAGE,
            title = "人工测试节点",
            doc = "这是只存在于草稿层的手工说明节点。",
            sourceTag = GraphSourceTag.DRAFT_MANUAL,
        )
        val factNode = GraphNode(
            id = "method:order-service-place",
            type = NodeType.METHOD,
            title = "OrderService.place",
            sourceTag = GraphSourceTag.FACT,
        )
        val result = GraphQaPatchService().answer(
            context = GraphQaContext(
                factGraph = GraphDocument(nodes = listOf(factNode)),
                editableGraph = GraphDocument(
                    nodes = listOf(factNode, manualNode),
                    edges = listOf(
                        GraphEdge(
                            id = "edge:place->manual-note",
                            type = EdgeType.LINKS_DOC,
                            fromNodeId = factNode.id,
                            toNodeId = manualNode.id,
                            sourceTag = GraphSourceTag.DRAFT_MANUAL,
                        ),
                    ),
                ),
                selectedNodeIds = listOf(manualNode.id),
            ),
            question = "请围绕这个手工补充节点继续问答并补全链路。",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.MOCK.id,
            ),
        )

        assertEquals(null, result.patch)
        assertTrue(result.investigationThreads.any { thread -> manualNode.id in thread.targetNodeIds })
        assertTrue(result.promptPreview.contains(manualNode.title))
        assertTrue(result.answer.contains("当前节点") || result.answer.contains("当前框选范围"))
    }

    @Test
    fun ignoresRemoteReferenceFilePathWhenTrustedLocalPathExists() {
        val gateway = object : LlmGateway {
            override fun generate(request: LlmRequest): LlmResponse {
                return LlmResponse(
                    content = """
                        {
                          "answer": "这里可以直接确认 fileDownload 方法需要调整。",
                          "findings": [
                            {
                              "id": "download-source",
                              "claim": "当前源码里直接能看到 fileDownload 方法。",
                              "evidenceLevel": "DIRECT_SOURCE",
                              "references": [
                                {
                                  "nodeId": "method:file-download",
                                  "filePath": "/tmp/forged/CommonController.java",
                                  "startLine": 42,
                                  "endLine": 88
                                }
                              ]
                            }
                          ],
                          "candidateChanges": [
                            {
                              "changeId": "change-file-download",
                              "status": "PENDING_CONFIRMATION",
                              "claimType": "CODE_FACT",
                              "title": "调整 fileDownload 里的路径规则",
                              "targetNodeIds": ["method:file-download"],
                              "reason": "当前源码里直接可见。",
                              "impactSummary": "影响下载路径解析。",
                              "supportingFindingIds": ["download-source"]
                            }
                          ],
                          "warnings": [],
                          "patch": null
                        }
                    """.trimIndent(),
                    model = request.model,
                )
            }
        }

        val result = GraphQaPatchService(gateway = gateway).answer(
            context = GraphQaContext(
                factGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:file-download",
                            type = NodeType.METHOD,
                            title = "CommonController.fileDownload",
                            signature = "com.example.CommonController.fileDownload(java.lang.String):void",
                            sourceTag = GraphSourceTag.FACT,
                            metadata = mapOf(
                                "source.filePath" to "src/main/java/com/example/CommonController.java",
                                "source.startOffset" to "1200",
                                "source.endOffset" to "1640",
                            ),
                        ),
                    ),
                ),
                selectedNodeIds = listOf("method:file-download"),
                sourceContext = listOf(
                    SourceSnippetContext(
                        nodeId = "method:file-download",
                        filePath = "src/main/java/com/example/CommonController.java",
                        startOffset = 1200,
                        endOffset = 1640,
                        startLine = 42,
                        endLine = 88,
                        snippet = "public void fileDownload(String baseUrl) { return; }",
                    ),
                ),
            ),
            question = "请确认这里的路径规则是否需要调整？",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://localhost:8080/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
        )

        assertEquals(
            "src/main/java/com/example/CommonController.java",
            result.candidateChanges.single().editScopes.single().filePath,
        )
    }

    @Test
    fun doesNotDeriveEditScopeFromRemoteReferenceFilePathAlone() {
        val gateway = object : LlmGateway {
            override fun generate(request: LlmRequest): LlmResponse {
                return LlmResponse(
                    content = """
                        {
                          "answer": "这里可以直接确认 fileDownload 方法需要调整。",
                          "findings": [
                            {
                              "id": "download-source",
                              "claim": "当前源码里直接能看到 fileDownload 方法。",
                              "evidenceLevel": "DIRECT_SOURCE",
                              "references": [
                                {
                                  "nodeId": "method:file-download",
                                  "filePath": "/tmp/forged/CommonController.java",
                                  "startLine": 42,
                                  "endLine": 88
                                }
                              ]
                            }
                          ],
                          "candidateChanges": [
                            {
                              "changeId": "change-file-download",
                              "status": "PENDING_CONFIRMATION",
                              "claimType": "CODE_FACT",
                              "title": "调整 fileDownload 里的路径规则",
                              "targetNodeIds": ["method:file-download"],
                              "reason": "当前源码里直接可见。",
                              "impactSummary": "影响下载路径解析。",
                              "supportingFindingIds": ["download-source"]
                            }
                          ],
                          "warnings": [],
                          "patch": null
                        }
                    """.trimIndent(),
                    model = request.model,
                )
            }
        }

        val result = GraphQaPatchService(gateway = gateway).answer(
            context = GraphQaContext(
                factGraph = GraphDocument(
                    nodes = listOf(
                        GraphNode(
                            id = "method:file-download",
                            type = NodeType.METHOD,
                            title = "CommonController.fileDownload",
                            signature = "com.example.CommonController.fileDownload(java.lang.String):void",
                            sourceTag = GraphSourceTag.FACT,
                        ),
                    ),
                ),
                selectedNodeIds = listOf("method:file-download"),
            ),
            question = "请确认这里的路径规则是否需要调整？",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_COMPATIBLE.id,
                endpoint = "https://localhost:8080/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
        )

        assertEquals(1, result.candidateChanges.size)
        assertTrue(result.candidateChanges.single().editScopes.isEmpty())
    }
}
