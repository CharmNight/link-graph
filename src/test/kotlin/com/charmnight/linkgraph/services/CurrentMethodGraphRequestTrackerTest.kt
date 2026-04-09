package com.charmnight.linkgraph.services

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CurrentMethodGraphRequestTrackerTest {
    @Test
    fun onlyMarksLatestRequestAsActive() {
        val tracker = CurrentMethodGraphRequestTracker()

        val firstRequestId = tracker.beginRequest()
        val secondRequestId = tracker.beginRequest()

        assertFalse(tracker.isLatest(firstRequestId))
        assertTrue(tracker.isLatest(secondRequestId))
    }
}
