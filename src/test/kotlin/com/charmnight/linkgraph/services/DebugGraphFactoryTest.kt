package com.charmnight.linkgraph.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DebugGraphFactoryTest {
    private val factory = DebugGraphFactory()

    @Test
    fun createsWide19GraphAsSingleLinearChain() {
        val definition = factory.create("wide19")
        assertNotNull(definition)

        assertEquals(19, definition.graph.nodes.size)
        assertEquals(18, definition.graph.edges.size)
        assertTrue(definition.summary.contains("wide19"))
        assertEquals(
            "com.example.audit.DataSourceAspect.around(org.aspectj.lang.ProceedingJoinPoint):java.lang.Object",
            definition.anchorSignature,
        )
        assertTrue(definition.graph.nodes.any { node -> node.doc?.contains("重点核对") == true })
        assertTrue(definition.graph.nodes.any { node -> node.doc?.contains("问答流水") == true })
    }

    @Test
    fun createsDenseGraphUsingParsedNodeCountInsteadOfSpecialCaseBranches() {
        val definition = factory.create("dense7")
        assertNotNull(definition)

        assertEquals(7, definition.graph.nodes.size)
        assertEquals(11, definition.graph.edges.size)
        assertTrue(definition.summary.contains("dense7"))
        assertEquals(
            "com.example.audit.DenseAnchor.execute(com.example.audit.DenseRequest):void",
            definition.anchorSignature,
        )
    }

    @Test
    fun returnsNullForUnknownDebugModes() {
        assertNull(factory.create("unknown"))
        assertNull(factory.create("dense"))
    }
}
