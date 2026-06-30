package com.charmnight.linkgraph.agent.capability

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.codegen.CodeEditOperation
import com.charmnight.linkgraph.codegen.CodeEditOperationKind
import com.charmnight.linkgraph.codegen.CodeGenerationResult
import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.agent.model.EditScope
import com.charmnight.linkgraph.agent.model.GenerationPlan
import com.charmnight.linkgraph.agent.model.GenerationPlanSource
import com.charmnight.linkgraph.agent.model.SourceSnippetContext
import com.charmnight.linkgraph.agent.artifact.ConfirmedIntentArtifact
import com.charmnight.linkgraph.agent.artifact.InMemoryArtifactStore
import com.charmnight.linkgraph.agent.runtime.AgentRunCoordinator
import com.charmnight.linkgraph.agent.runtime.AgentRuntimeContext
import com.charmnight.linkgraph.agent.runtime.RunBudget
import com.charmnight.linkgraph.llm.tools.AgentTool
import com.charmnight.linkgraph.llm.tools.AgentToolRegistry
import com.charmnight.linkgraph.llm.tools.CheckWritableDraftTool
import com.charmnight.linkgraph.agent.tools.ToolExecutionContext
import com.charmnight.linkgraph.llm.tools.ToolResult
import com.charmnight.linkgraph.llm.tools.ValidateEditScopeTool
import com.charmnight.linkgraph.llm.tools.ValidationToolFacade
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.workbench.DraftEntryKind
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry
import com.charmnight.linkgraph.workbench.DraftWorkbenchState
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodegenCapabilityTest : BasePlatformTestCase() {
    fun testRuntimeBudgetUsesConfiguredCodegenTimeout() {
        val capability = CodegenCapability(
            project = project,
            defaultBudget = RunBudget(),
            codegenExecutor = { _, _, _ ->
                CodeGenerationResult(drafts = emptyList())
            },
        )

        val state = capability.buildInitialState(
            input = CodegenCapabilityInput(
                generationContext = com.charmnight.linkgraph.agent.model.GenerationContext(),
                plan = null,
                settings = LinkGraphSettingsState(timeoutSeconds = 3_600),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = { testSnapshot().toToolGraphSnapshot() },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertEquals(3_600, state.budget.maxRuntimeSeconds)
        assertEquals(10, state.budget.maxSteps)
    }

    fun testRejectsExistingFileDraftWhenNoValidatedScopeExists() {
        val capability = CodegenCapability(
            project = project,
            defaultBudget = RunBudget(),
            codegenExecutor = { _, _, _ ->
                CodeGenerationResult(
                    drafts = listOf(
                        GeneratedCodeDraft(
                            id = "draft-1",
                            sourceNodeId = "method:upload-file",
                            title = "rewrite upload",
                            targetPath = "src/main/java/com/example/CommonController.java",
                            editOperations = listOf(
                                CodeEditOperation(
                                    operationId = "op-1",
                                    filePath = "src/main/java/com/example/CommonController.java",
                                    scopeId = "scope-upload",
                                    kind = CodeEditOperationKind.REPLACE_METHOD_BODY,
                                    payload = "return;",
                                ),
                            ),
                        ),
                    ),
                )
            },
        )

        val result = AgentRunCoordinator().run(
            capability = capability,
            input = CodegenCapabilityInput(
                generationContext = com.charmnight.linkgraph.agent.model.GenerationContext(),
                plan = GenerationPlan(
                    source = GenerationPlanSource.LOCAL_RULE,
                    summary = "rewrite upload",
                ),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = {
                    testSnapshot(
                        draftWorkbenchState = DraftWorkbenchState(
                            draftChanges = listOf(
                                DraftWorkbenchEntry(
                                    entryId = "draft-confirmed",
                                    kind = DraftEntryKind.CHANGE,
                                    title = "rewrite upload",
                                ),
                            ),
                        ),
                    ).toToolGraphSnapshot()
                },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertEquals(null, result.output)
        assertNotNull(result.finalState.failureReason)
    }

    fun testPassesRuntimeReadSourceEvidenceToCodegenExecutorInsteadOfPreloadedSnippet() {
        val sourceFile = java.nio.file.Path.of(requireNotNull(project.basePath))
            .resolve("src/main/java/com/example/CodegenRuntimeEvidenceController.java")
        Files.createDirectories(sourceFile.parent)
        Files.writeString(
            sourceFile,
            """
            class CommonController {
                void uploadFile(String file) {
                    validate(file);
                }

                void validate(String file) {}
            }
            """.trimIndent(),
        )
        val scope = EditScope(
            scopeId = "scope-upload",
            targetNodeId = "method:upload-file",
            filePath = sourceFile.toString(),
            language = "JAVA",
            symbolKind = "METHOD",
            symbolSignature = "com.example.CommonController.uploadFile(java.lang.String):void",
            startLine = 1,
            endLine = 4,
            allowedChangeKinds = listOf("REPLACE_METHOD_BODY"),
        )
        var executorSourceContext: List<com.charmnight.linkgraph.agent.model.SourceSnippetContext> = emptyList()
        val capability = CodegenCapability(
            project = project,
            defaultBudget = RunBudget(),
            codegenExecutor = { input, _, _ ->
                executorSourceContext = input.generationContext.sourceContext
                CodeGenerationResult(
                    drafts = listOf(
                        GeneratedCodeDraft(
                            id = "draft-1",
                            sourceNodeId = "method:upload-file",
                            title = "rewrite upload",
                            targetPath = sourceFile.toString(),
                            editOperations = listOf(
                                CodeEditOperation(
                                    operationId = "op-1",
                                    filePath = sourceFile.toString(),
                                    scopeId = "scope-upload",
                                    kind = CodeEditOperationKind.REPLACE_METHOD_BODY,
                                    payload = "return;",
                                ),
                            ),
                            editScopes = listOf(scope),
                        ),
                    ),
                )
            },
        )

        val result = AgentRunCoordinator().run(
            capability = capability,
            input = CodegenCapabilityInput(
                generationContext = com.charmnight.linkgraph.agent.model.GenerationContext(
                    graph = GraphDocument(),
                    sourceContext = listOf(
                        com.charmnight.linkgraph.agent.model.SourceSnippetContext(
                            nodeId = "method:upload-file",
                            filePath = sourceFile.toString(),
                            startLine = 1,
                            endLine = 4,
                            snippet = "PRELOADED_SNIPPET_SHOULD_NOT_BE_USED",
                        ),
                    ),
                    confirmedChanges = listOf(
                        DraftWorkbenchEntry(
                            entryId = "draft-confirmed",
                            kind = DraftEntryKind.CHANGE,
                            title = "rewrite upload",
                            editScopes = listOf(scope),
                        ),
                    ),
                ),
                plan = GenerationPlan(
                    source = GenerationPlanSource.LOCAL_RULE,
                    summary = "rewrite upload",
                ),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = {
                    testSnapshot(
                        draftWorkbenchState = DraftWorkbenchState(
                            draftChanges = listOf(
                                DraftWorkbenchEntry(
                                    entryId = "draft-confirmed",
                                    kind = DraftEntryKind.CHANGE,
                                    title = "rewrite upload",
                                    editScopes = listOf(scope),
                                ),
                            ),
                        ),
                    ).toToolGraphSnapshot()
                },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertEquals(1, result.output?.drafts?.size)
        assertEquals(1, executorSourceContext.size)
        assertTrue(executorSourceContext.single().snippet?.contains("validate(file);") == true)
        assertTrue(executorSourceContext.single().snippet?.contains("PRELOADED_SNIPPET_SHOULD_NOT_BE_USED") == false)
    }

    fun testUsesValidationToolsOnMainCodegenPath() {
        var validateScopeCalled = false
        var checkWritableCalled = false
        val capability = CodegenCapability(
            project = project,
            defaultBudget = RunBudget(),
            codegenExecutor = { _, _, _ ->
                CodeGenerationResult(
                    drafts = listOf(
                        GeneratedCodeDraft(
                            id = "draft-1",
                            sourceNodeId = "method:upload-file",
                            title = "new file draft",
                            targetPath = "src/main/java/com/example/UploadDraft.java",
                            content = "class UploadDraft {}",
                        ),
                    ),
                )
            },
            toolRegistry = AgentToolRegistry(
                listOf(
                    object : AgentTool {
                        override val name: String = "get_confirmed_intent"
                        override val description: String = "fake confirmed intent"

                        override fun invoke(
                            input: Map<String, Any?>,
                            context: ToolExecutionContext,
                        ): ToolResult {
                            return ToolResult(
                                toolName = name,
                                payload = mapOf(
                                    "confirmedCount" to 1,
                                    "confirmedIntents" to emptyList<Any>(),
                                    "artifactRefs" to emptyList<Any>(),
                                ),
                            )
                        }
                    },
                    object : AgentTool {
                        override val name: String = "read_source_snippet"
                        override val description: String = "fake source reader"

                        override fun invoke(
                            input: Map<String, Any?>,
                            context: ToolExecutionContext,
                        ): ToolResult {
                            return ToolResult(toolName = name, payload = mapOf("snippet" to "class UploadDraft {}"))
                        }
                    },
                    object : AgentTool {
                        override val name: String = "validate_edit_scope"
                        override val description: String = "fake validate edit scope"

                        override fun invoke(
                            input: Map<String, Any?>,
                            context: ToolExecutionContext,
                        ): ToolResult {
                            validateScopeCalled = true
                            return ToolResult(toolName = name, payload = mapOf("valid" to true))
                        }
                    },
                    object : AgentTool {
                        override val name: String = "check_writable_draft"
                        override val description: String = "fake writable draft"

                        override fun invoke(
                            input: Map<String, Any?>,
                            context: ToolExecutionContext,
                        ): ToolResult {
                            checkWritableCalled = true
                            return ToolResult(toolName = name, payload = mapOf("writable" to true))
                        }
                    },
                ),
            ),
        )

        val result = AgentRunCoordinator().run(
            capability = capability,
            input = CodegenCapabilityInput(
                generationContext = com.charmnight.linkgraph.agent.model.GenerationContext(
                    confirmedChanges = listOf(
                        DraftWorkbenchEntry(
                            entryId = "draft-confirmed",
                            kind = DraftEntryKind.CHANGE,
                            title = "rewrite upload",
                        ),
                    ),
                ),
                plan = null,
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = {
                    testSnapshot(
                        draftWorkbenchState = DraftWorkbenchState(
                            draftChanges = listOf(
                                DraftWorkbenchEntry(
                                    entryId = "draft-confirmed",
                                    kind = DraftEntryKind.CHANGE,
                                    title = "rewrite upload",
                                ),
                            ),
                        ),
                    ).toToolGraphSnapshot()
                },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertEquals(1, result.output?.drafts?.size)
        assertTrue(validateScopeCalled)
        assertTrue(checkWritableCalled)
    }

    fun testUsesConfirmedIntentArtifactsAsCodegenInputInsteadOfInitialGenerationContext() {
        val runtimeScope = EditScope(
            scopeId = "scope-runtime",
            targetNodeId = "method:upload-file",
            filePath = "src/main/java/com/example/CommonController.java",
            language = "JAVA",
            symbolKind = "METHOD",
            symbolSignature = "com.example.CommonController.uploadFile(java.lang.String):void",
            startLine = 10,
            endLine = 20,
            allowedChangeKinds = listOf("REPLACE_METHOD_BODY"),
        )
        val runtimeEntry = DraftWorkbenchEntry(
            entryId = "draft-runtime",
            kind = DraftEntryKind.CHANGE,
            title = "runtime confirmed",
            editScopes = listOf(runtimeScope),
        )
        val staleEntry = DraftWorkbenchEntry(
            entryId = "draft-stale",
            kind = DraftEntryKind.CHANGE,
            title = "stale confirmed",
        )
        var capturedConfirmedChanges: List<DraftWorkbenchEntry> = emptyList()
        var capturedSourceContext: List<SourceSnippetContext> = emptyList()
        val capability = CodegenCapability(
            project = project,
            defaultBudget = RunBudget(),
            codegenExecutor = { input, _, _ ->
                capturedConfirmedChanges = input.generationContext.confirmedChanges
                capturedSourceContext = input.generationContext.sourceContext
                CodeGenerationResult(
                    drafts = listOf(
                        GeneratedCodeDraft(
                            id = "draft-1",
                            sourceNodeId = "method:upload-file",
                            title = "rewrite upload",
                            targetPath = runtimeScope.filePath,
                            editOperations = listOf(
                                CodeEditOperation(
                                    operationId = "op-1",
                                    filePath = runtimeScope.filePath,
                                    scopeId = runtimeScope.scopeId,
                                    kind = CodeEditOperationKind.REPLACE_METHOD_BODY,
                                    payload = "return;",
                                ),
                            ),
                            editScopes = listOf(runtimeScope),
                        ),
                    ),
                )
            },
            toolRegistry = AgentToolRegistry(
                listOf(
                    object : AgentTool {
                        override val name: String = "get_confirmed_intent"
                        override val description: String = "fake confirmed intent"

                        override fun invoke(
                            input: Map<String, Any?>,
                            context: ToolExecutionContext,
                        ): ToolResult {
                            val ref = context.artifactStore.save(
                                ConfirmedIntentArtifact(
                                    artifactId = "confirmed-runtime",
                                    entry = runtimeEntry,
                                ),
                            )
                            return ToolResult(
                                toolName = name,
                                payload = mapOf(
                                    "confirmedCount" to 1,
                                    "confirmedIntents" to listOf(runtimeEntry),
                                    "artifactRefs" to listOf(ref),
                                ),
                            )
                        }
                    },
                    object : AgentTool {
                        override val name: String = "read_source_snippet"
                        override val description: String = "fake source snippet"

                        override fun invoke(
                            input: Map<String, Any?>,
                            context: ToolExecutionContext,
                        ): ToolResult {
                            return ToolResult(
                                toolName = name,
                                payload = mapOf(
                                    "snippet" to "RUNTIME_SNIPPET",
                                ),
                            )
                        }
                    },
                    object : AgentTool {
                        override val name: String = "validate_edit_scope"
                        override val description: String = "fake validate edit scope"

                        override fun invoke(
                            input: Map<String, Any?>,
                            context: ToolExecutionContext,
                        ): ToolResult {
                            return ToolResult(toolName = name, payload = mapOf("valid" to true))
                        }
                    },
                    object : AgentTool {
                        override val name: String = "check_writable_draft"
                        override val description: String = "fake writable draft"

                        override fun invoke(
                            input: Map<String, Any?>,
                            context: ToolExecutionContext,
                        ): ToolResult {
                            return ToolResult(toolName = name, payload = mapOf("writable" to true))
                        }
                    },
                ),
            ),
        )

        val result = AgentRunCoordinator().run(
            capability = capability,
            input = CodegenCapabilityInput(
                generationContext = com.charmnight.linkgraph.agent.model.GenerationContext(
                    confirmedChanges = listOf(staleEntry),
                    sourceContext = listOf(
                        SourceSnippetContext(
                            nodeId = "method:upload-file",
                            filePath = runtimeScope.filePath,
                            startLine = runtimeScope.startLine,
                            endLine = runtimeScope.endLine,
                            snippet = "STALE_PRELOADED_SNIPPET",
                        ),
                    ),
                ),
                plan = GenerationPlan(
                    source = GenerationPlanSource.LOCAL_RULE,
                    summary = "rewrite upload",
                ),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = { testSnapshot().toToolGraphSnapshot() },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertEquals(1, result.output?.drafts?.size)
        assertEquals(listOf(runtimeEntry), capturedConfirmedChanges)
        assertEquals("RUNTIME_SNIPPET", capturedSourceContext.singleOrNull()?.snippet)
    }

    fun testStopsReadingAdditionalFilesWithinSameCodegenStepAfterBudgetIsExhausted() {
        var readCount = 0
        var executorInvoked = false
        val firstScope = EditScope(
            scopeId = "scope-1",
            targetNodeId = "method:first",
            filePath = "src/main/java/com/example/First.java",
            language = "JAVA",
            symbolKind = "METHOD",
            startLine = 1,
            endLine = 2,
        )
        val secondScope = EditScope(
            scopeId = "scope-2",
            targetNodeId = "method:second",
            filePath = "src/main/java/com/example/Second.java",
            language = "JAVA",
            symbolKind = "METHOD",
            startLine = 1,
            endLine = 2,
        )
        val capability = CodegenCapability(
            project = project,
            defaultBudget = RunBudget(maxFilesRead = 1),
            codegenExecutor = { _, _, _ ->
                executorInvoked = true
                CodeGenerationResult(drafts = emptyList())
            },
            toolRegistry = AgentToolRegistry(
                listOf(
                    object : AgentTool {
                        override val name: String = "get_confirmed_intent"
                        override val description: String = "fake confirmed intent"

                        override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
                            val refs = listOf(firstScope, secondScope).mapIndexed { index, scope ->
                                context.artifactStore.save(
                                    ConfirmedIntentArtifact(
                                        artifactId = "confirmed-$index",
                                        entry = DraftWorkbenchEntry(
                                            entryId = "draft-$index",
                                            kind = DraftEntryKind.CHANGE,
                                            title = "change-$index",
                                            editScopes = listOf(scope),
                                        ),
                                    ),
                                )
                            }
                            return ToolResult(
                                toolName = name,
                                payload = mapOf(
                                    "confirmedCount" to 2,
                                    "confirmedIntents" to emptyList<Any>(),
                                    "artifactRefs" to refs,
                                ),
                            )
                        }
                    },
                    object : AgentTool {
                        override val name: String = "read_source_snippet"
                        override val description: String = "fake source reader"

                        override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
                            readCount += 1
                            return ToolResult(toolName = name, payload = mapOf("snippet" to "class CodegenBudget {}"))
                        }
                    },
                    ValidateEditScopeTool(ValidationToolFacade()),
                    CheckWritableDraftTool(ValidationToolFacade()),
                ),
            ),
        )

        val result = AgentRunCoordinator().run(
            capability = capability,
            input = CodegenCapabilityInput(
                generationContext = com.charmnight.linkgraph.agent.model.GenerationContext(),
                plan = null,
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = { testSnapshot().toToolGraphSnapshot() },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertFalse(executorInvoked)
        assertEquals(1, readCount)
        assertNull(result.output)
        assertEquals(com.charmnight.linkgraph.agent.runtime.AgentRunFailureReason.MAX_FILES_READ_EXCEEDED, result.finalState.failureReason)
    }

    fun testRejectsInvalidExistingFileScopeBeforeCallingCodegenExecutor() {
        val sourceFile = Files.createTempFile("codegen-prevalidate-scope", ".java")
        Files.writeString(sourceFile, "class UploadController { void upload() {} }")
        var executorInvoked = false
        val scope = EditScope(
            scopeId = "scope-invalid",
            targetNodeId = "method:upload-file",
            filePath = sourceFile.toString(),
            language = "JAVA",
            symbolKind = "METHOD",
            startLine = 1,
            endLine = 1,
            allowedChangeKinds = listOf("REPLACE_METHOD_BODY"),
        )
        val capability = CodegenCapability(
            project = project,
            defaultBudget = RunBudget(),
            codegenExecutor = { _, _, _ ->
                executorInvoked = true
                CodeGenerationResult(
                    drafts = listOf(
                        GeneratedCodeDraft(
                            id = "draft-1",
                            sourceNodeId = "method:upload-file",
                            title = "new file draft",
                            targetPath = "src/main/java/com/example/UploadDraft.java",
                            content = "class UploadDraft {}",
                        ),
                    ),
                )
            },
            toolRegistry = AgentToolRegistry(
                listOf(
                    object : AgentTool {
                        override val name: String = "get_confirmed_intent"
                        override val description: String = "fake confirmed intent"

                        override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
                            val ref = context.artifactStore.save(
                                ConfirmedIntentArtifact(
                                    artifactId = "confirmed-invalid",
                                    entry = DraftWorkbenchEntry(
                                        entryId = "draft-invalid",
                                        kind = DraftEntryKind.CHANGE,
                                        title = "invalid existing-file change",
                                        editScopes = listOf(scope),
                                    ),
                                ),
                            )
                            return ToolResult(
                                toolName = name,
                                payload = mapOf(
                                    "confirmedCount" to 1,
                                    "confirmedIntents" to emptyList<Any>(),
                                    "artifactRefs" to listOf(ref),
                                ),
                            )
                        }
                    },
                    object : AgentTool {
                        override val name: String = "read_source_snippet"
                        override val description: String = "fake source reader"

                        override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
                            return ToolResult(
                                toolName = name,
                                payload = mapOf("snippet" to "class UploadController { void upload() {} }"),
                            )
                        }
                    },
                    object : AgentTool {
                        override val name: String = "validate_edit_scope"
                        override val description: String = "fake invalid scope validator"

                        override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
                            return ToolResult(toolName = name, payload = mapOf("valid" to false))
                        }
                    },
                    object : AgentTool {
                        override val name: String = "check_writable_draft"
                        override val description: String = "fake writable checker"

                        override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
                            return ToolResult(toolName = name, payload = mapOf("writable" to true))
                        }
                    },
                ),
            ),
        )

        val result = AgentRunCoordinator().run(
            capability = capability,
            input = CodegenCapabilityInput(
                generationContext = com.charmnight.linkgraph.agent.model.GenerationContext(),
                plan = GenerationPlan(
                    source = GenerationPlanSource.LOCAL_RULE,
                    summary = "rewrite upload",
                ),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = { testSnapshot().toToolGraphSnapshot() },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertFalse(executorInvoked)
        assertNull(result.output)
        assertEquals(com.charmnight.linkgraph.agent.runtime.AgentRunFailureReason.EVIDENCE_INSUFFICIENT, result.finalState.failureReason)
    }

    fun testSkipsProjectExternalConfirmedIntentEvidence() {
        val sourceFile = Files.createTempFile("codegen-external-evidence", ".java")
        Files.writeString(
            sourceFile,
            """
            class CodegenExternalEvidence {
                void uploadFile(String file) {
                    validate(file);
                }
                void validate(String file) {}
            }
            """.trimIndent(),
        )
        val scope = EditScope(
            scopeId = "scope-upload",
            targetNodeId = "method:upload-file",
            filePath = sourceFile.toString(),
            language = "JAVA",
            symbolKind = "METHOD",
            symbolSignature = "com.example.CodegenExternalEvidence.uploadFile(java.lang.String):void",
            startLine = 1,
            endLine = 4,
            allowedChangeKinds = listOf("REPLACE_METHOD_BODY"),
        )
        var executorInvoked = false
        val capability = CodegenCapability(
            project = project,
            defaultBudget = RunBudget(),
            codegenExecutor = { _, _, _ ->
                executorInvoked = true
                CodeGenerationResult(
                    drafts = listOf(
                        GeneratedCodeDraft(
                            id = "draft-1",
                            sourceNodeId = "method:upload-file",
                            title = "new file draft",
                            targetPath = "src/main/java/com/example/UploadDraft.java",
                            content = "class UploadDraft {}",
                        ),
                    ),
                )
            },
        )

        val result = AgentRunCoordinator().run(
            capability = capability,
            input = CodegenCapabilityInput(
                generationContext = com.charmnight.linkgraph.agent.model.GenerationContext(
                    graph = GraphDocument(),
                    confirmedChanges = listOf(
                        DraftWorkbenchEntry(
                            entryId = "draft-confirmed",
                            kind = DraftEntryKind.CHANGE,
                            title = "rewrite upload",
                            editScopes = listOf(scope),
                        ),
                    ),
                ),
                plan = GenerationPlan(
                    source = GenerationPlanSource.LOCAL_RULE,
                    summary = "rewrite upload",
                ),
            ),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = {
                    testSnapshot(
                        draftWorkbenchState = DraftWorkbenchState(
                            draftChanges = listOf(
                                DraftWorkbenchEntry(
                                    entryId = "draft-confirmed",
                                    kind = DraftEntryKind.CHANGE,
                                    title = "rewrite upload",
                                    editScopes = listOf(scope),
                                ),
                            ),
                        ),
                    ).toToolGraphSnapshot()
                },
                artifactStore = InMemoryArtifactStore(),
            ),
        )

        assertFalse(executorInvoked)
        assertNull(result.output)
        assertEquals(com.charmnight.linkgraph.agent.runtime.AgentRunFailureReason.EVIDENCE_INSUFFICIENT, result.finalState.failureReason)
    }
}
