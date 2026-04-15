package com.charmnight.linkgraph.services

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CurrentSubjectGraphRequestTrackerTest {
    @Test
    fun onlyMarksLatestRequestAsActive() {
        val tracker = CurrentSubjectGraphRequestTracker()

        val firstRequestId = tracker.beginRequest()
        val secondRequestId = tracker.beginRequest()

        assertFalse(tracker.isLatest(firstRequestId))
        assertTrue(tracker.isLatest(secondRequestId))
    }
}
