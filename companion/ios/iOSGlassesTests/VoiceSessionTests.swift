import Foundation
import XCTest
@testable import iOSGlasses

final class VoiceSessionTests: XCTestCase {
    func testAudioBufferPreservesAllAudioWhenBelowCapacity() {
        var buffer = BoundedAudioBuffer(capacityBytes: 8)

        XCTAssertFalse(buffer.append(Data([1, 2, 3])))
        XCTAssertFalse(buffer.append(Data([4, 5])))
        XCTAssertEqual(buffer.drain(), Data([1, 2, 3, 4, 5]))
        XCTAssertTrue(buffer.data.isEmpty)
    }

    func testAudioBufferDropsOldestAudioAtCapacity() {
        var buffer = BoundedAudioBuffer(capacityBytes: 5)

        XCTAssertFalse(buffer.append(Data([1, 2, 3])))
        XCTAssertTrue(buffer.append(Data([4, 5, 6, 7])))
        XCTAssertEqual(buffer.drain(), Data([3, 4, 5, 6, 7]))
    }

    func testOversizedAudioChunkKeepsNewestBytes() {
        var buffer = BoundedAudioBuffer(capacityBytes: 4)

        XCTAssertTrue(buffer.append(Data([1, 2, 3, 4, 5, 6])))
        XCTAssertEqual(buffer.drain(), Data([3, 4, 5, 6]))
    }

    func testCredentialRequiresEphemeralPrefixAndRemainingLifetime() {
        let now = Date(timeIntervalSince1970: 1_000)
        let valid = RealtimeClientCredential(
            secret: "ek_test",
            expiresAt: now.addingTimeInterval(60),
            model: "gpt-realtime-2.1"
        )
        let permanent = RealtimeClientCredential(
            secret: "not-ephemeral",
            expiresAt: now.addingTimeInterval(60),
            model: "gpt-realtime-2.1"
        )

        XCTAssertTrue(valid.isUsable(at: now))
        XCTAssertFalse(valid.isUsable(at: now.addingTimeInterval(50)))
        XCTAssertFalse(permanent.isUsable(at: now))
    }

    func testRateLimitAppliesFullMinuteCooldown() {
        var gate = VoiceRetryGate()
        let now = Date(timeIntervalSince1970: 1_000)

        XCTAssertEqual(gate.record(.rateLimited, at: now), 60)
        XCTAssertTrue(gate.isBlocked(at: now.addingTimeInterval(59.999)))
        XCTAssertEqual(gate.remaining(at: now), 60, accuracy: 0.001)
        XCTAssertFalse(gate.isBlocked(at: now.addingTimeInterval(60)))
    }

    func testTransientFailuresBackOffAndCapAtThirtySeconds() {
        var gate = VoiceRetryGate()
        let now = Date(timeIntervalSince1970: 1_000)

        let delays = (0..<6).map { _ in gate.record(.networkUnavailable, at: now) }
        XCTAssertEqual(delays, [2, 4, 8, 16, 30, 30])

        gate.reset()
        XCTAssertFalse(gate.isBlocked(at: now))
        XCTAssertEqual(gate.record(.serviceUnavailable, at: now), 2)
    }

    func testRealtimeActivityIsBusyForMicOrResponse() {
        var activity = RealtimeActivityState()
        XCTAssertFalse(activity.isBusy)

        activity.microphoneActive = true
        XCTAssertTrue(activity.isBusy)
        activity.microphoneActive = false
        activity.responseActive = true
        XCTAssertTrue(activity.isBusy)

        activity.reset()
        XCTAssertFalse(activity.isBusy)
    }

    func testRealtimeHandshakeErrorsAreClassifiedWithoutParsingDisplayText() {
        let transportError = NSError(
            domain: NSURLErrorDomain,
            code: NSURLErrorNotConnectedToInternet
        )

        XCTAssertEqual(
            RealtimeSession.describe(error: transportError, httpStatus: 429, model: "test").kind,
            .rateLimited
        )
        XCTAssertEqual(
            RealtimeSession.describe(error: transportError, httpStatus: 503, model: "test").kind,
            .serviceUnavailable
        )
        XCTAssertEqual(
            RealtimeSession.describe(error: transportError, httpStatus: nil, model: "test").kind,
            .networkUnavailable
        )
    }

    func testPreparedVisionEventContainsTheJPEGBeforeItIsQueued() throws {
        let jpeg = Data([0xFF, 0xD8, 0xFF, 0xD9])
        let prepared = try XCTUnwrap(RealtimeSession.prepareImage(jpeg: jpeg))
        let object = try XCTUnwrap(
            JSONSerialization.jsonObject(with: Data(prepared.message.utf8)) as? [String: Any]
        )
        let item = try XCTUnwrap(object["item"] as? [String: Any])
        let content = try XCTUnwrap(item["content"] as? [[String: Any]])

        XCTAssertEqual(prepared.byteCount, jpeg.count)
        XCTAssertEqual(object["type"] as? String, "conversation.item.create")
        XCTAssertEqual(content.first?["type"] as? String, "input_image")
        XCTAssertEqual(
            content.first?["image_url"] as? String,
            "data:image/jpeg;base64,\(jpeg.base64EncodedString())"
        )
    }
}
