import Foundation

/// One customer conversation carrying at least one video, as returned by /admin/media-gallery-page.
struct Conversation: Decodable, Identifiable, Equatable {
    let salonId: String
    let clientId: String
    var clientName: String = ""
    var urls: [String] = []
    /// Index-aligned with ``urls``: when each video arrived on Instagram.
    var mediaTs: [String?] = []
    var lastMessageDate: String?

    /// Stable identity — the delete flow tracks pages by this, never by list index.
    var key: String { "\(salonId):\(clientId)" }

    /// SwiftUI's paging needs the same stable identity, so `id` is deliberately not a UUID.
    var id: String { key }

    /// Send time of one video, falling back to the conversation's own last-message date.
    func sentAt(_ index: Int) -> String? {
        if index < mediaTs.count, let stamp = mediaTs[index] { return stamp }
        return lastMessageDate
    }
}

struct GalleryPage: Decodable {
    var items: [Conversation] = []
    var nextOffset: Int = 0
    var hasMore: Bool = false
    /// Video-carrying conversations this account has in total, not just on this page.
    var total: Int = 0
}

struct ResolveResponse: Decodable {
    let igId: String
    var username: String = ""
}

struct CaptionResponse: Decodable {
    var caption: String = ""
}

/// Thrown when the server rejects the session; the UI drops back to the login screen.
struct UnauthorizedError: Error {}

struct AccountNotFoundError: Error {}

/// The stored or typed server address is not a usable URL.
struct InvalidServerAddressError: Error {}

/// Any other non-2xx answer, kept apart from the two the UI reacts to specifically.
struct HTTPStatusError: Error {
    let code: Int
}
