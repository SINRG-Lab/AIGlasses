package com.example.aiglasses.link

/** Which transport carried a payload (route truth = where the 'H' header arrived). */
enum class LinkRoute { Ble, Wifi }

/** One completed photo transfer, for the Developer screen + log. */
data class PhotoTransfer(
    val timestampMs: Long,
    val route: LinkRoute,
    val bytes: Int,
    val millis: Long,
) {
    val kbPerSec: Double
        get() = if (millis > 0) bytes * 1000.0 / millis / 1024.0 else 0.0
}
