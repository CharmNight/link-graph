package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.application.workflow.CurrentSubjectGraphRequestTracker
import com.charmnight.linkgraph.testing.*

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
