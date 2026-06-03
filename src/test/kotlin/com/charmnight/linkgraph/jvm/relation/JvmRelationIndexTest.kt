package com.charmnight.linkgraph.jvm.relation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JvmRelationIndexTest {
    @Test
    fun keepsCriticalRelationsWhenBudgetTruncates() {
        val relations = listOf(
            relation("call", JvmRelationKind.CALLS),
            relation("uses", JvmRelationKind.USES_TYPE),
            relation("contains", JvmRelationKind.PACKAGE_CONTAINS_CLASS),
            relation("impl", JvmRelationKind.IMPLEMENTS),
            relation("inject", JvmRelationKind.INJECTS),
            relation("spi", JvmRelationKind.SPI_PROVIDES),
            relation("reflect", JvmRelationKind.REFLECTS_TO),
            relation("tests", JvmRelationKind.TESTS),
        )

        val index = JvmRelationIndex(relations, maxRelations = 5)

        assertTrue(index.truncated)
        assertEquals(
            setOf(
                JvmRelationKind.IMPLEMENTS,
                JvmRelationKind.INJECTS,
                JvmRelationKind.SPI_PROVIDES,
                JvmRelationKind.REFLECTS_TO,
                JvmRelationKind.TESTS,
            ),
            index.relations.map(JvmRelation::kind).toSet(),
        )
    }

    private fun relation(id: String, kind: JvmRelationKind): JvmRelation =
        JvmRelation(
            id = id,
            kind = kind,
            fromSymbolId = "from:$id",
            toSymbolId = "to:$id",
            confidence = JvmRelationConfidence.PROVEN,
            source = JvmRelationSource.PSI,
        )
}
