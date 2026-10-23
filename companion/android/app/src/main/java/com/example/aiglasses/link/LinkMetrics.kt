package com.example.aiglasses.link

/** App-side stats for one transport, rolled every 2 s by the LinkManager tick. */
data class TransportMetrics(
    // Ping / RTT ('P' echo)
    val rttMs: Double = 0.0,          // last sample; 0 = none yet
    val rttAvgMs: Double = 0.0,
    val rttMinMs: Double = 0.0,
    val rttMaxMs: Double = 0.0,
    val pingsSent: Int = 0,
    val pingsLost: Int = 0,
    // Throughput + lifetime totals (whole-frame bytes off/onto the radio)
    val rxBytesPerSec: Double = 0.0,
    val txBytesPerSec: Double = 0.0,
    val rxBytesTotal: Long = 0,
    val txBytesTotal: Long = 0,
)

/** Everything the app measures about the link, per transport + shared. */
data class LinkMetrics(
    val ble: TransportMetrics = TransportMetrics(),
    val wifi: TransportMetrics = TransportMetrics(),
    // Mic uplink frame accounting (BLE 'A' notifications)
    val micRecvPerSec: Double = 0.0,       // off the radio
    val micAcceptedPerSec: Double = 0.0,   // after dup/stale filtering
    val micDupPerSec: Double = 0.0,
    val micLostPerSec: Double = 0.0,
    // Image path
    val imageSeqGapsTotal: Int = 0,
    // Lifecycle
    val bleReconnects: Int = 0,
    val wifiConnects: Int = 0,
    val wifiRedials: Int = 0,
    val wifiSocketUptimeMs: Long = 0,      // app-side (0 = socket down)
    // Link tuning
    val mtu: Int = 0,
    val phyTx: Int = 0,                    // 0 unknown, 1 = 1M, 2 = 2M, 3 = coded
    val phyRx: Int = 0,
)
