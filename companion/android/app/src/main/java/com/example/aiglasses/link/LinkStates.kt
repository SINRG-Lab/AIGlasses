package com.example.aiglasses.link

/** Connection state of the primary (BLE) transport. */
sealed class BleState {
    data object Disconnected : BleState()
    data object Scanning : BleState()
    data object Connecting : BleState()
    data class Connected(val name: String, val mtu: Int) : BleState()
}

/**
 * Lifecycle of the optional WiFi bulk lane (SoftAP + TCP socket).
 *
 *   Off        — lane down, no attempt in flight
 *   Requesting — 'F' written on BLE CONTROL, waiting for the 'N' credentials
 *   Approving  — local-only network requested; waiting for the system
 *                device-approval dialog / AP association
 *   Connecting — network up, TCP socket dialing (or a redial pending)
 *   Active     — firmware said 'R' hello; photos may route over the socket
 *   Failed     — gave up for now (message explains); slow retry keeps probing
 *                while the auto toggle stays on
 */
sealed class WifiPhase {
    data object Off : WifiPhase()
    data object Requesting : WifiPhase()
    data object Approving : WifiPhase()
    data object Connecting : WifiPhase()
    data object Active : WifiPhase()
    data class Failed(val message: String) : WifiPhase()
}
