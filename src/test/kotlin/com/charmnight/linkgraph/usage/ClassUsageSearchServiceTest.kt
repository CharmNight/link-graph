package com.charmnight.linkgraph.usage

import com.charmnight.linkgraph.jvm.index.stableJvmId
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClassUsageSearchServiceTest : BasePlatformTestCase() {
    fun testSearchGroupsProjectClassUsagesAndFiltersImportsByDefault() {
        myFixture.addFileToProject(
            "src/main/java/com/example/OrderService.java",
            """
            package com.example;
            public class OrderService {
                public void submit() {}
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/OrderController.java",
            """
            package com.example;
            import com.example.OrderService;
            public class OrderController {
                private OrderService orderService;
                public OrderController(OrderService orderService) {
                    this.orderService = orderService;
                }
                public void submit(OrderService service) {
                    new OrderService().submit();
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/SpecialOrderService.java",
            """
            package com.example;
            public class SpecialOrderService extends OrderService {
            }
            """.trimIndent(),
        )

        val targetClass = requireNotNull(
            JavaPsiFacade.getInstance(project).findClass(
                "com.example.OrderService",
                GlobalSearchScope.projectScope(project),
            ),
        )

        val result = ClassUsageSearchService(project).search(
            targetClass = targetClass,
            targetNodeId = "jvm:class:com-example-orderservice",
            options = ClassUsageSearchOptions(maxUsageGroups = 20, maxUsageEntries = 50),
        )

        val summary = result.groups.joinToString("\n") { group ->
            "${group.title} ${group.usages.map { it.kind }.sortedBy { it.name }}"
        }
        assertEquals("com.example.OrderService", result.target.qualifiedName)
        assertTrue(result.summary.usageCount >= 4, summary)
        assertTrue(result.groups.any { group -> group.qualifiedName == "com.example.OrderController" }, summary)
        assertTrue(result.groups.any { group -> group.qualifiedName == "com.example.SpecialOrderService" }, summary)
        assertTrue(result.groups.flatMap { group -> group.usages }.none { usage -> usage.kind == ClassUsageKind.IMPORT }, summary)
        assertTrue(result.groups.flatMap { group -> group.usages }.any { usage -> usage.kind == ClassUsageKind.FIELD_TYPE }, summary)
        assertTrue(result.groups.flatMap { group -> group.usages }.any { usage -> usage.kind == ClassUsageKind.METHOD_PARAMETER }, summary)
        assertTrue(result.groups.flatMap { group -> group.usages }.any { usage -> usage.kind == ClassUsageKind.CONSTRUCTOR_CALL }, summary)
        assertTrue(result.groups.flatMap { group -> group.usages }.any { usage -> usage.kind == ClassUsageKind.EXTENDS }, summary)
    }

    fun testSearchCanIncludeImportUsagesWhenRequested() {
        myFixture.addFileToProject(
            "src/main/java/com/example/OrderService.java",
            """
            package com.example;
            public class OrderService {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/OrderController.java",
            """
            package com.example;
            import com.example.OrderService;
            public class OrderController {
                private OrderService orderService;
            }
            """.trimIndent(),
        )
        val targetClass = requireNotNull(
            JavaPsiFacade.getInstance(project).findClass(
                "com.example.OrderService",
                GlobalSearchScope.projectScope(project),
            ),
        )

        val result = ClassUsageSearchService(project).search(
            targetClass = targetClass,
            targetNodeId = "jvm:class:com-example-orderservice",
            options = ClassUsageSearchOptions(includeImports = true),
        )

        assertTrue(
            result.groups.flatMap { group -> group.usages }.any { usage -> usage.kind == ClassUsageKind.IMPORT },
            result.groups.joinToString("\n") { group -> group.usages.joinToString { it.kind.name } },
        )
    }

    fun testSearchResolvesProjectJavaFilesOutsideSourceRootsByQualifiedName() {
        myFixture.addFileToProject(
            "samples/com/example/OrderService.java",
            """
            package com.example;
            public class OrderService {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "samples/com/example/OrderClient.java",
            """
            package com.example;
            public class OrderClient {
                private OrderService orderService;
            }
            """.trimIndent(),
        )

        val result = ClassUsageSearchService(project).search(
            qualifiedName = "com.example.OrderService",
            targetNodeId = "jvm:class:com-example-orderservice",
            options = ClassUsageSearchOptions(maxUsageGroups = 10, maxUsageEntries = 10),
        )

        assertNotNull(result, "非标准 source root 中的 Java 类也应能被 PSI fallback 找到。")
        assertTrue(
            result.groups.any { group -> group.qualifiedName == "com.example.OrderClient" },
            result.groups.joinToString("\n") { group -> "${group.qualifiedName}: ${group.usages.map { it.kind }}" },
        )
    }

    fun testSearchUsesSourcePathHintToResolveProjectJavaFileTarget() {
        myFixture.addFileToProject(
            "samples/com/example/OrderService.java",
            """
            package com.example;
            public class OrderService {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "samples/com/example/OrderClient.java",
            """
            package com.example;
            public class OrderClient {
                private OrderService orderService;
            }
            """.trimIndent(),
        )

        val result = ClassUsageSearchService(project).search(
            target = ClassUsageSearchTargetHint(
                qualifiedName = "com.example.OrderService",
                nodeId = "jvm:class:com-example-orderservice",
                sourcePath = "samples/com/example/OrderService.java",
            ),
            options = ClassUsageSearchOptions(maxUsageGroups = 10, maxUsageEntries = 10),
        )

        assertNotNull(result, "sourcePath hint 应能把架构索引中的源码位置映射回 PSI 类。")
        assertTrue(
            result.groups.any { group -> group.qualifiedName == "com.example.OrderClient" },
            result.groups.joinToString("\n") { group -> "${group.qualifiedName}: ${group.usages.map { it.kind }}" },
        )
    }

    fun testSourceRootPathHintDoesNotForceWordIndexFallback() {
        val targetFile = myFixture.addFileToProject(
            "src/main/java/com/example/OrderService.java",
            """
            package com.example;
            public class OrderService {}
            """.trimIndent(),
        )
        val sourceRoot = targetFile.virtualFile.parent.parent.parent
        PsiTestUtil.addSourceContentToRoots(module, sourceRoot)

        val resolution = ClassUsageTargetResolver(project).resolve(
            ClassUsageSearchTargetHint(
                qualifiedName = "com.example.OrderService",
                nodeId = "jvm:class:com-example-orderservice",
                sourceVirtualFileUrl = targetFile.virtualFile.url,
            ),
        )

        assertNotNull(resolution, "sourcePath hint should still resolve the source-root PSI class.")
        assertFalse(
            resolution.allowWordIndexFallback,
            "Classes already backed by source roots should rely on indexed reference search instead of broad fallback scans.",
        )
    }

    fun testNonSourcePathHintKeepsWordIndexFallback() {
        val targetFile = myFixture.addFileToProject(
            "samples/com/example/OrderService.java",
            """
            package com.example;
            public class OrderService {}
            """.trimIndent(),
        )

        val resolution = ClassUsageTargetResolver(project).resolve(
            ClassUsageSearchTargetHint(
                qualifiedName = "com.example.OrderService",
                nodeId = "jvm:class:com-example-orderservice",
                sourceVirtualFileUrl = targetFile.virtualFile.url,
            ),
        )

        assertNotNull(resolution, "source hint should resolve Java files outside source roots.")
        assertTrue(
            resolution.allowWordIndexFallback,
            "Java files outside source roots still need word/file fallback to find their usages.",
        )
    }

    fun testSearchResolvesProjectJavaFilesOutsideSourceRootsByStableNodeIdOnly() {
        val targetQualifiedName = "org.springframework.beans.AbstractNestablePropertyAccessor"
        val targetNodeId = stableJvmId("class", targetQualifiedName)
        myFixture.addFileToProject(
            "samples/org/springframework/beans/AbstractNestablePropertyAccessor.java",
            """
            package org.springframework.beans;
            public abstract class AbstractNestablePropertyAccessor {
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "samples/org/springframework/beans/BeanWrapperImpl.java",
            """
            package org.springframework.beans;
            public class BeanWrapperImpl extends AbstractNestablePropertyAccessor {
            }
            """.trimIndent(),
        )

        val result = ClassUsageSearchService(project).search(
            target = ClassUsageSearchTargetHint(nodeId = targetNodeId),
            options = ClassUsageSearchOptions(maxUsageGroups = 10, maxUsageEntries = 10),
        )

        assertNotNull(result, "只有 stable node id 时，也应能从非 source root Java 文件解析目标类。")
        assertEquals(targetQualifiedName, result.target.qualifiedName)
        assertTrue(
            result.groups.any { group -> group.qualifiedName == "org.springframework.beans.BeanWrapperImpl" },
            result.groups.joinToString("\n") { group -> "${group.qualifiedName}: ${group.usages.map { it.kind }}" },
        )
    }

    fun testSearchStopsAtRequestedEntryWindowAndMarksTruncated() {
        myFixture.addFileToProject(
            "src/main/java/com/example/OrderService.java",
            """
            package com.example;
            public class OrderService {}
            """.trimIndent(),
        )
        repeat(8) { index ->
            myFixture.addFileToProject(
                "src/main/java/com/example/client/OrderClient$index.java",
                """
                package com.example.client;
                import com.example.OrderService;
                public class OrderClient$index {
                    private OrderService orderService;
                }
                """.trimIndent(),
            )
        }
        val targetClass = requireNotNull(
            JavaPsiFacade.getInstance(project).findClass(
                "com.example.OrderService",
                GlobalSearchScope.projectScope(project),
            ),
        )

        val result = ClassUsageSearchService(project).search(
            targetClass = targetClass,
            targetNodeId = "jvm:class:com-example-orderservice",
            options = ClassUsageSearchOptions(maxUsageGroups = 10, maxUsageEntries = 2),
        )

        val debugSummary = "${result.summary}\n" +
            result.groups.joinToString("\n") { group -> "${group.title}: ${group.usages.map { "${it.kind}@${it.line}:${it.column}" }}" }
        assertEquals(2, result.summary.visibleUsageCount, debugSummary)
        assertEquals(2, result.groups.sumOf { group -> group.usages.size }, debugSummary)
        assertTrue(result.summary.truncated, "小窗口 usage 请求应快速截断，而不是扫完整项目后才裁剪。")
        assertTrue(result.summary.usageCount <= 3, "截断时 usageCount 应是窗口边界计数，不应要求精确全量计数。")
    }

    fun testSearchClampsOversizedUsageOptionsAndReportsHardCap() {
        myFixture.addFileToProject(
            "src/main/java/com/example/OrderService.java",
            """
            package com.example;
            public class OrderService {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/OrderController.java",
            """
            package com.example;
            public class OrderController {
                private OrderService orderService;
            }
            """.trimIndent(),
        )
        val targetClass = requireNotNull(
            JavaPsiFacade.getInstance(project).findClass(
                "com.example.OrderService",
                GlobalSearchScope.projectScope(project),
            ),
        )

        val result = ClassUsageSearchService(project).search(
            targetClass = targetClass,
            targetNodeId = "jvm:class:com-example-orderservice",
            options = ClassUsageSearchOptions(maxUsageGroups = 9999, maxUsageEntries = 99999),
        )

        assertEquals(200, result.summary.maxUsageGroups)
        assertEquals(1000, result.summary.maxUsageEntries)
        assertTrue(result.groups.size <= 200)
        assertTrue(result.groups.sumOf { group -> group.usages.size } <= 1000)
        assertFalse(result.summary.canRequestMore)
    }
}
