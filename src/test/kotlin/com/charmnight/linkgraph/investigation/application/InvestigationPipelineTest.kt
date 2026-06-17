package com.charmnight.linkgraph.investigation.application

import com.charmnight.linkgraph.investigation.application.EvidenceGoalKind
import com.charmnight.linkgraph.investigation.application.GateDecision
import com.charmnight.linkgraph.investigation.application.InvestigationRequest
import com.charmnight.linkgraph.investigation.application.InvestigationStatus
import com.charmnight.linkgraph.investigation.application.InvestigationTargetHint
import com.charmnight.linkgraph.investigation.application.EvidenceGoalPlanner
import com.charmnight.linkgraph.investigation.application.InvestigationSummaryProjector
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InvestigationPipelineTest : BasePlatformTestCase() {
    fun testPipelineAcceptsResolvedEnumConstantEvidence() {
        myFixture.configureByText(
            "TaskQueueEventType.java",
            """
                package com.example.queue;

                enum TaskQueueEventType {
                    ADD,
                    REMOVE
                }
            """.trimIndent(),
        )
        val pipeline = InvestigationPipeline.default(project)

        val result = pipeline.run(
            InvestigationRequest(
                threadId = "thread-add-event-type",
                question = "请继续取证：定位 TaskQueueEventType 的 ADD 取值。",
                title = "TaskQueueEventType ADD 分支待确认",
                summary = "需要确认 TaskQueueEventType 是否包含 ADD。",
                evidenceGap = "缺少 TaskQueueEventType.ADD 的直接源码证据。",
                recommendedQuestion = "请继续取证：定位 TaskQueueEventType 的 ADD 取值。",
            ),
        )

        assertEquals(InvestigationStatus.RESOLVED, result.status)
        assertEquals(1, result.acceptedFacts.size)
        assertEquals("com.example.queue.TaskQueueEventType.ADD", result.acceptedFacts.single().symbolSignature)
        assertTrue(result.acceptedFacts.single().filePath.endsWith("TaskQueueEventType.java"))
        assertTrue(result.acceptedFacts.single().startLine != null)
        assertTrue(result.summary.contains("已确认"))
    }

    fun testPipelineDoesNotCallSummaryWhenGateHasNoAcceptedEvidence() {
        var summaryCalled = false
        val pipeline = InvestigationPipeline.default(
            project = project,
            summaryService = object : InvestigationSummaryProjector {
                override fun summarize(
                    request: InvestigationRequest,
                    gateDecision: GateDecision.Accepted,
                ): String {
                    summaryCalled = true
                    return "不应该被调用"
                }
            },
        )

        val result = pipeline.run(
            InvestigationRequest(
                threadId = "thread-missing-event-type",
                question = "请继续取证：定位 MissingEventType 的 ADD 取值。",
                title = "MissingEventType ADD 分支待确认",
                evidenceGap = "缺少 MissingEventType.ADD 的直接源码证据。",
                recommendedQuestion = "请继续取证：定位 MissingEventType 的 ADD 取值。",
            ),
        )

        assertEquals(InvestigationStatus.NEEDS_MORE_EVIDENCE, result.status)
        assertTrue(result.acceptedFacts.isEmpty())
        assertTrue(result.requiredEvidence.isNotEmpty())
        assertEquals(false, summaryCalled)
        assertTrue(result.summary.contains("没有拿到可进入 LLM 上下文的直接证据"))
    }

    fun testPipelineReportsCandidatesWhenEnumClassNameIsAmbiguous() {
        myFixture.addFileToProject(
            "src/main/java/com/example/a/TaskQueueEventType.java",
            """
                package com.example.a;

                enum TaskQueueEventType {
                    ADD
                }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/b/TaskQueueEventType.java",
            """
                package com.example.b;

                enum TaskQueueEventType {
                    ADD
                }
            """.trimIndent(),
        )
        val pipeline = InvestigationPipeline.default(project)

        val result = pipeline.run(
            InvestigationRequest(
                threadId = "thread-ambiguous-event-type",
                question = "请继续取证：定位 TaskQueueEventType.ADD。",
                evidenceGap = "缺少 TaskQueueEventType.ADD 的直接源码证据。",
            ),
        )

        assertEquals(InvestigationStatus.NEEDS_MORE_EVIDENCE, result.status)
        assertTrue(result.acceptedFacts.isEmpty())
        assertEquals(2, result.candidates.size)
        assertTrue(result.requiredEvidence.any { evidence -> evidence.contains("全限定") })
    }

    fun testPlannerDoesNotCreateSearchGoalFromBareKeyword() {
        val goals = EvidenceGoalPlanner().plan(
            InvestigationRequest(
                threadId = "thread-bare-keyword",
                question = "请继续取证：ADD 分支到底在哪里进入。",
                evidenceGap = "缺少 ADD 的直接源码证据。",
            ),
        )

        assertEquals(1, goals.size)
        assertEquals(EvidenceGoalKind.UNKNOWN, goals.single().kind)
        assertEquals(null, goals.single().symbolName)
        assertEquals(null, goals.single().memberName)
    }

    fun testPlannerUsesStructuredTargetHintSignature() {
        val goals = EvidenceGoalPlanner().plan(
            InvestigationRequest(
                threadId = "thread-target-hint",
                question = "请继续取证：当前节点关联的事件类型。",
                targetHints = listOf(
                    InvestigationTargetHint(
                        nodeId = "method:add-pool",
                        title = "PoolService.addPool",
                        signature = "com.example.queue.TaskQueueEventType.ADD",
                    ),
                ),
            ),
        )

        assertEquals(1, goals.size)
        assertEquals(EvidenceGoalKind.ENUM_CONSTANT, goals.single().kind)
        assertEquals("TaskQueueEventType", goals.single().symbolName)
        assertEquals("ADD", goals.single().memberName)
    }

    fun testPipelineResolvesExactOverloadedMethodSignature() {
        myFixture.addFileToProject(
            "src/main/java/com/example/OverloadedWorker.java",
            """
                package com.example;

                class OverloadedWorker {
                    void run(String value) {}
                    void run(Integer value) {}
                }
            """.trimIndent(),
        )
        val pipeline = InvestigationPipeline.default(project)

        val result = pipeline.run(
            InvestigationRequest(
                threadId = "thread-overloaded-method",
                question = "请继续取证：定位重载方法。",
                targetHints = listOf(
                    InvestigationTargetHint(
                        nodeId = "method:worker-run",
                        signature = "com.example.OverloadedWorker.run(java.lang.String):void",
                    ),
                ),
            ),
        )

        assertEquals(InvestigationStatus.RESOLVED, result.status)
        assertEquals("com.example.OverloadedWorker.run(java.lang.String):void", result.acceptedFacts.single().symbolSignature)
    }

    fun testPipelineBlocksBareOverloadedMethodName() {
        myFixture.addFileToProject(
            "src/main/java/com/example/OverloadedWorker.java",
            """
                package com.example;

                class OverloadedWorker {
                    void run(String value) {}
                    void run(Integer value) {}
                }
            """.trimIndent(),
        )
        val pipeline = InvestigationPipeline.default(project)

        val result = pipeline.run(
            InvestigationRequest(
                threadId = "thread-overloaded-method-name",
                question = "请继续取证：定位 OverloadedWorker.run。",
                evidenceGap = "缺少 OverloadedWorker.run 的直接源码证据。",
            ),
        )

        assertEquals(InvestigationStatus.NEEDS_MORE_EVIDENCE, result.status)
        assertEquals(2, result.candidates.size)
        assertTrue(result.requiredEvidence.any { evidence -> evidence.contains("完整方法签名") })
    }

    fun testPipelineResolvesSingleInterfaceOverrideImplementation() {
        myFixture.addFileToProject(
            "src/main/java/com/example/Task.java",
            """
                package com.example;

                class Task {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/TaskHandler.java",
            """
                package com.example;

                interface TaskHandler {
                    void handle(Task task);
                }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/DefaultTaskHandler.java",
            """
                package com.example;

                class DefaultTaskHandler implements TaskHandler {
                    @Override
                    public void handle(Task task) {}
                }
            """.trimIndent(),
        )
        val pipeline = InvestigationPipeline.default(project)

        val result = pipeline.run(
            InvestigationRequest(
                threadId = "thread-override",
                question = "请继续取证：确认 TaskHandler.handle(com.example.Task):void 的实现/重写。",
            ),
        )

        assertEquals(InvestigationStatus.RESOLVED, result.status)
        assertEquals("com.example.DefaultTaskHandler.handle(com.example.Task):void", result.acceptedFacts.single().symbolSignature)
    }

    fun testPipelineBlocksMultipleInterfaceOverrideImplementations() {
        myFixture.addFileToProject(
            "src/main/java/com/example/Task.java",
            """
                package com.example;

                class Task {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/TaskHandler.java",
            """
                package com.example;

                interface TaskHandler {
                    void handle(Task task);
                }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/DefaultTaskHandler.java",
            """
                package com.example;

                class DefaultTaskHandler implements TaskHandler {
                    @Override
                    public void handle(Task task) {}
                }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/FallbackTaskHandler.java",
            """
                package com.example;

                class FallbackTaskHandler implements TaskHandler {
                    @Override
                    public void handle(Task task) {}
                }
            """.trimIndent(),
        )
        val pipeline = InvestigationPipeline.default(project)

        val result = pipeline.run(
            InvestigationRequest(
                threadId = "thread-multiple-overrides",
                question = "请继续取证：确认 TaskHandler.handle(com.example.Task):void 的实现/重写。",
            ),
        )

        assertEquals(InvestigationStatus.NEEDS_MORE_EVIDENCE, result.status)
        assertEquals(2, result.candidates.size)
        assertTrue(result.requiredEvidence.any { evidence -> evidence.contains("运行时") || evidence.contains("注入") })
    }

    fun testPipelineResolvesJavaSpiBinding() {
        myFixture.addFileToProject(
            "src/main/java/com/example/spi/TaskProvider.java",
            """
                package com.example.spi;

                public interface TaskProvider {
                    void provide();
                }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/spi/DefaultTaskProvider.java",
            """
                package com.example.spi;

                public class DefaultTaskProvider implements TaskProvider {
                    @Override
                    public void provide() {}
                }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/resources/META-INF/services/com.example.spi.TaskProvider",
            "com.example.spi.DefaultTaskProvider\n",
        )
        val pipeline = InvestigationPipeline.default(project)

        val result = pipeline.run(
            InvestigationRequest(
                threadId = "thread-spi",
                question = "请继续取证：确认 SPI com.example.spi.TaskProvider 的 provider。",
            ),
        )

        assertEquals(InvestigationStatus.RESOLVED, result.status)
        assertTrue(result.acceptedFacts.any { fact -> fact.level.name == "CONFIG_RESOLVED" })
        assertTrue(result.acceptedFacts.any { fact -> fact.symbolSignature == "com.example.spi.DefaultTaskProvider" })
    }

    fun testPipelineBlocksMissingJavaSpiBinding() {
        myFixture.addFileToProject(
            "src/main/java/com/example/spi/TaskProvider.java",
            """
                package com.example.spi;

                public interface TaskProvider {
                    void provide();
                }
            """.trimIndent(),
        )
        val pipeline = InvestigationPipeline.default(project)

        val result = pipeline.run(
            InvestigationRequest(
                threadId = "thread-missing-spi",
                question = "请继续取证：确认 SPI com.example.spi.TaskProvider 的 provider。",
            ),
        )

        assertEquals(InvestigationStatus.NEEDS_MORE_EVIDENCE, result.status)
        assertTrue(result.acceptedFacts.isEmpty())
        assertTrue(result.requiredEvidence.any { evidence -> evidence.contains("META-INF/services") })
    }

    fun testPipelineResolvesConstantReflectionCall() {
        myFixture.addFileToProject(
            "src/main/java/com/example/ReflectiveCaller.java",
            """
                package com.example;

                class ReflectiveCaller {
                    void call() throws Exception {
                        Class<?> type = Class.forName("com.example.TargetService");
                        type.getMethod("execute", String.class);
                    }
                }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/TargetService.java",
            """
                package com.example;

                class TargetService {
                    public void execute(String value) {}
                }
            """.trimIndent(),
        )
        val pipeline = InvestigationPipeline.default(project)

        val result = pipeline.run(
            InvestigationRequest(
                threadId = "thread-reflection",
                question = "请继续取证：确认反射调用。",
                targetHints = listOf(
                    InvestigationTargetHint(
                        nodeId = "method:reflective-call",
                        signature = "com.example.ReflectiveCaller.call():void",
                    ),
                ),
            ),
        )

        assertEquals(InvestigationStatus.RESOLVED, result.status)
        assertTrue(result.acceptedFacts.any { fact ->
            fact.symbolSignature == "com.example.TargetService.execute(java.lang.String):void"
        })
    }

    fun testPipelineBlocksDynamicReflectionCall() {
        myFixture.addFileToProject(
            "src/main/java/com/example/ReflectiveCaller.java",
            """
                package com.example;

                class ReflectiveCaller {
                    void call(String className, String methodName) throws Exception {
                        Class<?> type = Class.forName(className);
                        type.getMethod(methodName);
                    }
                }
            """.trimIndent(),
        )
        val pipeline = InvestigationPipeline.default(project)

        val result = pipeline.run(
            InvestigationRequest(
                threadId = "thread-dynamic-reflection",
                question = "请继续取证：确认反射调用。",
                targetHints = listOf(
                    InvestigationTargetHint(
                        nodeId = "method:reflective-call",
                        signature = "com.example.ReflectiveCaller.call(java.lang.String,java.lang.String):void",
                    ),
                ),
            ),
        )

        assertEquals(InvestigationStatus.NEEDS_MORE_EVIDENCE, result.status)
        assertTrue(result.acceptedFacts.isEmpty())
        assertTrue(result.requiredEvidence.any { evidence -> evidence.contains("反射") || evidence.contains("运行时") })
    }

    fun testPipelineResolvesSpringEventListener() {
        myFixture.addFileToProject(
            "src/main/java/com/example/event/OrderCreatedEvent.java",
            """
                package com.example.event;

                public class OrderCreatedEvent {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/event/EventPublisher.java",
            """
                package com.example.event;

                class EventPublisher {
                    private final org.springframework.context.ApplicationEventPublisher publisher = null;

                    void publish() {
                        publisher.publishEvent(new OrderCreatedEvent());
                    }
                }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/event/SearchIndexListener.java",
            """
                package com.example.event;

                class SearchIndexListener {
                    @org.springframework.context.event.EventListener
                    public void onEvent(OrderCreatedEvent event) {}
                }
            """.trimIndent(),
        )
        val pipeline = InvestigationPipeline.default(project)

        val result = pipeline.run(
            InvestigationRequest(
                threadId = "thread-spring-event",
                question = "请继续取证：确认 Event OrderCreatedEvent 的监听器。",
                targetHints = listOf(
                    InvestigationTargetHint(
                        nodeId = "method:event-publish",
                        signature = "com.example.event.EventPublisher.publish():void",
                    ),
                ),
            ),
        )

        assertEquals(InvestigationStatus.RESOLVED, result.status)
        assertTrue(result.acceptedFacts.any { fact ->
            fact.symbolSignature == "com.example.event.SearchIndexListener.onEvent(com.example.event.OrderCreatedEvent):void"
        })
    }

    fun testPipelineDoesNotTreatCustomEventListenerNamedAnnotationAsSpringListener() {
        myFixture.addFileToProject(
            "src/main/java/com/example/event/OrderCreatedEvent.java",
            """
                package com.example.event;

                public class OrderCreatedEvent {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/event/EventPublisher.java",
            """
                package com.example.event;

                class EventPublisher {
                    private final org.springframework.context.ApplicationEventPublisher publisher = null;

                    void publish() {
                        publisher.publishEvent(new OrderCreatedEvent());
                    }
                }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/event/CustomEventListener.java",
            """
                package com.example.event;

                public @interface CustomEventListener {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/event/SearchIndexListener.java",
            """
                package com.example.event;

                class SearchIndexListener {
                    @CustomEventListener
                    public void onEvent(OrderCreatedEvent event) {}
                }
            """.trimIndent(),
        )
        val pipeline = InvestigationPipeline.default(project)

        val result = pipeline.run(
            InvestigationRequest(
                threadId = "thread-custom-event-listener",
                question = "请继续取证：确认 Event OrderCreatedEvent 的监听器。",
                targetHints = listOf(
                    InvestigationTargetHint(
                        nodeId = "method:event-publish",
                        signature = "com.example.event.EventPublisher.publish():void",
                    ),
                ),
            ),
        )

        assertEquals(InvestigationStatus.NEEDS_MORE_EVIDENCE, result.status)
        assertTrue(result.acceptedFacts.none { fact ->
            fact.symbolSignature == "com.example.event.SearchIndexListener.onEvent(com.example.event.OrderCreatedEvent):void"
        })
    }

    fun testPipelineDoesNotTreatLocalEventListenerAnnotationAsSpringListener() {
        myFixture.addFileToProject(
            "src/main/java/com/example/event/OrderCreatedEvent.java",
            """
                package com.example.event;

                public class OrderCreatedEvent {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/event/EventPublisher.java",
            """
                package com.example.event;

                class EventPublisher {
                    private final org.springframework.context.ApplicationEventPublisher publisher = null;

                    void publish() {
                        publisher.publishEvent(new OrderCreatedEvent());
                    }
                }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/event/EventListener.java",
            """
                package com.example.event;

                public @interface EventListener {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/event/SearchIndexListener.java",
            """
                package com.example.event;

                class SearchIndexListener {
                    @EventListener
                    public void onEvent(OrderCreatedEvent event) {}
                }
            """.trimIndent(),
        )
        val pipeline = InvestigationPipeline.default(project)

        val result = pipeline.run(
            InvestigationRequest(
                threadId = "thread-local-event-listener",
                question = "请继续取证：确认 Event OrderCreatedEvent 的监听器。",
                targetHints = listOf(
                    InvestigationTargetHint(
                        nodeId = "method:event-publish",
                        signature = "com.example.event.EventPublisher.publish():void",
                    ),
                ),
            ),
        )

        assertEquals(InvestigationStatus.NEEDS_MORE_EVIDENCE, result.status)
        assertTrue(result.acceptedFacts.none { fact ->
            fact.symbolSignature == "com.example.event.SearchIndexListener.onEvent(com.example.event.OrderCreatedEvent):void"
        })
    }
}
