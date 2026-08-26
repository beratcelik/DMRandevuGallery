import Foundation
import Photos

/// Puts a finished video in the phone's photo library, in an album of its own where it does not
/// get lost among the operator's own footage.
enum PhotoLibrarySaver {

    static let albumName = "DMRandevu"

    struct DeniedError: Error {}

    /// Saves `file`, adding it to the app's album when the library allows it.
    ///
    /// Add-only access is enough to save but not to look an album up, so a stricter grant quietly
    /// lands the video in Recents rather than failing — the operator still gets the video, which
    /// is the part that matters.
    static func save(_ file: URL) async throws {
        let status = await request()
        guard status == .authorized || status == .limited else { throw DeniedError() }

        let album = status == .authorized ? try? await album() : nil
        try await PHPhotoLibrary.shared().performChanges {
            let creation = PHAssetCreationRequest.forAsset()
            let options = PHAssetResourceCreationOptions()
            // The caller still owns the working file and clears it itself.
            options.shouldMoveFile = false
            creation.addResource(with: .video, fileURL: file, options: options)

            if let album, let placeholder = creation.placeholderForCreatedAsset {
                PHAssetCollectionChangeRequest(for: album)?
                    .addAssets([placeholder] as NSArray)
            }
        }
    }

    private static func request() async -> PHAuthorizationStatus {
        let current = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        guard current == .notDetermined else { return current }
        return await PHPhotoLibrary.requestAuthorization(for: .readWrite)
    }

    /// The app's album, created on first use.
    private static func album() async throws -> PHAssetCollection? {
        let options = PHFetchOptions()
        options.predicate = NSPredicate(format: "title = %@", albumName)
        let existing = PHAssetCollection.fetchAssetCollections(
            with: .album,
            subtype: .albumRegular,
            options: options
        )
        if let found = existing.firstObject { return found }

        var identifier: String?
        try await PHPhotoLibrary.shared().performChanges {
            let request = PHAssetCollectionChangeRequest
                .creationRequestForAssetCollection(withTitle: albumName)
            identifier = request.placeholderForCreatedAssetCollection.localIdentifier
        }
        guard let identifier else { return nil }
        return PHAssetCollection.fetchAssetCollections(
            withLocalIdentifiers: [identifier],
            options: nil
        ).firstObject
    }
}
