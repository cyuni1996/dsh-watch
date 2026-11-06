package org.dshwatch.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PollPolicyTest {
    @Test fun idleAndPanelsReduceRequestsWhileRepliesStayResponsive() {
        assertEquals(1_200L, PollPolicy.delayMillis(true, false, 0))
        assertEquals(1_200L, PollPolicy.delayMillis(true, true, 0))
        assertEquals(6_000L, PollPolicy.delayMillis(false, false, 0))
        assertEquals(12_000L, PollPolicy.delayMillis(false, true, 0))
    }

    @Test fun repeatedFailuresBackOffEvenDuringAnOldBusyStateAndAreCapped() {
        val delays=(1..10).map { PollPolicy.delayMillis(true, false, it) }
        assertEquals(listOf(450L,2_000L,4_000L,8_000L,12_000L),delays.take(5))
        assertTrue(delays.drop(5).all { it==12_000L })
        assertEquals(1_200L,PollPolicy.delayMillis(true,false,0))
    }
}
