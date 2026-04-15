package com.charmnight.linkgraph.semantic.provider.code

import com.charmnight.linkgraph.semantic.model.FlowActionUnit
import com.charmnight.linkgraph.semantic.model.FlowEdgeRole
import com.charmnight.linkgraph.semantic.model.FlowScopeUnit
import com.charmnight.linkgraph.semantic.model.FlowScopeCategory
import com.charmnight.linkgraph.semantic.model.InvocationUnit
import com.charmnight.linkgraph.semantic.model.MethodLikeUnit
import com.charmnight.linkgraph.semantic.model.SemanticRelationKind
import com.charmnight.linkgraph.semantic.policy.SemanticCapturePolicy
import com.charmnight.linkgraph.semantic.policy.TraversalBudgetPolicy
import com.charmnight.linkgraph.semantic.subject.CaretSubjectLocator
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandle
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class KotlinCodeSemanticProviderTest : BasePlatformTestCase() {
    fun testAnalyzeKotlinWhenBuildsExplicitBranchSemanticUnits() {
        myFixture.configureByText(
            "BranchService.kt",
            """
                package com.example

                class BranchService(
                    private val gateway: Gateway = GatewayImpl(),
                ) {
                    fun branchy(state: String): String {
                        return when (<caret>state) {
                            "NEW" -> sanitize(state)
                            else -> gateway.fetch(state)
                        }
                    }

                    private fun sanitize(value: String): String {
                        return value.trim()
                    }
                }

                interface Gateway {
                    fun fetch(value: String): String
                }

                class GatewayImpl : Gateway {
                    override fun fetch(value: String): String {
                        return "repo:${'$'}value"
                    }
                }
            """.trimIndent(),
        )

        val handle = CaretSubjectLocator().locate(project, myFixture.editor)
        val codeHandle = assertInstanceOf(handle, CodeSubjectHandle::class.java)

        val result = KotlinCodeSemanticProvider().analyze(
            handle = codeHandle,
            capturePolicy = SemanticCapturePolicy(),
            budgetPolicy = TraversalBudgetPolicy(maxDownstreamDepth = 1, maxInvocationsPerUnit = 8),
        )

        assertTrue(result.semanticUnits.any { unit ->
            unit is MethodLikeUnit && unit.signature == codeHandle.methodSignature
        })
        val whenScope = result.semanticUnits
            .filterIsInstance<FlowScopeUnit>()
            .firstOrNull { unit -> unit.scopeKind == "SWITCH" }
        assertTrue(whenScope != null)
        assertEquals(FlowScopeCategory.SWITCH, whenScope!!.scopeCategory)
        assertTrue(result.semanticUnits.any { unit ->
            unit is MethodLikeUnit && unit.title == "BranchService.sanitize"
        })
        assertTrue(result.semanticUnits.any { unit ->
            unit is MethodLikeUnit && unit.title == "GatewayImpl.fetch"
        })
        assertTrue(result.semanticUnits.any { unit ->
            unit is InvocationUnit && unit.targetSignature?.contains("BranchService.sanitize") == true
        })
        assertTrue(result.relations.any { relation ->
            relation.kind == SemanticRelationKind.CONTROL_FLOW && relation.label != null
        })
        assertTrue(result.relations.any { relation ->
            relation.kind == SemanticRelationKind.CONTROL_FLOW && relation.flowEdgeRole == FlowEdgeRole.CASE
        })
        assertTrue(result.relations.any { relation ->
            relation.kind == SemanticRelationKind.CONTROL_FLOW && relation.flowEdgeRole == FlowEdgeRole.DEFAULT
        })
    }

    fun testAnalyzeKotlinAccessorBuildsUnifiedSemanticUnits() {
        myFixture.configureByText(
            "AccessorService.kt",
            """
                package com.example

                class AccessorService {
                    var raw: String = "seed"
                        get() = normalize(<caret>field).trim()

                    private fun normalize(value: String): String {
                        return value.trim()
                    }
                }
            """.trimIndent(),
        )

        val handle = CaretSubjectLocator().locate(project, myFixture.editor)
        val codeHandle = assertInstanceOf(handle, CodeSubjectHandle::class.java)

        val result = KotlinCodeSemanticProvider().analyze(
            handle = codeHandle,
            capturePolicy = SemanticCapturePolicy(),
            budgetPolicy = TraversalBudgetPolicy(maxDownstreamDepth = 1, maxInvocationsPerUnit = 8),
        )

        assertEquals(codeHandle, result.subject)
        assertTrue(result.semanticUnits.any { unit ->
            unit is MethodLikeUnit && unit.signature == codeHandle.methodSignature
        })
        assertTrue(result.semanticUnits.any { unit ->
            unit is MethodLikeUnit && unit.title == "AccessorService.normalize"
        })
        assertTrue(result.semanticUnits.any { unit ->
            unit is FlowActionUnit && unit.title.contains("normalize(field)")
        })
        assertTrue(result.semanticUnits.any { unit ->
            unit is InvocationUnit && unit.targetSignature?.contains("AccessorService.normalize") == true
        })
        assertTrue(result.relations.any { relation -> relation.kind == SemanticRelationKind.INVOKES })
        assertTrue(result.sourceMappings.isNotEmpty())
    }

    fun testAnalyzeKotlinIfConditionBuildsExplicitConditionInvocation() {
        myFixture.configureByText(
            "GuardService.kt",
            """
                package com.example

                class GuardService(
                    private val policy: Policy = DefaultPolicy(),
                ) {
                    fun guard(input: String): String {
                        if (!<caret>policy.allow(input)) {
                            return input
                        }
                        return normalize(input)
                    }

                    private fun normalize(value: String): String = value.trim()
                }

                interface Policy {
                    fun allow(value: String): Boolean
                }

                class DefaultPolicy : Policy {
                    override fun allow(value: String): Boolean = value.isNotBlank()
                }
            """.trimIndent(),
        )

        val handle = CaretSubjectLocator().locate(project, myFixture.editor)
        val codeHandle = assertInstanceOf(handle, CodeSubjectHandle::class.java)

        val result = KotlinCodeSemanticProvider().analyze(
            handle = codeHandle,
            capturePolicy = SemanticCapturePolicy(),
            budgetPolicy = TraversalBudgetPolicy(maxDownstreamDepth = 1, maxInvocationsPerUnit = 8),
        )

        assertTrue(result.semanticUnits.any { unit ->
            unit is MethodLikeUnit && unit.signature == codeHandle.methodSignature
        })
        assertTrue(result.semanticUnits.any { unit ->
            unit is FlowScopeUnit && unit.scopeKind == "IF"
        })
        assertTrue(result.semanticUnits.any { unit ->
            unit is InvocationUnit && unit.targetSignature?.contains("Policy.allow") == true
        })
    }

    fun testAnalyzeKotlinDoWhileBuildsExplicitPostTestLoopRoles() {
        myFixture.configureByText(
            "LoopService.kt",
            """
                package com.example

                class LoopService {
                    fun flush(seed: Int): Int {
                        var current = <caret>seed
                        do {
                            current = normalize(current)
                        } while (current > 0)
                        return current
                    }

                    private fun normalize(value: Int): Int = value - 1
                }
            """.trimIndent(),
        )

        val handle = CaretSubjectLocator().locate(project, myFixture.editor)
        val codeHandle = assertInstanceOf(handle, CodeSubjectHandle::class.java)

        val result = KotlinCodeSemanticProvider().analyze(
            handle = codeHandle,
            capturePolicy = SemanticCapturePolicy(),
            budgetPolicy = TraversalBudgetPolicy(maxDownstreamDepth = 1, maxInvocationsPerUnit = 8),
        )

        val loopScope = result.semanticUnits
            .filterIsInstance<FlowScopeUnit>()
            .firstOrNull { unit -> unit.scopeKind == "DO_WHILE" }
        val loopBackEdge = result.relations.firstOrNull { relation ->
            relation.kind == SemanticRelationKind.CONTROL_FLOW && relation.flowEdgeRole == FlowEdgeRole.LOOP_BACK
        }
        val loopExitEdge = result.relations.firstOrNull { relation ->
            relation.kind == SemanticRelationKind.CONTROL_FLOW && relation.flowEdgeRole == FlowEdgeRole.LOOP_EXIT
        }

        assertTrue(loopScope != null)
        assertEquals(FlowScopeCategory.LOOP_POST_TEST, loopScope!!.scopeCategory)
        assertTrue(loopBackEdge != null)
        assertTrue(loopExitEdge != null)
        assertTrue(
            result.relations.any { relation ->
                relation.kind == SemanticRelationKind.CONTROL_FLOW && relation.flowEdgeRole == FlowEdgeRole.ENTRY
            },
        )
    }

    fun testAnalyzeInfiniteWhileLoopMarksMissingNormalExitAsIncompleteInsteadOfFakingLoopExit() {
        myFixture.configureByText(
            "LoopService.kt",
            """
                package com.example

                class LoopService {
                    fun <caret>spin() {
                        while (true) {
                            tick()
                        }
                    }

                    private fun tick() = Unit
                }
            """.trimIndent(),
        )

        val handle = CaretSubjectLocator().locate(project, myFixture.editor)
        val codeHandle = assertInstanceOf(handle, CodeSubjectHandle::class.java)

        val result = KotlinCodeSemanticProvider().analyze(
            handle = codeHandle,
            capturePolicy = SemanticCapturePolicy(),
            budgetPolicy = TraversalBudgetPolicy(maxDownstreamDepth = 1, maxInvocationsPerUnit = 8),
        )

        val loopScope = result.semanticUnits
            .filterIsInstance<FlowScopeUnit>()
            .firstOrNull { unit -> unit.scopeKind == "WHILE" }

        assertTrue(loopScope != null)
        assertTrue(loopScope!!.incomplete)
        assertTrue(
            result.relations.none { relation ->
                relation.kind == SemanticRelationKind.CONTROL_FLOW && relation.flowEdgeRole == FlowEdgeRole.LOOP_EXIT
            },
        )
    }
}
