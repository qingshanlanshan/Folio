// folio-capture: records the microphone and/or system audio and streams 16 kHz mono float32 frames
// to stdout for the Folio engine.
//
//   folio-capture [--mic] [--system] [--aec]
//
// stdout: frames of  [track: UInt8 (0 = mic, 1 = system)] [count: UInt32 LE] [count × Float32 LE]
// stderr: one JSON object per line ({"event": "started" | "error" | "stopped", ...})
// Stops on SIGINT / SIGTERM or when stdin closes.
//
// System audio uses a Core Audio process tap (macOS 14.4+): a private aggregate device that
// contains a global tap of everything played, excluding this process.

import AVFoundation
import CoreAudio
import Foundation

let SR = 16000.0
let outFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: SR, channels: 1, interleaved: false)!
let writeQueue = DispatchQueue(label: "folio.write")

func log(_ obj: [String: Any]) {
    if let d = try? JSONSerialization.data(withJSONObject: obj), var s = String(data: d, encoding: .utf8) {
        s += "\n"
        FileHandle.standardError.write(s.data(using: .utf8)!)
    }
}

func fail(_ message: String) -> Never {
    log(["event": "error", "message": message])
    exit(1)
}

func emit(track: UInt8, _ buffer: AVAudioPCMBuffer) {
    let n = Int(buffer.frameLength)
    guard n > 0, let ch = buffer.floatChannelData else { return }
    var data = Data(capacity: 5 + n * 4)
    data.append(track)
    var count = UInt32(n).littleEndian
    withUnsafeBytes(of: &count) { data.append(contentsOf: $0) }
    data.append(UnsafeBufferPointer(start: ch[0], count: n))
    writeQueue.async { FileHandle.standardOutput.write(data) }
}

/// Converts whatever the device delivers to 16 kHz mono.
final class Resampler {
    let converter: AVAudioConverter
    let track: UInt8
    init?(from: AVAudioFormat, track: UInt8) {
        guard let c = AVAudioConverter(from: from, to: outFormat) else { return nil }
        // Mix all input channels down to mono rather than taking only the first.
        if from.channelCount > 1 { c.downmix = true }
        converter = c
        self.track = track
    }
    func push(_ input: AVAudioPCMBuffer) {
        let ratio = SR / input.format.sampleRate
        let cap = AVAudioFrameCount(Double(input.frameLength) * ratio + 64)
        guard let out = AVAudioPCMBuffer(pcmFormat: outFormat, frameCapacity: cap) else { return }
        var given = false
        var err: NSError?
        converter.convert(to: out, error: &err) { _, status in
            if given { status.pointee = .noDataNow; return nil }
            given = true
            status.pointee = .haveData
            return input
        }
        if err == nil { emit(track: track, out) }
    }
}

// MARK: - Microphone

final class Mic {
    let engine = AVAudioEngine()
    var resampler: Resampler?

    func start(aec: Bool) throws {
        let input = engine.inputNode
        if aec {
            // Echo cancellation: removes what the speakers play from the mic signal.
            do {
                try input.setVoiceProcessingEnabled(true)
                if #available(macOS 14.0, *) {
                    input.voiceProcessingOtherAudioDuckingConfiguration =
                        AVAudioVoiceProcessingOtherAudioDuckingConfiguration(enableAdvancedDucking: false, duckingLevel: .min)
                }
            } catch {
                log(["event": "warning", "message": "echo cancellation unavailable: \(error.localizedDescription)"])
            }
        }
        let fmt = input.outputFormat(forBus: 0)
        guard fmt.sampleRate > 0 else { throw NSError(domain: "folio", code: 1, userInfo: [NSLocalizedDescriptionKey: "No microphone input"]) }
        resampler = Resampler(from: fmt, track: 0)
        input.installTap(onBus: 0, bufferSize: 4096, format: fmt) { [weak self] buf, _ in
            self?.resampler?.push(buf)
        }
        engine.prepare()
        try engine.start()
    }

    func stop() {
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
    }
}

// MARK: - System audio (process tap)

func getProp<T: BitwiseCopyable>(_ obj: AudioObjectID, _ selector: AudioObjectPropertySelector, _ value: inout T) -> OSStatus {
    var addr = AudioObjectPropertyAddress(mSelector: selector, mScope: kAudioObjectPropertyScopeGlobal,
                                          mElement: kAudioObjectPropertyElementMain)
    var size = UInt32(MemoryLayout<T>.size)
    return AudioObjectGetPropertyData(obj, &addr, 0, nil, &size, &value)
}

func deviceUID(_ dev: AudioObjectID) -> String? {
    var addr = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyDeviceUID, mScope: kAudioObjectPropertyScopeGlobal,
                                          mElement: kAudioObjectPropertyElementMain)
    var uid: Unmanaged<CFString>?
    var size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
    guard AudioObjectGetPropertyData(dev, &addr, 0, nil, &size, &uid) == noErr, let uid else { return nil }
    return uid.takeRetainedValue() as String
}

final class SystemTap {
    var tapID = AudioObjectID(kAudioObjectUnknown)
    var aggID = AudioObjectID(kAudioObjectUnknown)
    var procID: AudioDeviceIOProcID?
    var resampler: Resampler?
    let queue = DispatchQueue(label: "folio.tap", qos: .userInitiated)

    func start() throws {
        let desc = CATapDescription(stereoGlobalTapButExcludeProcesses: [])
        desc.uuid = UUID()
        desc.isPrivate = true
        desc.muteBehavior = .unmuted
        var err = AudioHardwareCreateProcessTap(desc, &tapID)
        guard err == noErr else { throw osErr("create tap", err) }

        var outDev = AudioObjectID(kAudioObjectUnknown)
        err = getProp(AudioObjectID(kAudioObjectSystemObject), kAudioHardwarePropertyDefaultSystemOutputDevice, &outDev)
        guard err == noErr else { throw osErr("default output", err) }
        guard let uid = deviceUID(outDev) else { throw osErr("output uid", -1) }

        let agg: [String: Any] = [
            kAudioAggregateDeviceNameKey: "Folio system audio",
            kAudioAggregateDeviceUIDKey: UUID().uuidString,
            kAudioAggregateDeviceMainSubDeviceKey: uid,
            kAudioAggregateDeviceIsPrivateKey: true,
            kAudioAggregateDeviceIsStackedKey: false,
            kAudioAggregateDeviceTapAutoStartKey: true,
            kAudioAggregateDeviceSubDeviceListKey: [[kAudioSubDeviceUIDKey: uid]],
            kAudioAggregateDeviceTapListKey: [[kAudioSubTapDriftCompensationKey: true,
                                               kAudioSubTapUIDKey: desc.uuid.uuidString]],
        ]
        err = AudioHardwareCreateAggregateDevice(agg as CFDictionary, &aggID)
        guard err == noErr else { throw osErr("aggregate device", err) }

        var asbd = AudioStreamBasicDescription()
        err = getProp(tapID, kAudioTapPropertyFormat, &asbd)
        guard err == noErr, let fmt = AVAudioFormat(streamDescription: &asbd) else { throw osErr("tap format", err) }
        resampler = Resampler(from: fmt, track: 1)

        err = AudioDeviceCreateIOProcIDWithBlock(&procID, aggID, queue) { [weak self] _, inData, _, _, _ in
            guard let self, let buf = AVAudioPCMBuffer(pcmFormat: fmt, bufferListNoCopy: inData, deallocator: nil) else { return }
            self.resampler?.push(buf)
        }
        guard err == noErr else { throw osErr("io proc", err) }
        err = AudioDeviceStart(aggID, procID)
        guard err == noErr else { throw osErr("start", err) }
    }

    func stop() {
        if aggID != kAudioObjectUnknown {
            AudioDeviceStop(aggID, procID)
            if let p = procID { AudioDeviceDestroyIOProcID(aggID, p) }
            AudioHardwareDestroyAggregateDevice(aggID)
        }
        if tapID != kAudioObjectUnknown { AudioHardwareDestroyProcessTap(tapID) }
    }

    func osErr(_ what: String, _ code: OSStatus) -> NSError {
        NSError(domain: "folio", code: Int(code), userInfo: [NSLocalizedDescriptionKey: "System audio: \(what) failed (\(code))"])
    }
}

// MARK: - main

let args = Set(CommandLine.arguments.dropFirst())
let wantMic = args.contains("--mic")
let wantSystem = args.contains("--system")
if !wantMic && !wantSystem { fail("Pass --mic and/or --system") }

var mic: Mic?
var tap: SystemTap?

func shutdown() {
    mic?.stop()
    tap?.stop()
    writeQueue.sync {}
    log(["event": "stopped"])
    exit(0)
}

for sig in [SIGINT, SIGTERM] {
    signal(sig, SIG_IGN)
    let src = DispatchSource.makeSignalSource(signal: sig, queue: .main)
    src.setEventHandler { shutdown() }
    src.resume()
    _ = Unmanaged.passRetained(src)
}
// Parent gone → stop.
DispatchQueue.global().async {
    while FileHandle.standardInput.availableData.count > 0 {}
    DispatchQueue.main.async { shutdown() }
}

func begin() {
    do {
        if wantSystem {
            tap = SystemTap()
            try tap!.start()
        }
        if wantMic {
            mic = Mic()
            try mic!.start(aec: args.contains("--aec"))
        }
        log(["event": "started", "mic": wantMic, "system": wantSystem, "sampleRate": SR])
    } catch {
        tap?.stop()
        fail(error.localizedDescription)
    }
}

if wantMic {
    switch AVCaptureDevice.authorizationStatus(for: .audio) {
    case .authorized: begin()
    case .notDetermined:
        AVCaptureDevice.requestAccess(for: .audio) { ok in
            DispatchQueue.main.async { if ok { begin() } else { fail("Microphone access denied") } }
        }
    default: fail("Microphone access denied. Allow it in System Settings → Privacy & Security → Microphone.")
    }
} else {
    begin()
}

dispatchMain()
