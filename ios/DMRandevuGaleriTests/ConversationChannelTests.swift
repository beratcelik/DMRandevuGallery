import XCTest
@testable import DMRandevuGaleri

/// Galeri artık Instagram'la birlikte Messenger ve WhatsApp konuşmalarını da taşıyor ve kanal iki
/// şeye karar veriyor: başlıkta adın nasıl yazıldığına ve ihbar kaydırmasının o videoda çalışıp
/// çalışmadığına.
///
/// NEDEN Android'deki ConversationChannelTest İLE AYNI SENARYOLAR: kural iki uygulamada ayrı
/// yazıldı; ayrışırlarsa bir platform Messenger videosunu ihbar sistemine göndermeye çalışır ve
/// fark ancak sahip telefon değiştirdiğinde görülür.
final class ConversationChannelTests: XCTestCase {

    /// Bir galeri öğesi, sunucunun HER ZAMAN gönderdiği alanlarla. `channel` verilmezse gövdede
    /// hiç yer almıyor — kanalı bilmeyen eski sunucunun yanıtı tam olarak böyle.
    ///
    /// Diğer alanlar fikstürde zorunlu, çünkü sentezlenmiş Decodable eksik anahtarı varsayılan
    /// değerle karşılamıyor; `channel`'ın opsiyonel olmasının sebebi de bu.
    private func decode(
        clientId: String,
        clientName: String = "x",
        channel: String? = nil,
        urls: [String] = ["https://cdn/v.mp4"]
    ) throws -> Conversation {
        var item: [String: Any] = [
            "salonId": "1", "clientId": clientId, "clientName": clientName,
            "urls": urls, "mediaTs": urls.map { _ in NSNull() }, "lastMessageDate": NSNull(),
        ]
        if let channel { item["channel"] = channel }
        let data = try JSONSerialization.data(withJSONObject: item)
        return try JSONDecoder().decode(Conversation.self, from: data)
    }

    func testTheServersChannelIsRead() throws {
        XCTAssertEqual(try decode(clientId: "77", channel: "instagram").chatChannel, .instagram)
        XCTAssertEqual(try decode(clientId: "fb:42", channel: "facebook").chatChannel, .facebook)
        XCTAssertEqual(try decode(clientId: "wa:90555", channel: "whatsapp").chatChannel, .whatsapp)
    }

    /// Kanal alanı yeni. Eski bir sunucu onu göndermiyor ama Messenger ve WhatsApp konuşmalarını
    /// yine aynı önekle adlandırıyordu. Yanıt yine çözülebilmeli ve kanal önekten okunmalı.
    func testAnOldResponseWithoutAChannelIsReadFromThePrefix() throws {
        XCTAssertEqual(try decode(clientId: "17841400").chatChannel, .instagram)
        XCTAssertEqual(try decode(clientId: "fb:42").chatChannel, .facebook)
        XCTAssertEqual(try decode(clientId: "wa:90555").chatChannel, .whatsapp)
    }

    func testOnlyAnInstagramNameCarriesAnAt() throws {
        XCTAssertEqual(try decode(clientId: "77", clientName: "kaan.ig").displayName, "@kaan.ig")
        XCTAssertEqual(
            try decode(clientId: "fb:42", clientName: "Ayşe Yılmaz", channel: "facebook").displayName,
            "Ayşe Yılmaz"
        )
        XCTAssertEqual(
            try decode(clientId: "wa:905551112233", clientName: "+905551112233", channel: "whatsapp").displayName,
            "+905551112233"
        )
    }

    /// WhatsApp videosunun adresi sunucuya özgü bir biçim; uygulama onu olduğu gibi taşımalı.
    func testAWhatsAppVideoAddressIsCarriedUntouched() throws {
        let conversation = try decode(clientId: "wa:90555", channel: "whatsapp", urls: ["wa-media://1/998877"])
        XCTAssertEqual(conversation.urls, ["wa-media://1/998877"])
    }
}
