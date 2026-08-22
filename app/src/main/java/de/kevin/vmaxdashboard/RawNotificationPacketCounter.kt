package de.kevin.vmaxdashboard

/**
 * Assigns monotonically increasing packet numbers per notification channel.
 *
 * RAW exports contain accepted, diagnostic-only, and quarantined notifications.
 * All of them must share the same channel-local sequence so packet_no never
 * changes meaning based on the decoder disposition.
 */
internal class RawNotificationPacketCounter {
    private val counts = mutableMapOf<String, Int>()

    fun next(channel: String): Int {
        val normalizedChannel = channel.trim().uppercase()
        val next = (counts[normalizedChannel] ?: 0) + 1
        counts[normalizedChannel] = next
        return next
    }

    fun clear() {
        counts.clear()
    }
}
