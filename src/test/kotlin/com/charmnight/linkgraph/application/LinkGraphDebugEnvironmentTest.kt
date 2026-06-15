package com.charmnight.linkgraph.foundation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkGraphDebugEnvironmentTest {
    @Test
    fun debugFlagAcceptsPlainTrueValue() {
        assertTrue(
            LinkGraphDebugEnvironment.isEnabled(
                "LINKGRAPH_DEBUG_TRACE",
                mapOf("LINKGRAPH_DEBUG_TRACE" to "true"),
            ),
        )
    }

    @Test
    fun debugFlagAcceptsRunConfigurationValueThatCombinesMultipleAssignments() {
        val environment = mapOf(
            "LINKGRAPH_DEBUG_TRACE" to "true LINKGRAPH_DEBUG_INTERACTION_PROBE=true",
        )

        assertTrue(LinkGraphDebugEnvironment.isEnabled("LINKGRAPH_DEBUG_TRACE", environment))
        assertTrue(LinkGraphDebugEnvironment.isEnabled("LINKGRAPH_DEBUG_INTERACTION_PROBE", environment))
    }

    @Test
    fun debugFlagDoesNotTreatUnrelatedTextAsEnabled() {
        assertFalse(
            LinkGraphDebugEnvironment.isEnabled(
                "LINKGRAPH_DEBUG_TRACE",
                mapOf("LINKGRAPH_DEBUG_TRACE" to "false LINKGRAPH_DEBUG_INTERACTION_PROBE=true"),
            ),
        )
    }
}
