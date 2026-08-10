import Foundation

/// Local protection against retry storms while a held glasses button keeps
/// producing mic frames after a credential request fails.
struct VoiceRetryGate {
    private(set) var notBefore = Date.distantPast
    private(set) var transientDelay: TimeInterval = 2

    var maximumTransientDelay: TimeInterval { 30 }

    func isBlocked(at date: Date = Date()) -> Bool {
        date < notBefore
    }

    func remaining(at date: Date = Date()) -> TimeInterval {
        max(0, notBefore.timeIntervalSince(date))
    }

    /// Returns the cooldown that was applied, if this failure is gated.
    @discardableResult
    mutating func record(
        _ error: RealtimeCredentialServiceError,
        at date: Date = Date()
    ) -> TimeInterval? {
        let delay: TimeInterval
        switch error {
        case .rateLimited:
            delay = 60
        case .networkUnavailable, .serviceUnavailable:
            delay = transientDelay
            transientDelay = min(transientDelay * 2, maximumTransientDelay)
        case .authenticationUnavailable, .invalidResponse, .expiredCredential:
            // These should be rare and are not part of the exponential
            // network backoff, but still need a small busy-loop guard.
            delay = 2
        }
        notBefore = date.addingTimeInterval(delay)
        return delay
    }

    mutating func reset() {
        notBefore = .distantPast
        transientDelay = 2
    }
}
/// Tracks activity that makes a Realtime socket ineligible for idle closure.
struct RealtimeActivityState {
    var microphoneActive = false
    var responseActive = false

    var isBusy: Bool { microphoneActive || responseActive }

    mutating func reset() {
        microphoneActive = false
        responseActive = false
    }
}
