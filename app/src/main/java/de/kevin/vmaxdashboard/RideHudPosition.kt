package de.kevin.vmaxdashboard

internal data class RideHudPosition(val x: Int, val y: Int)

/** Applies a drag delta while keeping the complete HUD inside the display. */
internal fun moveRideHudPosition(
    start: RideHudPosition,
    deltaX: Int,
    deltaY: Int,
    displayWidth: Int,
    displayHeight: Int,
    hudWidth: Int,
    hudHeight: Int
): RideHudPosition {
    val maxX = (displayWidth.coerceAtLeast(0) - hudWidth.coerceAtLeast(0)).coerceAtLeast(0)
    val maxY = (displayHeight.coerceAtLeast(0) - hudHeight.coerceAtLeast(0)).coerceAtLeast(0)

    return RideHudPosition(
        x = movedCoordinate(start.x, deltaX, maxX),
        y = movedCoordinate(start.y, deltaY, maxY)
    )
}

private fun movedCoordinate(start: Int, delta: Int, maximum: Int): Int =
    (start.toLong() + delta.toLong()).coerceIn(0L, maximum.toLong()).toInt()
