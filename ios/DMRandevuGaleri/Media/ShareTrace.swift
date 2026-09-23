import AVFoundation
import Foundation
import os

/// A running record of every video handed to Instagram: how long it was at each step, from what
/// the player saw to the file Instagram was given.
///
/// Here for the videos that sometimes arrive in Instagram at 15 seconds. Nothing on the way trims,
/// and every one caught so far was 15 seconds at the source — but it happens now and then, and the
/// device log does not reach back far. So this also goes to a file that survives restarts:
///
///     xcrun devicectl device copy from --device <id> --domain-type appDataContainer \
///         --domain-identifier com.dmrandevu.gallery \
///         --source "Library/Application Support/share-trace.log" --destination share-trace.log
///
/// A step that comes out shorter than the one before it, or at about 15 seconds, is marked
/// SUSPECT, so the line to look for is easy to find. The Android twin is `ShareTrace.kt` and
/// writes the same lines.
enum ShareTrace {

    private static let logger = Logger(subsystem: "com.dmrandevu.gallery", category: "ShareTrace")
    private static let lock = NSLock()

    /// Past this the log rolls over to a single `.1` backup, so it stays bounded.
    private static let maxBytes = 512 * 1024

    /// Instagram's own clip length, which is what the suspicious videos keep coming out at.
    private static let clipMS: Int64 = 15_000
    private static let clipToleranceMS: Int64 = 600

    /// A drop smaller than this is container rounding between steps, not lost video.
    private static let shrinkToleranceMS: Int64 = 1_000

    static func log(_ message: String) {
        logger.info("\(message, privacy: .public)")
        lock.lock()
        defer { lock.unlock() }
        guard let file = fileURL else { return }
        let size = (try? FileManager.default.attributesOfItem(atPath: file.path)[.size] as? Int) ?? 0
        if size > maxBytes {
            let backup = file.appendingPathExtension("1")
            try? FileManager.default.removeItem(at: backup)
            try? FileManager.default.moveItem(at: file, to: backup)
        }
        let line = Data("\(stamp.string(from: Date())) \(message)\n".utf8)
        if let handle = try? FileHandle(forWritingTo: file) {
            defer { try? handle.close() }
            _ = try? handle.seekToEnd()
            try? handle.write(contentsOf: line)
        } else {
            try? line.write(to: file)
        }
    }

    /// Logs `file`'s length and size under `step`. Checks it against `expectedMS` (the length an
    /// earlier step reported) and returns its own length for the next step to check.
    @discardableResult
    static func probe(_ step: String, file: URL, expectedMS: Int64?) async -> Int64? {
        let durationMS = await duration(of: file)
        let flags = suspicion(durationMS: durationMS, expectedMS: expectedMS)
        let bytes = (try? FileManager.default.attributesOfItem(atPath: file.path)[.size] as? Int) ?? 0
        log(
            "\(step): \(format(durationMS)), \(bytes) bytes"
                + (expectedMS.map { " (was \(format($0)))" } ?? "")
                + (flags.isEmpty ? "" : " SUSPECT: \(flags.joined(separator: ", "))")
        )
        return durationMS ?? expectedMS
    }

    static func suspicion(durationMS: Int64?, expectedMS: Int64?) -> [String] {
        guard let durationMS else { return ["unreadable"] }
        var flags: [String] = []
        if abs(durationMS - clipMS) <= clipToleranceMS { flags.append("~15s") }
        if let expectedMS, expectedMS - durationMS > shrinkToleranceMS { flags.append("shorter") }
        return flags
    }

    static func format(_ durationMS: Int64?) -> String {
        durationMS.map { String(format: "%.2fs", Double($0) / 1000) } ?? "?"
    }

    private static func duration(of file: URL) async -> Int64? {
        guard let time = try? await AVURLAsset(url: file).load(.duration), time.isNumeric else {
            return nil
        }
        return Int64((time.seconds * 1000).rounded())
    }

    private static let fileURL: URL? = {
        guard let directory = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask).first else { return nil }
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appendingPathComponent("share-trace.log")
    }()

    private static let stamp: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
        return formatter
    }()
}
