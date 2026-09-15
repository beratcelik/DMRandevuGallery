import CoreGraphics
import Testing
@testable import DMRandevuGaleri

/// Düz akışın sayfa cebiri.
///
/// NEDEN TEST EDİLİYOR: bu kurallar sistemin geri alınamaz tek yan etkisini
/// tetikliyor — hangi müşterinin SİLİNECEĞİNİ belirliyorlar. Akış düzleşince
/// "sayfa değişti" artık "müşteri değişti" demek değil ve kural bunu ayırt
/// edemezse, sahip aynı müşterinin ikinci videosuna geçtiği anda o müşteri beş
/// saniye sonra sessizce siliniyor.
struct FeedPagesTests {

    private func conversation(_ client: String, videos: Int) -> Conversation {
        Conversation(
            salonId: "s",
            clientId: client,
            clientName: client,
            urls: (0..<videos).map { "https://cdn/\(client)/\($0).mp4" },
            mediaTs: [],
            lastMessageDate: nil
        )
    }

    private var items: [Conversation] {
        [conversation("a", videos: 3), conversation("b", videos: 1), conversation("c", videos: 2)]
    }
    private var keys: [String] { items.map(\.key) }

    @Test("her video bir sayfa")
    func buildsOnePagePerVideo() {
        let feed = buildFeed(items)
        #expect(feed.count == 6)
        #expect(feed[0] == FeedPage(conversationKey: "s:a", mediaIndex: 0))
        #expect(feed[3] == FeedPage(conversationKey: "s:b", mediaIndex: 0))
        #expect(feed[5] == FeedPage(conversationKey: "s:c", mediaIndex: 1))
    }

    @Test("videosuz konuşma sayfa üretmiyor")
    func skipsEmptyConversations() {
        // Sayfasız kalmalı: bakacak hiçbir şeyi olmayan siyah bir sayfadan
        // çıkmak için kaydırmak, o müşteriyi silmek olurdu.
        let feed = buildFeed([conversation("bos", videos: 0), conversation("a", videos: 2)])
        #expect(feed.count == 2)
        #expect(feed.allSatisfy { $0.conversationKey == "s:a" })
    }

    @Test("sayfa kimliği işaret anahtarıyla aynı biçimde")
    func pageIDMatchesMarkKey() {
        // İhbar işaretleri ve elle küfür işaretleri ZATEN bu dizgiyi kullanıyor.
        #expect(FeedPage(conversationKey: "s:a", mediaIndex: 2).id == "s:a#2")
    }

    @Test("aynı konuşma içinde ilerlemek HİÇBİR ŞEY silmiyor")
    func withinConversationNeverDeletes() {
        // Bu testin koruduğu arıza: üç videolu bir müşteride ikinci videoya
        // geçmek, sahip hâlâ onun videosunu izlerken o müşteriyi silme sırasına
        // alıyordu.
        let action = onSettled(
            previousConversationKey: "s:a",
            next: FeedPage(conversationKey: "s:a", mediaIndex: 1),
            conversationKeys: keys,
            pendingDeleteKey: nil
        )
        #expect(action == .none)
    }

    @Test("ileri yönde konuşma değişimi silme sırasına alıyor")
    func forwardAcrossConversationsQueuesDelete() {
        let action = onSettled(
            previousConversationKey: "s:a",
            next: FeedPage(conversationKey: "s:b", mediaIndex: 0),
            conversationKeys: keys,
            pendingDeleteKey: nil
        )
        #expect(action == .queueDelete(conversationIndex: 0))
    }

    @Test("geri kaydırmak ASLA silmiyor")
    func backwardsNeverDeletes() {
        let action = onSettled(
            previousConversationKey: "s:c",
            next: FeedPage(conversationKey: "s:b", mediaIndex: 0),
            conversationKeys: keys,
            pendingDeleteKey: nil
        )
        #expect(action == .none)
    }

    @Test("silinmeyi bekleyen konuşmaya dönmek örtük geri alma")
    func returningCancelsPendingDelete() {
        let action = onSettled(
            previousConversationKey: "s:b",
            next: FeedPage(conversationKey: "s:a", mediaIndex: 2),
            conversationKeys: keys,
            pendingDeleteKey: "s:a"
        )
        #expect(action == .cancelPendingDelete)
    }

    @Test("listede olmayan konuşma hiçbir şey uydurtmuyor")
    func unknownConversationDoesNothing() {
        // Önceki konuşma silinmiş olabilir; orada bir sıra uydurmak YANLIŞ
        // müşteriyi silerdi.
        let action = onSettled(
            previousConversationKey: "s:yok",
            next: FeedPage(conversationKey: "s:b", mediaIndex: 0),
            conversationKeys: keys,
            pendingDeleteKey: nil
        )
        #expect(action == .none)
    }

    @Test("konuşmanın kapladığı sayfa sayısı")
    func spanCountsVideos() {
        #expect(pageSpan(items, conversationKey: "s:a") == 3)
        #expect(pageSpan(items, conversationKey: "s:yok") == 0)
    }
}

/// Kaydırmanın hangi kararı verdiği.
///
/// İKİ YÖNDE DE PAHALI: eşik fazla düşükse videoyu izlerken yapılan küçük bir
/// parmak kayması emniyet birimine ihbar gönderiyor; fazla yüksekse sahip
/// kararını veremiyor ve ekranı zorluyor.
struct SwipeDecisionTests {

    private let width: CGFloat = 400

    @Test("kısa sürükleme karar değil")
    func shortDragIsNotADecision() {
        #expect(decisionFor(translation: 40, velocity: 0, width: width) == nil)
    }

    @Test("eşiği geçen sağ sürükleme ihbar")
    func rightPastThresholdReports() {
        #expect(decisionFor(translation: width * 0.3, velocity: 0, width: width) == .report)
    }

    @Test("eşiği geçen sol sürükleme eleme")
    func leftPastThresholdDismisses() {
        #expect(decisionFor(translation: -width * 0.3, velocity: 0, width: width) == .dismiss)
    }

    @Test("hızlı fiske kısa olsa da karar")
    func flingDecidesEvenWhenShort() {
        // Sahip ya kartı ortaya kadar taşıyor ya da kısa ve hızlı bir fiske
        // atıyor. Yalnızca mesafeye baksaydık fiske hiç çalışmazdı.
        #expect(decisionFor(translation: 30, velocity: 1200, width: width) == .report)
    }

    @Test("hız yönü sürükleme yönüyle çelişirse karar yok")
    func oppositeVelocityIsNotADecision() {
        // Parmağını sola sürükleyip sağa fırlatan hareket GERİ ÇEKMEDİR.
        #expect(decisionFor(translation: -30, velocity: 1200, width: width) == nil)
    }

    @Test("genişlik ölçülmeden karar verilmiyor")
    func unmeasuredWidthDecidesNothing() {
        #expect(decisionFor(translation: 500, velocity: 0, width: 0) == nil)
    }
}
