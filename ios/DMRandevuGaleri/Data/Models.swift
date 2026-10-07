import Foundation

/// The inbox a conversation came in through. One account's gallery mixes all three.
enum ChatChannel: Equatable {
    case instagram, facebook, whatsapp
}

/// One customer conversation carrying at least one video, as returned by /admin/media-gallery-page.
struct Conversation: Decodable, Identifiable, Equatable {
    let salonId: String
    let clientId: String
    /// An Instagram @handle without the @ — or, on Messenger and WhatsApp, a person's name or a
    /// phone number. Show ``displayName``, which knows the difference.
    var clientName: String = ""
    /// "instagram", "facebook" or "whatsapp". Optional because servers from before WhatsApp and
    /// Messenger reached the gallery do not send it (and a synthesized Decodable would reject a
    /// missing key even with a default); ``chatChannel`` then reads the clientId prefix.
    var channel: String?
    /// Opaque: always played through the server's media proxy, never fetched directly.
    var urls: [String] = []
    /// Index-aligned with ``urls``: when each video arrived.
    var mediaTs: [String?] = []
    var lastMessageDate: String?

    /// Stable identity — the delete flow tracks pages by this, never by list index.
    var key: String { "\(salonId):\(clientId)" }

    var chatChannel: ChatChannel {
        switch channel {
        case "instagram": return .instagram
        case "facebook": return .facebook
        case "whatsapp": return .whatsapp
        default:
            if clientId.hasPrefix("fb:") { return .facebook }
            if clientId.hasPrefix("wa:") { return .whatsapp }
            return .instagram
        }
    }

    /// The customer as the header shows them. Only Instagram has @handles.
    var displayName: String {
        chatChannel == .instagram ? "@\(clientName)" : clientName
    }

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

/// Caption ucunun hata gövdesi: `{ error, message }`.
struct CaptionError: Decodable {
    var error: String?
    var message: String?

    /// Ekrana yazılacak cümle; `message` sunucunun ayrıntılı olanı.
    var reason: String? { message ?? error }
}

/// Caption üretilemedi ve sunucu nedenini söyledi. "Konuşma bulunamadı" ile "model yanıt vermedi"
/// arasındaki fark, aynı ekranda bekleyen kişi için bir sonraki adımı belirliyor.
struct CaptionFailedError: Error {
    let status: Int
    let serverMessage: String?
}

/// Thrown when the server rejects the session; the UI drops back to the login screen.
struct UnauthorizedError: Error {}

/// Şifre değişmedi; `message` sunucunun söylediği (Türkçe) sebep, doğrudan ekrana yazılabilir.
struct PasswordChangeError: Error {
    let message: String
}

struct AccountNotFoundError: Error {}

/// The stored or typed server address is not a usable URL.
struct InvalidServerAddressError: Error {}

/// Any other non-2xx answer, kept apart from the two the UI reacts to specifically.
struct HTTPStatusError: Error {
    let code: Int
}
