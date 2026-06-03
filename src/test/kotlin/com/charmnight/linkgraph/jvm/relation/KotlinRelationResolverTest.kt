package com.charmnight.linkgraph.jvm.relation

import com.charmnight.linkgraph.jvm.index.JvmSymbolIndexBuilder
import com.charmnight.linkgraph.source.IdeSourceContentResolver
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class KotlinRelationResolverTest : BasePlatformTestCase() {
    fun testKotlinPsiResolverAddsCallsTypeUsageAndConstructorInjection() {
        myFixture.addFileToProject(
            "src/main/kotlin/com/example/KotlinRelations.kt",
            """
                package com.example

                class KotlinRepository

                class KotlinFormatter {
                    fun format(value: String): String = value.trim()
                }

                class KotlinService(
                    private val repository: KotlinRepository,
                    private val formatter: KotlinFormatter,
                ) {
                    fun run(input: KotlinRepository): String {
                        return formatter.format(input.toString())
                    }
                }
            """.trimIndent(),
        )

        val symbolIndex = JvmSymbolIndexBuilder(project).build()
        val relationIndex = JvmRelationResolverRegistry().resolveAll(
            JvmResolutionContext(
                project = project,
                symbolIndex = symbolIndex,
                sourceResolver = IdeSourceContentResolver(project),
            ),
        )
        val service = requireNotNull(symbolIndex.findClass("com.example.KotlinService"))
        val repository = requireNotNull(symbolIndex.findClass("com.example.KotlinRepository"))
        val formatter = requireNotNull(symbolIndex.findClass("com.example.KotlinFormatter"))

        assertTrue(
            "Kotlin primary constructor parameters should produce injection relations.",
            relationIndex.relations.any { relation ->
                relation.kind == JvmRelationKind.INJECTS &&
                    relation.fromSymbolId == service.id &&
                    relation.toSymbolId == repository.id
            },
        )
        assertTrue(
            "Kotlin type references should produce type usage relations.",
            relationIndex.relations.any { relation ->
                relation.kind == JvmRelationKind.USES_TYPE &&
                    relation.fromSymbolId == service.id &&
                    relation.toSymbolId == repository.id
            },
        )
        assertTrue(
            "Kotlin method calls should produce class-level call relations.",
            relationIndex.relations.any { relation ->
                relation.kind == JvmRelationKind.CALLS &&
                    relation.fromSymbolId == service.id &&
                    relation.toSymbolId == formatter.id
            },
        )
    }
}
