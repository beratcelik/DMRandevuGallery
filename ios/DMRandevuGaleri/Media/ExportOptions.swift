import Foundation

/// What should be changed about a video on its way out of the app.
///
/// All off by default: an untouched video is delivered by handing over the downloaded file, which
/// is far quicker than the decode-and-re-encode any of these needs.
struct ExportOptions: Equatable {
    var blurFaces = false
    var blurPlates = false
    /// Whether the plate pass runs at the quicker, less thorough setting.
    var fastPlates = true
    /// Account handle to burn into the picture, or nil to leave the video unmarked.
    var watermarkHandle: String?

    var changesNothing: Bool {
        !blurFaces && !blurPlates && watermarkHandle == nil
    }

    static let none = ExportOptions()
}
