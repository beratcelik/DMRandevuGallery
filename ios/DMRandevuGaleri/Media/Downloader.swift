import Foundation

/// Pulls proxied videos down and hands them either to the photo library or to a cache file for
/// sharing.
///
/// When ``ExportOptions`` asks for anything, the video is run through ``VideoExporter`` before it
/// goes anywhere — nothing leaves the app unprotected once a filter is on. With no options set the
/// downloaded file is delivered as it arrived.
final class Downloader {

    private let session: URLSession
    private let repository: GalleryRepository
    private let exporter: VideoExporter

    init(session: URLSession, repository: GalleryRepository, exporter: VideoExporter) {
        self.session = session
        self.repository = repository
        self.exporter = exporter
    }

    /// Saves the video to the phone's photo library. Returns false when the download itself
    /// failed; a failed *export* throws, because that is the case where an unprotected video
    /// nearly got out.
    func saveToPhotos(
        rawURL: String,
        clientName: String,
        options: ExportOptions,
        onProgress: @escaping (Int) -> Void = { _ in }
    ) async throws -> Bool {
        defer { clearWorkDir() }
        let ready: URL
        do {
            ready = try await prepare(rawURL: rawURL, options: options, onProgress: onProgress)
        } catch is UnauthorizedError {
            throw UnauthorizedError()
        } catch let failure as VideoExporter.ExportFailedError {
            // The caller has to be able to tell "could not process" from "could not download" —
            // one of them means an unprotected video nearly got out.
            throw failure
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            return false
        }

        // A refused library is worth saying out loud — it is the one download failure the
        // operator can actually do something about.
        try await PhotoLibrarySaver.save(ready)
        return true
    }

    /// Downloads to caches/share so the file can be handed to Instagram or a share sheet.
    ///
    /// `playerMS` is how long the player on screen found the video to be, the first length in the
    /// ``ShareTrace`` chain that ends at the file Instagram gets.
    func downloadForShare(
        rawURL: String,
        clientName: String,
        options: ExportOptions,
        playerMS: Int64? = nil,
        onProgress: @escaping (Int) -> Void = { _ in }
    ) async throws -> URL {
        defer { clearWorkDir() }
        let components = URLComponents(string: rawURL)
        let assetID = components?.queryItems?.first { $0.name == "asset_id" }?.value
        ShareTrace.log(
            "share start: client=\(clientName) player=\(ShareTrace.format(playerMS)) "
                + "options=\(options) url=\(rawURL.split(separator: "?").first.map(String.init) ?? rawURL)"
                // The messaging CDN names the video only in the query; the rest of it is a
                // signature that expires anyway.
                + (assetID.map { " asset_id=\($0)" } ?? "")
        )
        let ready: URL
        do {
            ready = try await prepare(
                rawURL: rawURL, options: options, expectedMS: playerMS, onProgress: onProgress
            )
        } catch {
            ShareTrace.log("share failed: \(error)")
            throw error
        }

        let directory = Self.caches.appendingPathComponent(Self.shareDir)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        // One file per share keeps a previous, still-open share from being overwritten.
        for stale in (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? [] {
            try? FileManager.default.removeItem(at: stale)
        }

        let file = directory.appendingPathComponent(fileName(clientName))
        // Same volume, so this is a rename rather than a second copy of the whole video.
        try FileManager.default.moveItem(at: ready, to: file)
        await ShareTrace.probe("share file \(file.lastPathComponent)", file: file, expectedMS: playerMS)
        return file
    }

    /// Downloads the video and applies `options`, returning whichever file should be handed on. A
    /// video the options turn out not to change — asking for face blur when there are no faces —
    /// comes back untouched rather than needlessly re-encoded.
    ///
    /// Throws ``VideoExporter/ExportFailedError`` if the processing cannot be applied — never a
    /// quietly unprotected original.
    private func prepare(
        rawURL: String,
        options: ExportOptions,
        expectedMS: Int64? = nil,
        onProgress: @escaping (Int) -> Void
    ) async throws -> URL {
        let directory = workDir()
        let input = directory.appendingPathComponent("input.mp4")
        onProgress(0)
        try await fetch(rawURL, to: input)
        onProgress(Self.downloadShare)
        let inputMS = await ShareTrace.probe("downloaded", file: input, expectedMS: expectedMS)

        guard !options.changesNothing else { return input }

        let output = directory.appendingPathComponent("processed.mp4")
        let result = try await exporter.export(input: input, output: output, options: options) { percent in
            onProgress(Self.downloadShare + percent * (100 - Self.downloadShare) / 100)
        }
        switch result {
        case .exported(let url, _, _):
            await ShareTrace.probe("exported", file: url, expectedMS: inputMS)
            return url
        case .nothingToDo:
            ShareTrace.log("exported: nothing to do, original passed on")
            return input
        }
    }

    private func fetch(_ rawURL: String, to destination: URL) async throws {
        guard let proxy = repository.proxyURL(rawURL) else { throw InvalidServerAddressError() }
        var request = URLRequest(url: proxy)
        request.setValue(GalleryRepository.userAgent, forHTTPHeaderField: "User-Agent")
        let (temporary, response) = try await session.download(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if status == 401 {
            try? FileManager.default.removeItem(at: temporary)
            throw UnauthorizedError()
        }
        guard (200..<300).contains(status) else {
            try? FileManager.default.removeItem(at: temporary)
            ShareTrace.log("fetch: HTTP \(status)")
            throw HTTPStatusError(code: status)
        }
        // What the server promised against what arrived: a body cut short, or a partial (206)
        // answer saved as though it were the whole video, would show up here first.
        let advertised = response.expectedContentLength
        let read = (try? FileManager.default.attributesOfItem(atPath: temporary.path)[.size] as? Int64) ?? -1
        let range = (response as? HTTPURLResponse)?.value(forHTTPHeaderField: "Content-Range")
        ShareTrace.log(
            "fetch: HTTP \(status) length=\(advertised >= 0 ? String(advertised) : "?") read=\(read)"
                + (range.map { " range=\($0)" } ?? "")
                + (advertised >= 0 && advertised != read ? " SUSPECT: short read" : "")
        )
        try? FileManager.default.removeItem(at: destination)
        // The temporary file is only ours until this call returns, so it moves rather than copies.
        try FileManager.default.moveItem(at: temporary, to: destination)
    }

    /// Deliberately not caches/share: ``downloadForShare`` wipes that directory on every call, and
    /// it is the directory handed to other apps, so intermediates have no business being there.
    private func workDir() -> URL {
        let directory = Self.caches.appendingPathComponent(Self.workDirName)
        // Whatever a previous export left behind, including one killed mid-flight.
        try? FileManager.default.removeItem(at: directory)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    private func clearWorkDir() {
        try? FileManager.default.removeItem(
            at: Self.caches.appendingPathComponent(Self.workDirName)
        )
    }

    private func fileName(_ clientName: String) -> String {
        let safe = String(
            clientName
                .map { $0.isLetter || $0.isNumber || $0 == "_" || $0 == "-" ? $0 : "_" }
                .prefix(24)
        )
        let stamp = Int(Date().timeIntervalSince1970 * 1000)
        return "dmrandevu_\(safe.isEmpty ? "video" : safe)_\(stamp).mp4"
    }

    private static let caches = FileManager.default
        .urls(for: .cachesDirectory, in: .userDomainMask)[0]

    private static let workDirName = "export"
    private static let shareDir = "share"

    /// Share of the progress bar spent downloading before the processing passes start.
    private static let downloadShare = 10
}
