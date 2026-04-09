package com.charmnight.linkgraph.services

import kotlin.test.Test
import kotlin.test.assertEquals

class LinkGraphProjectServiceDebugModeTest {
    @Test
    fun parseDenseDebugNodeCountReturnsBoundedNodeCount() {
        assertEquals(2, parseDenseDebugNodeCount("dense1"))
        assertEquals(40, parseDenseDebugNodeCount("dense40"))
        assertEquals(400, parseDenseDebugNodeCount("dense999"))
    }

    @Test
    fun parseDenseDebugNodeCountReturnsNullForNonDenseModes() {
        assertEquals(null, parseDenseDebugNodeCount("wide19"))
        assertEquals(null, parseDenseDebugNodeCount("dense"))
        assertEquals(null, parseDenseDebugNodeCount("dense-1"))
    }

    @Test
    fun parseDenseDebugNodeCountTreatsDense40AsRegularDenseMode() {
        assertEquals(40, parseDenseDebugNodeCount("dense40"))
    }
}
