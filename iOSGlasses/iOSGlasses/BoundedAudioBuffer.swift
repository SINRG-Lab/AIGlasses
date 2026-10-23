import Foundation

/// A small FIFO used only while the phone obtains a short-lived credential
/// and opens the Realtime socket. It prevents the beginning of a glasses-button
/// utterance from being lost without allowing unbounded microphone data growth.
struct BoundedAudioBuffer {
    private(set) var data = Data()
    let capacityBytes: Int

    init(capacityBytes: Int) {
        precondition(capacityBytes > 0)
        self.capacityBytes = capacityBytes
    }

    /// Returns true when old audio had to be discarded.
    @discardableResult
    mutating func append(_ chunk: Data) -> Bool {
        guard !chunk.isEmpty else { return false }
        if chunk.count >= capacityBytes {
            data = Data(chunk.suffix(capacityBytes))
            return true
        }

        let overflow = max(0, data.count + chunk.count - capacityBytes)
        if overflow > 0 {
            data.removeFirst(overflow)
        }
        data.append(chunk)
        return overflow > 0
    }

    mutating func drain() -> Data {
        defer { data.removeAll(keepingCapacity: true) }
        return data
    }

    mutating func removeAll() {
        data.removeAll(keepingCapacity: true)
    }
}
