package com.charmnight.linkgraph.architecture.query

import com.charmnight.linkgraph.jvm.index.JvmClassKind
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmSourceRef
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex
import com.charmnight.linkgraph.source.SourceOrigin
import kotlin.test.Test
import kotlin.test.assertEquals

class ArchitectureSymbolSearchTest {
    @Test
    fun ranksExactAndSimpleNameMatchesAbovePackageSubstringMatches() {
        val search = ArchitectureSymbolSearch(
            JvmSymbolIndex(
                classesByQualifiedName = listOf(
                    classSymbol("class:task-runner", "com.example.service.TaskRunner", "src/main/java/com/example/service/TaskRunner.java"),
                    classSymbol("class:task-runner-helper", "com.example.service.TaskRunnerHelper", "src/main/java/com/example/service/TaskRunnerHelper.java"),
                    classSymbol("class:service-package", "com.taskrunner.infrastructure.Worker", "src/main/java/com/taskrunner/infrastructure/Worker.java"),
                ).associateBy(JvmClassSymbol::qualifiedName),
            ),
        )

        val results = search.search("TaskRunner")

        assertEquals("SIMPLE_NAME_EXACT", results[0].matchKind)
        assertEquals("com.example.service.TaskRunner", results[0].symbol.qualifiedName)
        assertEquals("SIMPLE_NAME_PREFIX", results[1].matchKind)
        assertEquals("QUALIFIED_NAME_CONTAINS", results[2].matchKind)
    }

    private fun classSymbol(id: String, qualifiedName: String, path: String): JvmClassSymbol =
        JvmClassSymbol(
            id = id,
            qualifiedName = qualifiedName,
            simpleName = qualifiedName.substringAfterLast('.'),
            packageName = qualifiedName.substringBeforeLast('.'),
            moduleName = null,
            kind = JvmClassKind.CLASS,
            source = JvmSourceRef(path, null, null, null, false),
            origin = SourceOrigin.PROJECT_SOURCE,
        )
}
