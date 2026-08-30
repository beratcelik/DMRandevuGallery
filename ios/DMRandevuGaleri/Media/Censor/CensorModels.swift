import CryptoKit
import Foundation

/// The speech models the censor pass needs, fetched the first time the filter is switched on.
///
/// They are not in the app. Together they are a quarter of a gigabyte, which would make the
/// download something nobody wants to sit through for a filter that may never be used. They are
/// downloaded once, verified, and kept. The separation model is small enough to ship and is in the
/// bundle already.
///
/// A model that is missing or damaged fails the export. Falling back to no censoring would hand
/// over a video with the swearing still in it.
actor CensorModels {

    struct ModelUnavailableError: Error {
        var message: String
    }

    /// `sha256` is checked after every download. A truncated file — the phone lost signal two
    /// thirds of the way through — otherwise loads as a valid-looking model that recognises
    /// nothing, and silently stops finding swearing.
    enum Model: CaseIterable {
        case whisperBase
        case whisperSmall

        var fileName: String {
            switch self {
            case .whisperBase: return "ggml-base-q5_1.bin"
            case .whisperSmall: return "ggml-small-q5_1.bin"
            }
        }

        var url: URL {
            URL(string: "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/\(fileName)")!
        }

        var sizeBytes: Int64 {
            switch self {
            case .whisperBase: return 59_707_625
            case .whisperSmall: return 190_085_487
            }
        }

        var sha256: String {
            switch self {
            case .whisperBase:
                return "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"
            case .whisperSmall:
                return "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"
            }
        }
    }

    nonisolated func file(for model: Model) -> URL {
        Self.directory.appendingPathComponent(model.fileName)
    }

    nonisolated func isInstalled(_ model: Model) -> Bool {
        let url = file(for: model)
        let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int64)
        return size.flatMap { $0 } == model.sizeBytes
    }

    nonisolated var allInstalled: Bool { Model.allCases.allSatisfy(isInstalled) }

    /// Total bytes still to fetch, for telling the operator what they are waiting for.
    nonisolated var bytesOutstanding: Int64 {
        Model.allCases.filter { !isInstalled($0) }.reduce(0) { $0 + $1.sizeBytes }
    }

    /// Downloads whatever is missing. `onProgress` reports 0…1 across all of them together.
    func ensureAvailable(onProgress: @escaping (Double) -> Void) async throws {
        let missing = Model.allCases.filter { !isInstalled($0) }
        guard !missing.isEmpty else {
            onProgress(1)
            return
        }
        try FileManager.default.createDirectory(
            at: Self.directory, withIntermediateDirectories: true
        )
        let total = Double(missing.reduce(0) { $0 + $1.sizeBytes })
        var done: Int64 = 0
        for model in missing {
            try await download(model) { bytes in
                onProgress(min(1, max(0, Double(done + bytes) / total)))
            }
            done += model.sizeBytes
        }
        onProgress(1)
    }

    private func download(_ model: Model, onBytes: @escaping (Int64) -> Void) async throws {
        let target = file(for: model)
        // Downloaded beside the real name and moved into place at the end, so a download that
        // dies halfway can never be picked up as a finished model.
        let partial = target.appendingPathExtension("part")
        try? FileManager.default.removeItem(at: partial)
        FileManager.default.createFile(atPath: partial.path, contents: nil)

        do {
            let handle = try FileHandle(forWritingTo: partial)
            defer { try? handle.close() }
            var digest = SHA256()
            var written: Int64 = 0

            let (bytes, response) = try await URLSession.shared.bytes(from: model.url)
            if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                throw ModelUnavailableError(
                    message: "Could not fetch \(model.fileName): HTTP \(http.statusCode)"
                )
            }

            var buffer = Data()
            buffer.reserveCapacity(1 << 16)
            for try await byte in bytes {
                buffer.append(byte)
                if buffer.count >= 1 << 16 {
                    handle.write(buffer)
                    digest.update(data: buffer)
                    written += Int64(buffer.count)
                    onBytes(written)
                    buffer.removeAll(keepingCapacity: true)
                }
            }
            if !buffer.isEmpty {
                handle.write(buffer)
                digest.update(data: buffer)
                written += Int64(buffer.count)
                onBytes(written)
            }
            try handle.close()

            guard written == model.sizeBytes else {
                throw ModelUnavailableError(
                    message: "\(model.fileName) came back \(written) bytes, "
                        + "expected \(model.sizeBytes)"
                )
            }
            let actual = digest.finalize().map { String(format: "%02x", $0) }.joined()
            guard actual.caseInsensitiveCompare(model.sha256) == .orderedSame else {
                throw ModelUnavailableError(
                    message: "\(model.fileName) does not match its checksum"
                )
            }
            try? FileManager.default.removeItem(at: target)
            try FileManager.default.moveItem(at: partial, to: target)
        } catch let error as ModelUnavailableError {
            try? FileManager.default.removeItem(at: partial)
            throw error
        } catch {
            try? FileManager.default.removeItem(at: partial)
            throw ModelUnavailableError(
                message: "Could not fetch \(model.fileName): \(error.localizedDescription)"
            )
        }
    }

    /// Kept in Application Support rather than Caches: the system empties the cache when storage
    /// runs low, and re-downloading a quarter of a gigabyte because the phone wanted a few
    /// megabytes back is not a trade worth making.
    nonisolated static let directory: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)
            .first ?? URL(fileURLWithPath: NSTemporaryDirectory())
        return base.appendingPathComponent("censor-models", isDirectory: true)
    }()
}
