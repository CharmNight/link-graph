package com.charmnight.linkgraph.architecture

import com.charmnight.linkgraph.jvm.relation.JvmRelationKind
import com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArchitectureOverviewSymbolIndexBuilderTest : BasePlatformTestCase() {
    fun testBuildMergesVirtualFixtureFilesWhenBasePathAlreadyHasDiskSources() {
        val basePath = Path.of(requireNotNull(project.basePath))
        val diskOnlySource = basePath.resolve("src/main/java/com/stale/StaleDiskClass.java")
        Files.createDirectories(diskOnlySource.parent)
        Files.writeString(
            diskOnlySource,
            """
                package com.stale;

                public class StaleDiskClass {}
            """.trimIndent(),
        )

        addServiceProviderFixture()

        val symbolIndex = ArchitectureOverviewSymbolIndexBuilder(project).build(JvmResolutionBudget())
        val index = ArchitectureOverviewFastIndex.fromSymbols(symbolIndex, JvmResolutionBudget())

        assertNotNull(symbolIndex.findClass("com.example.service.DefaultTaskProvider"))
        assertNotNull(symbolIndex.findClass("com.example.spi.service.TaskProvider"))
        assertTrue(
            index.graph.edges.any { edge ->
                edge.metadata["architecture.aggregate.level"] == "OVERVIEW" &&
                    edge.metadata["jvm.relation.kind"] in setOf(
                        JvmRelationKind.IMPLEMENTS.name,
                        JvmRelationKind.USES_TYPE.name,
                        JvmRelationKind.RESOURCE_BINDS.name,
                    )
            },
            index.graph.edges.joinToString("\n") { edge ->
                "${edge.fromNodeId} -> ${edge.toNodeId}: ${edge.metadata["jvm.relation.kind"]}"
            },
        )
    }

    private fun addServiceProviderFixture() {
        myFixture.addFileToProject(
            "src/main/java/com/example/spi/TaskProvider.java",
            """
                package com.example.spi.service;

                public interface TaskProvider {
                    void provide();
                }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/service/DefaultTaskProvider.java",
            """
                package com.example.service;

                import com.example.spi.service.TaskProvider;

                public class DefaultTaskProvider implements TaskProvider {
                    public void provide() {}
                }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/service/TaskRunner.java",
            """
                package com.example.service;

                import com.example.spi.service.TaskProvider;

                public class TaskRunner {
                    private TaskProvider provider;
                }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/resources/META-INF/services/com.example.spi.service.TaskProvider",
            "com.example.service.DefaultTaskProvider\n",
        )
    }
}
