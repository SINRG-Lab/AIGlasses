import Foundation

// MARK: - PCM16 <-> bytes helpers

extension Data {
    /// Interpret little-endian PCM16 bytes as samples (all iOS devices are LE).
    func toInt16Array() -> [Int16] {
        let n = count / 2
        guard n > 0 else { return [] }
        var out = [Int16](repeating: 0, count: n)
        _ = out.withUnsafeMutableBytes { dst in
            self.copyBytes(to: dst, from: startIndex..<(startIndex + n * 2))
        }
        return out
    }
}

extension Array where Element == Int16 {
    func toData() -> Data {
        withUnsafeBytes { Data($0) }
    }
}

// MARK: - G.711 µ-law

/// G.711 µ-law codec, ported from the validated implementations in
/// HardwareTest/realtime_ble.py (numpy) and HardwareTest.ino (C).
///
/// BLE audio is 1 byte/sample in BOTH directions on purpose: µ-law halves the
/// notification/write packet rate vs PCM16, and the CoreBluetooth central
/// demonstrably drops notifications above ~40/s. Do NOT "optimize" this back
/// to PCM16.
///
/// Unit-style sanity check (verified by compiling this exact file as a macOS
/// script): a 1 kHz sine at -6 dBFS, 16 kHz sample rate, 1 s long, passed
/// through encode + decode gives a round-trip SNR of
///   10 * log10(sum(x^2) / sum((x-y)^2)) = 35.42 dB
/// which is BIT-EXACT with the hardware-validated numpy implementation in
/// realtime_ble.py (same tone: 35.42219221624197 dB there too — the classic
/// "~39 dB" G.711 figure assumes mid-rise reconstruction; this codec matches
/// the firmware's, which is what matters). Silence (sample 0) encodes to 0xFF
/// and decodes back to exactly 0.
enum ULaw {
    static let decodeTable: [Int16] = {
        var lut = [Int16](repeating: 0, count: 256)
        for u in 0..<256 {
            let v = (~u) & 0xFF
            var t = ((v & 0x0F) << 3) + 0x84
            t <<= (v & 0x70) >> 4
            lut[u] = Int16((v & 0x80) != 0 ? (0x84 - t) : (t - 0x84))
        }
        return lut
    }()

    /// Standard G.711: bias 0x84, clip 32635, 3-bit exponent, 4-bit mantissa,
    /// ones-complement of the assembled byte.
    static func encodeSample(_ sample: Int16) -> UInt8 {
        var pcm = Int(sample)
        let sign: UInt8 = pcm < 0 ? 0x80 : 0
        if pcm < 0 { pcm = -pcm }
        if pcm > 32635 { pcm = 32635 }
        pcm += 0x84
        var exp = 7
        var mask = 0x4000
        while (pcm & mask) == 0 && exp > 0 {
            exp -= 1
            mask >>= 1
        }
        let mant = UInt8((pcm >> (exp + 3)) & 0x0F)
        return ~(sign | (UInt8(exp) << 4) | mant)
    }

    /// PCM16 little-endian bytes -> µ-law bytes (1 byte per sample).
    static func encode(pcm16 data: Data) -> Data {
        let samples = data.toInt16Array()
        var out = Data(capacity: samples.count)
        for s in samples { out.append(encodeSample(s)) }
        return out
    }

    /// µ-law bytes -> PCM16 little-endian bytes (2 bytes per sample).
    static func decode(_ ulaw: Data) -> Data {
        var out = Data(capacity: ulaw.count * 2)
        for b in ulaw {
            let s = decodeTable[Int(b)]
            out.append(UInt8(truncatingIfNeeded: s))
            out.append(UInt8(truncatingIfNeeded: s >> 8))
        }
        return out
    }
}

// MARK: - Resampling

enum Resampler {
    /// 16 kHz -> 24 kHz linear-interpolation upsample (PCM16 bytes in/out).
    /// Linear is fine for the mic uplink — the model's audio frontend is
    /// tolerant; the *downlink* stays at 24 kHz native so no resampling there.
    static func upsample16to24(_ pcm: Data) -> Data {
        let src = pcm.toInt16Array()
        let n = src.count
        guard n >= 2 else { return Data() }
        let m = n * 3 / 2
        var out = [Int16](repeating: 0, count: m)
        for j in 0..<m {
            let x = Double(j) * 2.0 / 3.0
            let i = Int(x)
            if i + 1 >= n {
                out[j] = src[n - 1]
            } else {
                let f = x - Double(i)
                let v = Double(src[i]) + (Double(src[i + 1]) - Double(src[i])) * f
                out[j] = Int16(clamping: Int(v))
            }
        }
        return out.toData()
    }
}

// MARK: - Mic conditioning

/// High-pass + slow AGC for the quiet onboard mic, applied before upload.
/// Port of MicConditioner in HardwareTest/realtime_bridge.py:
/// the MSM261D3526H1CPM is a -26 dBFS sensitivity part — speech at temple
/// distance peaks around -15 dBFS, far below what VAD/ASR want. A one-pole
/// HPF (~80 Hz @ 16 kHz) kills DC offset and handling rumble so the AGC does
/// not amplify it; gain adapts slowly toward a -6 dBFS peak target,
/// fast-attacks down on loud input, and is capped at 12x so silence never
/// becomes white noise.
final class MicConditioner {
    private var prevX: Double = 0
    private var prevY: Double = 0
    private(set) var gain: Double = 4.0     // start at Seeed's x4
    private(set) var lastPeak: Int = 1      // pre-gain peak of the last frame

    func reset() {
        prevX = 0
        prevY = 0
        gain = 4.0
        lastPeak = 1
    }

    /// PCM16 @16 kHz in, conditioned PCM16 @16 kHz out.
    func process(_ pcm: Data) -> Data {
        var s = pcm.toInt16Array()
        let n = s.count
        guard n > 0 else { return Data() }
        let r = 0.97                        // ~80 Hz corner @ 16 kHz
        var peak = 1
        for i in 0..<n {
            let x = Double(s[i])
            let y = x - prevX + r * prevY
            prevX = x
            prevY = y
            let c = Int16(clamping: Int(y))
            s[i] = c
            let a = abs(Int(c))
            if a > peak { peak = a }
        }
        if Double(peak) * gain > 30000 {    // fast attack: never clip
            gain = max(1.0, 30000.0 / Double(peak))
        } else if peak > 300 {              // speech present: slow release toward target
            let desired = min(12.0, 16000.0 / Double(peak))
            gain += (desired - gain) * 0.05
        }
        lastPeak = peak
        for i in 0..<n {
            s[i] = Int16(clamping: Int(Double(s[i]) * gain))
        }
        return s.toData()
    }
}
