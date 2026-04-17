package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.GraphUncertainty
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.settings.LlmProviderType
import com.charmnight.linkgraph.workbench.AuditConversationMessage
import com.charmnight.linkgraph.workbench.AuditConversationSession
import com.charmnight.linkgraph.workbench.AuditInvestigationLead
import com.charmnight.linkgraph.workbench.AuditInvestigationLeadStatus
import com.charmnight.linkgraph.workbench.AuditMessageRole
import java.net.http.HttpTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GraphAuditPatchServiceTest {
    @Test
    fun buildsMockAuditAnswerAndPatchPreview() {
        val result = GraphAuditPatchService().audit(
            context = GraphAuditContext(
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
                provider = LlmProviderType.MOCK.name,
            ),
        )

        assertEquals(LlmResultSource.MOCK, result.source)
        assertTrue(result.answer.contains("当前轮结论"))
        assertTrue(result.answer.contains("处理建议"))
        assertTrue(result.answer.contains("默认兜底"))
        assertTrue(result.promptPreview.contains("当前范围"))
        assertEquals(null, result.patch)
        assertTrue(result.findings.any { finding ->
            finding.evidenceLevel == ResultEvidenceLevel.NOT_OBSERVED
        })
        assertTrue(result.candidateChanges.isEmpty())
        assertEquals(1, result.investigationLeads.size)
        assertTrue(result.investigationLeads.first().title.contains("默认兜底"))
        assertEquals("RISK_HINT", result.investigationLeads.first().claimType)
        assertEquals(
            ResultEvidenceLevel.NOT_OBSERVED,
            result.investigationLeads.first().evidence.firstOrNull()?.evidenceLevel,
        )
        assertEquals(1, result.sourceContext.size)
        assertEquals(1, result.evidenceTrace.size)
        assertEquals("本轮问答直接附带的源码片段", result.evidenceTrace.first().reason)
        assertNotNull(result.auditSession)
        assertEquals(2, result.auditSession.messages.size)
        assertEquals(AuditMessageRole.USER, result.auditSession.messages.first().role)
        assertEquals(AuditMessageRole.ASSISTANT, result.auditSession.messages.last().role)
    }

    @Test
    fun explanationStyleQuestionReturnsExplanationWithoutGenericCandidateChangesInMockMode() {
        val result = GraphAuditPatchService().audit(
            context = GraphAuditContext(
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
                provider = LlmProviderType.MOCK.name,
            ),
        )

        assertEquals(LlmResultSource.MOCK, result.source)
        assertTrue(result.answer.contains("当前范围说明"))
        assertTrue(result.answer.contains("CommonController.uploadFile"))
        assertTrue(result.candidateChanges.isEmpty())
        assertTrue(result.findings.isNotEmpty())
    }

    @Test
    fun requestsRemoteAuditWhenOpenAiCompatibleProviderIsReady() {
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

        val result = GraphAuditPatchService(gateway = gateway).audit(
            context = GraphAuditContext(
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
            question = "请审计整图是否遗漏默认兜底逻辑？",
            settings = LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderType.OPENAI_COMPATIBLE.name,
                endpoint = "http://localhost:8080/v1",
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
        assertEquals("补一个默认兜底说明节点", result.investigationLeads.firstOrNull()?.title)
        assertEquals("RISK_HINT", result.investigationLeads.firstOrNull()?.claimType)
        assertEquals("fallback-missing", result.investigationLeads.firstOrNull()?.evidence?.firstOrNull()?.id)
        assertEquals(
            ResultEvidenceLevel.NOT_OBSERVED,
            result.investigationLeads.firstOrNull()?.evidence?.firstOrNull()?.evidenceLevel,
        )
    }

    @Test
    fun `follow-up audit with source lead id merges weak evidence back into the original risk thread`() {
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
                          "investigationLeads": [
                            {
                              "leadId": "lead-upload-follow-up",
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

        val result = GraphAuditPatchService(gateway = gateway).audit(
            context = GraphAuditContext(
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
                provider = LlmProviderType.OPENAI_COMPATIBLE.name,
                endpoint = "http://localhost:8080/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
            session = AuditConversationSession(
                sessionId = "audit-upload",
                scopeKey = "flow-action:upload",
                messages = listOf(
                    AuditConversationMessage(
                        messageId = "audit-upload-user-1",
                        role = AuditMessageRole.USER,
                        content = "请继续取证：展开 upload 实现。",
                        focusTargetId = "lead-upload-risk",
                    ),
                ),
                investigationLeads = listOf(
                    AuditInvestigationLead(
                        leadId = "lead-upload-risk",
                        status = AuditInvestigationLeadStatus.OPEN,
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
                focusTargetId = "lead-upload-risk",
            ),
            sourceLeadId = "lead-upload-risk",
        )

        assertEquals(1, result.investigationLeads.size)
        assertEquals("lead-upload-risk", result.investigationLeads.single().leadId)
        assertEquals("上传路径校验仍待确认", result.investigationLeads.single().title)
        assertEquals(2, result.investigationLeads.single().evidence.size)
        assertTrue(result.newInvestigationLeads.isEmpty())
        assertEquals("lead-upload-risk", result.auditSession?.focusTargetId)
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

        val result = GraphAuditPatchService(gateway = gateway).audit(
            context = GraphAuditContext(
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
                provider = LlmProviderType.OPENAI_COMPATIBLE.name,
                endpoint = "http://localhost:8080/v1",
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

        val result = GraphAuditPatchService(gateway = gateway).audit(
            context = GraphAuditContext(
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
                provider = LlmProviderType.OPENAI_COMPATIBLE.name,
                endpoint = "http://localhost:8080/v1",
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

        val result = GraphAuditPatchService(gateway = gateway).audit(
            context = GraphAuditContext(
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
                provider = LlmProviderType.OPENAI_COMPATIBLE.name,
                endpoint = "http://localhost:8080/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
        )

        assertEquals(1, result.candidateChanges.size)
        assertTrue(result.investigationLeads.isEmpty())
        assertEquals("change-upload-condition", result.candidateChanges.first().changeId)
    }

    @Test
    fun retriesOnceWhenRemoteAuditResponseIsNotStructuredJson() {
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
                                    "id": "remote-audit-add-node",
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

        val result = GraphAuditPatchService(gateway = gateway).audit(
            context = GraphAuditContext(
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
                provider = LlmProviderType.OPENAI_COMPATIBLE.name,
                endpoint = "http://localhost:8080/v1",
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

        val result = GraphAuditPatchService(gateway = gateway).audit(
            context = GraphAuditContext(
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
                provider = LlmProviderType.OPENAI_COMPATIBLE.name,
                endpoint = "http://localhost:8080/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
        )

        assertEquals(1, result.candidateChanges.size)
        assertEquals("由远程问答建议生成。", result.candidateChanges.single().reason)
    }

    @Test
    fun retriesOnceWhenRemoteAuditRequestTimesOut() {
        var callCount = 0
        val result = GraphAuditPatchService(
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
                                    "id": "remote-audit-add-node",
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
        ).audit(
            context = GraphAuditContext(
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
                provider = LlmProviderType.OPENAI_COMPATIBLE.name,
                endpoint = "http://localhost:8080/v1",
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
    fun reportsRetryAttemptWhenRemoteAuditStillTimesOutAfterRetry() {
        var callCount = 0
        val result = GraphAuditPatchService(
            gateway = object : LlmGateway {
                override fun generate(request: LlmRequest): LlmResponse {
                    callCount += 1
                    throw HttpTimeoutException("request timed out")
                }
            },
        ).audit(
            context = GraphAuditContext(
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
                provider = LlmProviderType.OPENAI_COMPATIBLE.name,
                endpoint = "http://localhost:8080/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
        )

        assertEquals(2, callCount)
        assertEquals(LlmResultSource.MOCK, result.source)
        assertTrue(result.warnings.any { it.contains("重试 1 次后仍失败") })
        assertTrue(result.warnings.none { it.contains("请检查请求地址、鉴权和模型配置") })
    }

    @Test
    fun fallsBackToMockAuditWhenRemoteAuditFails() {
        val gateway = object : LlmGateway {
            override fun generate(request: LlmRequest): LlmResponse {
                error("Remote LLM request failed with HTTP 503 (model_not_found): No available channel for model gpt-5.4")
            }
        }

        val result = GraphAuditPatchService(gateway = gateway).audit(
            context = GraphAuditContext(
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
                provider = LlmProviderType.OPENAI_COMPATIBLE.name,
                endpoint = "http://localhost:8080/v1",
                apiKey = "token",
                model = "gpt-test",
            ),
        )

        assertEquals(LlmResultSource.MOCK, result.source)
        assertTrue(result.warnings.any { it.contains("远程 LLM 问答失败") })
        assertTrue(result.warnings.any { it.contains("model_not_found") })
    }

    @Test
    fun explainsHowToFixRemoteAuditConfigurationBeforeUse() {
        val result = GraphAuditPatchService().audit(
            context = GraphAuditContext(
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
                provider = LlmProviderType.OPENAI_COMPATIBLE.name,
                endpoint = "",
                apiKey = "",
                model = "",
            ),
        )

        assertEquals(LlmResultSource.MOCK, result.source)
        assertTrue(result.warnings.any { it.contains("请求地址") })
        assertTrue(result.warnings.any { it.contains("API 密钥") })
        assertTrue(result.warnings.any { it.contains("链路图设置") })
        assertTrue(result.warnings.any { it.contains("先验证") })
    }

    @Test
    fun usesDraftOnlySelectedNodeAsAuditScopeWhenManualNodeIsNotInFactGraph() {
        val manualNode = GraphNode(
            id = "doc:manual-audit-note",
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
        val result = GraphAuditPatchService().audit(
            context = GraphAuditContext(
                factGraph = GraphDocument(nodes = listOf(factNode)),
                draftGraph = GraphDocument(
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
                provider = LlmProviderType.MOCK.name,
            ),
        )

        assertEquals(null, result.patch)
        assertTrue(result.investigationLeads.any { lead -> manualNode.id in lead.targetNodeIds })
        assertTrue(result.promptPreview.contains(manualNode.title))
        assertTrue(result.answer.contains("当前节点") || result.answer.contains("当前框选范围"))
    }
}
