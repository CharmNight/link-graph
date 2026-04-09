package com.charmnight.linkgraph.semantic.provider.code

import com.charmnight.linkgraph.testing.addResourceFixture
import com.charmnight.linkgraph.testing.fixtureFileName
import com.charmnight.linkgraph.testing.readJavaFixture
import com.charmnight.linkgraph.semantic.graph.GraphAssembler
import com.charmnight.linkgraph.semantic.model.FlowActionUnit
import com.charmnight.linkgraph.semantic.model.FlowScopeUnit
import com.charmnight.linkgraph.semantic.model.InvocationUnit
import com.charmnight.linkgraph.semantic.model.MergeUnit
import com.charmnight.linkgraph.semantic.model.MethodLikeUnit
import com.charmnight.linkgraph.semantic.model.ResourceUnit
import com.charmnight.linkgraph.semantic.model.SemanticRelationKind
import com.charmnight.linkgraph.semantic.model.TerminalUnit
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.policy.SemanticCapturePolicy
import com.charmnight.linkgraph.semantic.policy.TraversalBudgetPolicy
import com.charmnight.linkgraph.semantic.subject.CaretSubjectLocator
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandle
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class JavaCodeSemanticProviderTest : BasePlatformTestCase() {
    fun testAnalyzeJavaMethodBuildsUnifiedSemanticResourceBinding() {
        loadProjectFixture("mybatis/UserMapper.xml")
        loadFixtureWithCaret("mybatis/UserMapper.java", "loadUser")

        val handle = CaretSubjectLocator().locate(project, myFixture.editor)
        val codeHandle = assertInstanceOf(handle, CodeSubjectHandle::class.java)

        val result = JavaCodeSemanticProvider().analyze(
            handle = codeHandle,
            capturePolicy = SemanticCapturePolicy(),
            budgetPolicy = TraversalBudgetPolicy(maxDownstreamDepth = 1, maxInvocationsPerUnit = 8),
        )

        val mapperMethod = result.semanticUnits.firstOrNull { unit ->
            unit is MethodLikeUnit && unit.title == "UserMapper.selectUser"
        } as? MethodLikeUnit
        val sqlResource = result.semanticUnits.firstOrNull { unit ->
            unit is ResourceUnit &&
                unit.resourceKind == "MYBATIS_STATEMENT" &&
                unit.metadata["namespace"] == "com.charmnight.linkgraph.fixtures.mybatis.UserMapper" &&
                unit.metadata["statementId"] == "selectUser"
        } as? ResourceUnit

        assertTrue("应当识别出 mapper 方法语义单元", mapperMethod != null)
        assertTrue("应当识别出对应 SQL 资源单元", sqlResource != null)
        assertTrue(
            "应当通过统一 BINDS_TO 关系表达 mapper 到 SQL 的绑定",
            result.relations.any { relation ->
                relation.kind == SemanticRelationKind.BINDS_TO &&
                    relation.fromUnitId == mapperMethod!!.id &&
                    relation.toUnitId == sqlResource!!.id &&
                    relation.label == "MYBATIS_STATEMENT"
            },
        )
    }

    fun testAnalyzeJavaMethodBuildsUnifiedSemanticHttpBindings() {
        loadFixtureWithCaret("http/FeignOrderClient.java", "getOrder(id)")

        val handle = CaretSubjectLocator().locate(project, myFixture.editor)
        val codeHandle = assertInstanceOf(handle, CodeSubjectHandle::class.java)

        val result = JavaCodeSemanticProvider().analyze(
            handle = codeHandle,
            capturePolicy = SemanticCapturePolicy(),
            budgetPolicy = TraversalBudgetPolicy(maxDownstreamDepth = 1, maxInvocationsPerUnit = 8),
        )

        val serviceMethod = result.semanticUnits.firstOrNull { unit ->
            unit is MethodLikeUnit && unit.title == "OrderGatewayService.loadOrder"
        } as? MethodLikeUnit
        val feignClient = result.semanticUnits.firstOrNull { unit ->
            unit is ResourceUnit &&
                unit.resourceKind == "FEIGN_CLIENT" &&
                unit.metadata["clientClass"] == "com.charmnight.linkgraph.fixtures.http.FeignOrderClient" &&
                unit.metadata["httpMethod"] == "GET" &&
                unit.metadata["path"] == "/orders/{id}"
        } as? ResourceUnit
        val endpoint = result.semanticUnits.firstOrNull { unit ->
            unit is ResourceUnit &&
                unit.resourceKind == "HTTP_ENDPOINT" &&
                unit.metadata["httpMethod"] == "GET" &&
                unit.metadata["path"] == "/orders/{id}"
        } as? ResourceUnit

        assertTrue("应当识别服务方法语义单元", serviceMethod != null)
        assertTrue("应当识别 Feign 客户端资源单元", feignClient != null)
        assertTrue("应当识别 HTTP 端点资源单元", endpoint != null)
        assertTrue(
            "应当通过统一 REFERENCES 关系表达服务方法到 Feign 客户端的代理调用",
            result.relations.any { relation ->
                relation.kind == SemanticRelationKind.REFERENCES &&
                    relation.fromUnitId == serviceMethod!!.id &&
                    relation.toUnitId == feignClient!!.id &&
                    relation.label == com.charmnight.linkgraph.model.EdgeType.USES_PROXY.name
            },
        )
        assertTrue(
            "应当通过统一 REFERENCES 关系表达 Feign 客户端到 HTTP 端点的路由",
            result.relations.any { relation ->
                relation.kind == SemanticRelationKind.REFERENCES &&
                    relation.fromUnitId == feignClient!!.id &&
                    relation.toUnitId == endpoint!!.id &&
                    relation.label == com.charmnight.linkgraph.model.EdgeType.ROUTES_TO.name
            },
        )
        assertTrue(
            "应当继续发现 provider 端点方法，供统一下游遍历使用",
            result.semanticUnits.any { unit ->
                unit is MethodLikeUnit && unit.title == "OrderProviderController.getOrder"
            },
        )
    }

    fun testAnalyzeJavaMethodBuildsUnifiedSemanticUnits() {
        loadFixtureWithCaret("simple/SimpleCallChain.java", "sanitize(state)")

        val handle = CaretSubjectLocator().locate(project, myFixture.editor)
        val codeHandle = assertInstanceOf(handle, CodeSubjectHandle::class.java)

        val result = JavaCodeSemanticProvider().analyze(
            handle = codeHandle,
            capturePolicy = SemanticCapturePolicy(),
            budgetPolicy = TraversalBudgetPolicy(maxDownstreamDepth = 1, maxInvocationsPerUnit = 8),
        )
        assertEquals(codeHandle, result.subject)
        assertTrue(result.semanticUnits.any { unit ->
            unit is MethodLikeUnit && unit.signature == codeHandle.methodSignature
        })
        assertTrue(result.semanticUnits.any { unit ->
            unit is MethodLikeUnit && unit.title == "SimpleCallChain.sanitize"
        })
        assertTrue(result.semanticUnits.any { unit ->
            unit is MethodLikeUnit && unit.title == "OrderGatewayImpl.fetch"
        })
        assertTrue(result.semanticUnits.any { unit ->
            unit is FlowActionUnit && unit.title.contains("sanitize(state)")
        })
        assertTrue(result.semanticUnits.any { unit ->
            unit is FlowScopeUnit && unit.scopeKind == "IF"
        })
        assertTrue(result.semanticUnits.any { unit ->
            unit is InvocationUnit && unit.targetSignature?.contains("SimpleCallChain.sanitize") == true
        })
        assertTrue(result.relations.any { relation -> relation.kind == SemanticRelationKind.CONTAINS })
        assertTrue(result.relations.any { relation -> relation.kind == SemanticRelationKind.CONTROL_FLOW })
        assertTrue(result.relations.any { relation -> relation.kind == SemanticRelationKind.INVOKES })
        assertTrue(result.sourceMappings.any { mapping -> mapping.targetUnitId == result.anchors.single().targetUnitId })
    }

    fun testAnalyzeFileDownloadMethodProducesDecisionFlowchartBranches() {
        myFixture.configureByText(
            "CommonController.java",
            """
                package com.ruoyi.web.controller.common;

                class CommonController {
                    void fileDownload(String fileName, Boolean delete, Object response, Object request) {
                        try {
                            if (!FileUtils.checkAllowDownload(fileName)) {
                                throw new Exception(StringUtils.format("文件名称({})非法，不允许下载。 ", fileName));
                            }
                            String realFileName = System.currentTimeMillis() + fileName.substring(fileName.indexOf("_") + 1);
                            String filePath = RuoYiConfig.getDownloadPath() + fileName;
                            FileUtils.setAttachmentResponseHeader(response, realFileName);
                            FileUtils.writeBytes(filePath, response.toString());
                            if (delete) {
                                FileUtils.deleteFile(filePath);
                            }
                        } catch (Exception e) {
                            log(e);
                        }
                    }

                    void log(Exception e) {}
                }

                class FileUtils {
                    static boolean checkAllowDownload(String fileName) { return true; }
                    static void setAttachmentResponseHeader(Object response, String realFileName) {}
                    static void writeBytes(String filePath, String output) {}
                    static void deleteFile(String filePath) {}
                }

                class StringUtils {
                    static String format(String pattern, String fileName) { return pattern + fileName; }
                }

                class RuoYiConfig {
                    static String getDownloadPath() { return "/tmp/"; }
                }
            """.trimIndent().replace("fileDownload", "<caret>fileDownload"),
        )

        val handle = CaretSubjectLocator().locate(project, myFixture.editor)
        val codeHandle = assertInstanceOf(handle, CodeSubjectHandle::class.java)
        val result = JavaCodeSemanticProvider().analyze(
            handle = codeHandle,
            capturePolicy = SemanticCapturePolicy(),
            budgetPolicy = TraversalBudgetPolicy(maxDownstreamDepth = 1, maxInvocationsPerUnit = 16),
        )

        val flowchart = GraphAssembler().assemble(result, AnalysisDisplayMode.FLOWCHART)
        val decisionNode = flowchart.nodes.firstOrNull { node ->
            node.type == com.charmnight.linkgraph.model.NodeType.FLOW_SCOPE &&
                node.metadata["flowchart.kind"] == "DECISION"
        }
        val terminalThrowNode = flowchart.nodes.firstOrNull { node ->
            node.title.contains("Exception", ignoreCase = true) &&
                node.metadata["flowchart.kind"] == "TERMINAL"
        }
        val realFileNameNode = flowchart.nodes.firstOrNull { node ->
            node.title.contains("realFileName", ignoreCase = true)
        }
        val edgeSummary = flowchart.edges.joinToString(separator = "\n") { edge ->
            "${edge.fromNodeId} -> ${edge.toNodeId} [${edge.label ?: ""}]"
        }

        assertTrue("应当识别出 fileDownload 的下载校验条件节点", decisionNode != null)
        assertTrue("应当把 guard throw 分支投影为终止节点", terminalThrowNode != null)
        assertTrue("应当保留 FALSE 分支上的后续正常步骤", realFileNameNode != null)
        assertTrue(
            "应当显式抽取 if 条件中的 checkAllowDownload 调用",
            result.semanticUnits.any { unit ->
                unit is InvocationUnit && unit.targetSignature?.contains("checkAllowDownload") == true
            },
        )
        assertTrue(
            "当前流程图边如下：\n$edgeSummary",
            flowchart.edges.any { edge ->
                edge.fromNodeId == decisionNode!!.id &&
                    edge.toNodeId == terminalThrowNode!!.id &&
                    edge.label == "TRUE"
            },
        )
        assertTrue(
            "当前流程图边如下：\n$edgeSummary",
            flowchart.edges.any { edge ->
                edge.fromNodeId == decisionNode!!.id &&
                    edge.toNodeId == realFileNameNode!!.id &&
                    edge.label == "FALSE"
            },
        )
    }

    fun testAnalyzeJavaMethodBuildsImplicitReturnAfterTrailingSingleBranchIf() {
        myFixture.configureByText(
            "CleanupController.java",
            """
                package com.example;

                class CleanupController {
                    void <caret>cleanup(boolean delete, String filePath) {
                        if (delete) {
                            FileUtils.deleteFile(filePath);
                        }
                    }
                }

                class FileUtils {
                    static void deleteFile(String filePath) {}
                }
            """.trimIndent(),
        )

        val handle = CaretSubjectLocator().locate(project, myFixture.editor)
        val codeHandle = assertInstanceOf(handle, CodeSubjectHandle::class.java)
        val result = JavaCodeSemanticProvider().analyze(
            handle = codeHandle,
            capturePolicy = SemanticCapturePolicy(),
            budgetPolicy = TraversalBudgetPolicy(maxDownstreamDepth = 1, maxInvocationsPerUnit = 16),
        )

        val decisionUnit = result.semanticUnits
            .filterIsInstance<FlowScopeUnit>()
            .firstOrNull { unit -> unit.scopeKind == "IF" }
        val deleteInvocation = result.semanticUnits
            .filterIsInstance<InvocationUnit>()
            .firstOrNull { unit -> unit.targetSignature?.contains("FileUtils.deleteFile") == true }
        val mergeUnit = result.semanticUnits.filterIsInstance<MergeUnit>().firstOrNull()
        val returnTerminal = result.semanticUnits
            .filterIsInstance<TerminalUnit>()
            .firstOrNull { unit -> unit.terminalKind == "RETURN" }
        val edgeSummary = result.relations
            .filter { relation -> relation.kind == SemanticRelationKind.CONTROL_FLOW }
            .joinToString(separator = "\n") { relation ->
                "${relation.fromUnitId} -> ${relation.toUnitId} [${relation.label ?: ""}]"
            }

        assertTrue("应当识别出尾部单分支 if 的条件节点", decisionUnit != null)
        assertTrue("TRUE 分支里的 deleteFile 调用必须被显式抽取", deleteInvocation != null)
        assertTrue("方法尾部的单分支 if 之后必须补出隐式汇合节点", mergeUnit != null)
        assertTrue("void 方法自然结束时必须补出返回终止节点", returnTerminal != null)
        assertTrue(
            "FALSE 分支必须显式汇合到方法结束，当前控制流如下：\n$edgeSummary",
            result.relations.any { relation ->
                relation.kind == SemanticRelationKind.CONTROL_FLOW &&
                    relation.fromUnitId == decisionUnit!!.id &&
                    relation.toUnitId == mergeUnit!!.id &&
                    relation.label == "FALSE"
            },
        )
        assertTrue(
            "TRUE 分支执行完 deleteFile 后必须回到隐式汇合节点，当前控制流如下：\n$edgeSummary",
            result.relations.any { relation ->
                relation.kind == SemanticRelationKind.CONTROL_FLOW &&
                    relation.fromUnitId == deleteInvocation!!.id &&
                    relation.toUnitId == mergeUnit!!.id
            },
        )
        assertTrue(
            "隐式汇合节点之后必须继续连接到返回终止节点，当前控制流如下：\n$edgeSummary",
            result.relations.any { relation ->
                relation.kind == SemanticRelationKind.CONTROL_FLOW &&
                    relation.fromUnitId == mergeUnit!!.id &&
                    relation.toUnitId == returnTerminal!!.id
            },
        )
    }

    fun testAnalyzeJavaMethodBuildsExplicitMergeAfterIfElseBranches() {
        myFixture.configureByText(
            "MergeSample.java",
            """
                package com.example;

                class MergeSample {
                    void <caret>build(boolean flag) {
                        int base;
                        if (flag) {
                            base = 1;
                        } else {
                            base = 2;
                        }
                        normalize(base);
                    }

                    void normalize(int value) {}
                }
            """.trimIndent(),
        )

        val handle = CaretSubjectLocator().locate(project, myFixture.editor)
        val codeHandle = assertInstanceOf(handle, CodeSubjectHandle::class.java)
        val result = JavaCodeSemanticProvider().analyze(
            handle = codeHandle,
            capturePolicy = SemanticCapturePolicy(),
            budgetPolicy = TraversalBudgetPolicy(maxDownstreamDepth = 1, maxInvocationsPerUnit = 16),
        )

        val decisionUnit = result.semanticUnits
            .filterIsInstance<FlowScopeUnit>()
            .firstOrNull { unit -> unit.scopeKind == "IF" }
        val mergeUnit = result.semanticUnits.filterIsInstance<MergeUnit>().firstOrNull()
        val trueBranchAction = result.semanticUnits
            .filterIsInstance<FlowActionUnit>()
            .firstOrNull { unit -> unit.title.contains("base = 1") }
        val falseBranchAction = result.semanticUnits
            .filterIsInstance<FlowActionUnit>()
            .firstOrNull { unit -> unit.title.contains("base = 2") }
        val postBranchAction = result.semanticUnits
            .filterIsInstance<FlowActionUnit>()
            .firstOrNull { unit -> unit.title.contains("normalize(base)") }

        assertTrue("多出口分支之后必须产出显式汇合语义", mergeUnit != null)
        assertTrue("应当识别 if 条件作用域", decisionUnit != null)
        assertTrue("应当保留 TRUE 分支动作", trueBranchAction != null)
        assertTrue("应当保留 FALSE 分支动作", falseBranchAction != null)
        assertTrue("应当保留分支后的顺序动作", postBranchAction != null)
        assertTrue(
            "TRUE 分支必须先汇合再继续后续动作",
            result.relations.any { relation ->
                relation.kind == SemanticRelationKind.CONTROL_FLOW &&
                    relation.fromUnitId == trueBranchAction!!.id &&
                    relation.toUnitId == mergeUnit!!.id
            },
        )
        assertTrue(
            "FALSE 分支必须先汇合再继续后续动作",
            result.relations.any { relation ->
                relation.kind == SemanticRelationKind.CONTROL_FLOW &&
                    relation.fromUnitId == falseBranchAction!!.id &&
                    relation.toUnitId == mergeUnit!!.id
            },
        )
        assertTrue(
            "汇合节点之后必须继续连接后续顺序动作",
            result.relations.any { relation ->
                relation.kind == SemanticRelationKind.CONTROL_FLOW &&
                    relation.fromUnitId == mergeUnit!!.id &&
                    relation.toUnitId == postBranchAction!!.id
            },
        )
    }

    private fun loadFixtureWithCaret(
        relativePath: String,
        caretMarker: String,
    ) {
        val content = readJavaFixture(relativePath).replace(caretMarker, "<caret>$caretMarker")
        myFixture.configureByText(fixtureFileName(relativePath), content)
    }

    private fun loadProjectFixture(relativePath: String) {
        myFixture.addResourceFixture(relativePath)
    }
}
