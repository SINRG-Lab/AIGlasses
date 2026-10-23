package com.example.aiglasses.link

/**
 * Firmware-side truth, sent every 5 s as a compact binary 'T' packet on the
 * BLE CONTROL characteristic (and mirrored on the WiFi CONTROL channel while
 * a socket is attached). Layout v1 — must stay in lockstep with
 * `sendStatsPacket()` in S3_App_V2/link.cpp; byte table in
 * S3_App_V2/docs/BLE_PROTOCOL.md. Total documented size: 74 bytes; parsing is
 * length-guarded so shorter (or future longer) packets degrade to partial
 * stats instead of garbage.
 */
data class FirmwareStats(
    val version: Int = 0,
    val uptimeMs: Long = 0,          // u32 @2
    val freeHeap: Long = 0,          // u32 @6
    val freePsram: Long = 0,         // u32 @10
    val attMtu: Int = 0,             // u16 @14 (23 until the exchange)
    val phyTx: Int = 0,              // u8 @16: 0 unknown, 1 = 1M, 2 = 2M, 3 = coded
    val phyRx: Int = 0,              // u8 @17
    val bleConnected: Boolean = false,       // flags @18 bit0
    val softApUp: Boolean = false,           // bit1
    val wifiClientConnected: Boolean = false, // bit2
    val routeIsWifi: Boolean = false,        // bit3 (next image routes WiFi)
    val staRssi: Int = 0,            // i8 @19, dBm (0 = unavailable)
    val bleConnectCount: Int = 0,    // u16 @20
    val wifiClientConnects: Int = 0, // u16 @22
    val pingsHeardBle: Int = 0,      // u16 @24
    val pingsHeardWifi: Int = 0,     // u16 @26
    val bleTxPerSec: Long = 0,       // u32 @28 (rolling 5 s window)
    val bleRxPerSec: Long = 0,       // u32 @32
    val wifiTxPerSec: Long = 0,      // u32 @36
    val wifiRxPerSec: Long = 0,      // u32 @40
    val bleTxTotal: Long = 0,        // u32 @44
    val bleRxTotal: Long = 0,        // u32 @48
    val wifiTxTotal: Long = 0,       // u32 @52
    val wifiRxTotal: Long = 0,       // u32 @56
    val lastPhotoRoute: Int = 0,     // u8 @60: 0 none yet, 1 = BLE, 2 = WiFi
    val lastPhotoBytes: Long = 0,    // u32 @62
    val lastPhotoMs: Long = 0,       // u32 @66
    val wifiSocketUptimeMs: Long = 0, // u32 @70 (0 = no client attached)
    val receivedAtMs: Long = 0,
) {
    val phyLabel: String
        get() {
            fun name(v: Int) = when (v) {
                1 -> "1M"
                2 -> "2M"
                3 -> "Coded"
                else -> "?"
            }
            if (phyTx == 0 && phyRx == 0) return "—"
            return if (phyTx == phyRx) name(phyTx) else "${name(phyTx)}/${name(phyRx)}"
        }

    companion object {
        /** Parse a raw CONTROL packet (tag byte included). Null if not a 'T' packet. */
        fun parse(b: ByteArray): FirmwareStats? {
            if (b.size < 2 || b[0] != 'T'.code.toByte()) return null
            fun u8(at: Int): Int = if (b.size > at) b[at].toInt() and 0xFF else 0
            fun u16(at: Int): Int =
                if (b.size >= at + 2) (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
                else 0
            fun u32(at: Int): Long =
                if (b.size >= at + 4)
                    (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
                        ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)
                else 0
            val flags = u8(18)
            return FirmwareStats(
                version = u8(1),
                uptimeMs = u32(2),
                freeHeap = u32(6),
                freePsram = u32(10),
                attMtu = u16(14),
                phyTx = u8(16),
                phyRx = u8(17),
                bleConnected = flags and 0x01 != 0,
                softApUp = flags and 0x02 != 0,
                wifiClientConnected = flags and 0x04 != 0,
                routeIsWifi = flags and 0x08 != 0,
                staRssi = if (b.size > 19) b[19].toInt() else 0,   // i8 sign-extended
                bleConnectCount = u16(20),
                wifiClientConnects = u16(22),
                pingsHeardBle = u16(24),
                pingsHeardWifi = u16(26),
                bleTxPerSec = u32(28),
                bleRxPerSec = u32(32),
                wifiTxPerSec = u32(36),
                wifiRxPerSec = u32(40),
                bleTxTotal = u32(44),
                bleRxTotal = u32(48),
                wifiTxTotal = u32(52),
                wifiRxTotal = u32(56),
                lastPhotoRoute = u8(60),
                lastPhotoBytes = u32(62),
                lastPhotoMs = u32(66),
                wifiSocketUptimeMs = u32(70),
                receivedAtMs = System.currentTimeMillis(),
            )
        }
    }
}
