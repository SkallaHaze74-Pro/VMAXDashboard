package de.kevin.vmaxdashboard

import java.util.UUID
import kotlin.math.roundToInt

class DecoderLabEngine {
    private val baseline = mutableMapOf<String, MutableList<ByteArray>>()
    private val active = mutableMapOf<String, MutableList<ByteArray>>()
    private var phase = Phase.IDLE
    var action: String = ""
        private set

    enum class Phase { IDLE, BASELINE, ACTIVE }

    fun startBaseline(selectedAction: String) {
        baseline.clear()
        active.clear()
        action = selectedAction
        phase = Phase.BASELINE
    }

    fun startActive() {
        phase = Phase.ACTIVE
    }

    fun cancel() {
        baseline.clear()
        active.clear()
        action = ""
        phase = Phase.IDLE
    }

    fun record(uuid: UUID, value: ByteArray) {
        val key = shortUuid(uuid)
        when (phase) {
            Phase.BASELINE -> baseline.getOrPut(key) { mutableListOf() }.add(value.copyOf())
            Phase.ACTIVE -> active.getOrPut(key) { mutableListOf() }.add(value.copyOf())
            Phase.IDLE -> Unit
        }
    }

    fun finish(): List<ByteCandidate> {
        val result = mutableListOf<ByteCandidate>()
        val channels = baseline.keys.intersect(active.keys)

        for (channel in channels) {
            val beforeSamples = baseline[channel].orEmpty()
            val activeSamples = active[channel].orEmpty()
            val maxLength = minOf(
                beforeSamples.minOfOrNull { it.size } ?: 0,
                activeSamples.minOfOrNull { it.size } ?: 0
            )

            for (index in 0 until maxLength) {
                val beforeValues = beforeSamples.map { it[index].toInt() and 0xFF }
                val afterValues = activeSamples.map { it[index].toInt() and 0xFF }
                val beforeMode = mode(beforeValues)
                val afterMode = mode(afterValues)

                if (beforeMode != afterMode) {
                    val beforeStability = stability(beforeValues, beforeMode)
                    val afterStability = stability(afterValues, afterMode)
                    val score = ((beforeStability + afterStability) * 50.0).roundToInt()
                    if (score >= 45) {
                        result += ByteCandidate(
                            characteristic = channel,
                            byteIndex = index,
                            beforeValue = beforeMode,
                            activeValue = afterMode,
                            score = score.coerceIn(0, 100)
                        )
                    }
                }
            }
        }

        phase = Phase.IDLE
        return result.sortedByDescending { it.score }.take(30)
    }

    private fun mode(values: List<Int>): Int =
        values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 0

    private fun stability(values: List<Int>, mode: Int): Double =
        if (values.isEmpty()) 0.0 else values.count { it == mode }.toDouble() / values.size

    private fun shortUuid(uuid: UUID): String =
        uuid.toString().substring(4, 8).uppercase()
}
