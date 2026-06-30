package com.charmnight.linkgraph.application.composition

import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * 冒烟测试：验证 WorkflowComposition 的结构完整性，而无需启动完整的 IntelliJ 项目。
 *
 * 完整的运行时构造需要 Project / Logger / InfrastructureComposition 等真实依赖，
 * 在单测里全部模拟成本过高；这里改为静态检查：
 * - 关键惰性属性都存在（subjectFlow / workspaceFlow / architectureFlow / classDiagramFlow 等）
 * - 所有工作流构造时都把 `infrastructure.eventSink` 作为事件出口传入（保证单一事件总线）
 * - LazyThreadSafetyMode.PUBLICATION 用于并发初始化
 *
 * 若后续把这些结构改坏（例如新工作流忘了接 eventSink），本测试会失败。
 */
class WorkflowCompositionSmokeTest {
    @Test
    fun workflowCompositionExposesExpectedLazyWorkflowsAndSharedEventSink() {
        val source = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/application/composition/WorkflowComposition.kt"),
        )
        // P2-1：允许共享基础设施分散到子文件，但工作流属性必须在主文件声明
        // 关键惰性工作流声明都应存在
        listOf(
            "subjectFlow",
            "workspaceFlow",
            "architectureGraphFlow",
            "classDiagramFlow",
            "reviewGraphFlow",
        ).forEach { name ->
            assertTrue(
                source.contains("val $name: ") || source.contains("val $name by lazy"),
                "WorkflowComposition 应声明 lazy val $name",
            )
        }

        // 共享基础设施允许分散到同包其他文件（如 CompositionSharedInfrastructure.kt）
        val sharedInfraNames = listOf(
            "codeSubjectHandleFactory",
            "defaultSubjectLocator",
            "defaultSemanticAnalyzer",
        )
        val compositionDir = Path.of("src/main/kotlin/com/charmnight/linkgraph/application/composition")
        val allCompositionSources = Files.list(compositionDir).use { stream ->
            stream
                .filter { p -> Files.isRegularFile(p) && p.toString().endsWith(".kt") }
                .map { p -> Files.readString(p) }
                .toList()
        }.joinToString("\n")
        sharedInfraNames.forEach { name ->
            assertTrue(
                allCompositionSources.contains("val $name: ") || allCompositionSources.contains("val $name by lazy"),
                "composition 包内应声明 lazy val $name（允许在子文件中）",
            )
        }

        // 所有工作流共享同一 eventSink（基础设施组合提供的单例）
        val eventSinkUsages = "infrastructure.eventSink".let { keyword ->
            source.split(keyword).size - 1
        }
        assertTrue(
            eventSinkUsages >= 5,
            "至少应有 5 处工作流把 infrastructure.eventSink 作为事件出口，实际 $eventSinkUsages",
        )

        // 所有 lazy 都用 PUBLICATION 模式，避免并发首访时的重复构造
        val publicationCount = "LazyThreadSafetyMode.PUBLICATION".let { keyword ->
            source.split(keyword).size - 1
        }
        assertTrue(
            publicationCount >= 5,
            "至少应有 5 处使用 PUBLICATION 模式，实际 $publicationCount",
        )
    }
}
