package org.dshwatch.app

/** Foreground only. Delay starts after the previous request finishes. */
object PollPolicy {
    fun delayMillis(active: Boolean, panelOpen: Boolean, failures: Int): Long = when {
        failures == 1 -> 450L
        failures > 1 -> minOf(12_000L, 2_000L shl minOf(failures - 2, 3))
        active -> 1_200L
        panelOpen -> 12_000L
        else -> 6_000L
    }
}
