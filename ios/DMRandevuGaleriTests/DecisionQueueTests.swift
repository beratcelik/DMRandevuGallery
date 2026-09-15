import Testing
@testable import DMRandevuGaleri

/// Kaydırma kararlarının geri alma defteri.
///
/// EN KRİTİK DAVRANIŞ: karar, konuşması listeden SİLİNSE BİLE hayatta kalmalı.
/// Sahip son videoyu karara bağlayıp bir sonraki müşteriye geçtiğinde, geride
/// bıraktığı konuşma silme sırasına giriyor; karar o anda anahtarla aransaydı
/// hiçbir şey bulunamaz ve sessizce kaybolurdu.
struct DecisionQueueTests {

    private let conversation = Conversation(
        salonId: "s",
        clientId: "a",
        clientName: "a",
        urls: ["https://cdn/1.mp4", "https://cdn/2.mp4"],
        mediaTs: [],
        lastMessageDate: nil
    )

    private var page: FeedPage { FeedPage(conversationKey: conversation.key, mediaIndex: 0) }

    @Test("boş defter")
    func emptyLedger() {
        let ledger = DecisionLedger()
        #expect(ledger.isEmpty)
        #expect(ledger[page.id] == nil)
    }

    @Test("karar konuşmanın ANLIK GÖRÜNTÜSÜNÜ taşıyor")
    func carriesConversationSnapshot() {
        var ledger = DecisionLedger()
        ledger.put(QueuedDecision(page: page, conversation: conversation, decision: .report))

        // Anahtar değil, konuşmanın kendisi: liste bu karar beklerken
        // değişebiliyor ve anahtarla arama boş dönerdi.
        #expect(ledger[page.id]?.conversation.clientId == "a")
        #expect(ledger[page.id]?.decision == .report)
    }

    @Test("aynı sayfaya ikinci karar öncekinin YERİNE geçiyor")
    func secondDecisionReplacesFirst() {
        var ledger = DecisionLedger()
        ledger.put(QueuedDecision(page: page, conversation: conversation, decision: .report))
        ledger.put(QueuedDecision(page: page, conversation: conversation, decision: .dismiss))

        #expect(ledger.count == 1)
        // Sahip fikrini değiştirdiyse ağa yalnızca son kararı çıkmalı.
        #expect(ledger[page.id]?.decision == .dismiss)
    }

    @Test("aynı anda birden çok karar bekleyebiliyor")
    func multipleDecisionsCoexist() {
        // Silme kuyruğunda tek bekleyen var; burada sahip saniyede bir karar
        // verebiliyor ve ikisi de kendi penceresini yaşamalı.
        let second = FeedPage(conversationKey: conversation.key, mediaIndex: 1)
        var ledger = DecisionLedger()
        ledger.put(QueuedDecision(page: page, conversation: conversation, decision: .report))
        ledger.put(QueuedDecision(page: second, conversation: conversation, decision: .dismiss))

        #expect(ledger.count == 2)
        #expect(ledger.latest?.page == second)
    }

    @Test("geri alma yalnızca o sayfayı kaldırıyor")
    func undoRemovesOnlyThatPage() {
        let second = FeedPage(conversationKey: conversation.key, mediaIndex: 1)
        var ledger = DecisionLedger()
        ledger.put(QueuedDecision(page: page, conversation: conversation, decision: .report))
        ledger.put(QueuedDecision(page: second, conversation: conversation, decision: .dismiss))
        ledger.remove(page.id)

        #expect(ledger[page.id] == nil)
        #expect(ledger[second.id] != nil)
    }

    @Test("en son karar geri alma çipinin gösterdiği")
    func latestFollowsInsertionOrder() {
        // Sözlükte sıra yok; sırayı ayrıca tutmazsak çip her çizimde başka bir
        // kararı gösterirdi.
        let second = FeedPage(conversationKey: conversation.key, mediaIndex: 1)
        var ledger = DecisionLedger()
        ledger.put(QueuedDecision(page: second, conversation: conversation, decision: .dismiss))
        ledger.put(QueuedDecision(page: page, conversation: conversation, decision: .report))
        #expect(ledger.latest?.page == page)
    }

    @Test("bir konuşmanın bekleyen kararları toplu elemede iptal edilebiliyor")
    func filtersByConversation() {
        let other = Conversation(
            salonId: "s", clientId: "b", clientName: "b",
            urls: ["https://cdn/3.mp4"], mediaTs: [], lastMessageDate: nil
        )
        var ledger = DecisionLedger()
        ledger.put(QueuedDecision(page: page, conversation: conversation, decision: .report))
        ledger.put(
            QueuedDecision(
                page: FeedPage(conversationKey: other.key, mediaIndex: 0),
                conversation: other,
                decision: .report
            )
        )

        #expect(ledger.of(conversationKey: conversation.key).count == 1)
    }

    @Test("karar konuşması listeden kalksa da hayatta kalıyor")
    func survivesConversationRemoval() {
        // BU TESTİN KORUDUĞU ARIZA: karar uygulanırken konuşma anahtarla
        // aransaydı — liste artık onu taşımıyor — hiçbir şey bulunamaz ve karar
        // sessizce kaybolurdu.
        var items = [conversation]
        var ledger = DecisionLedger()
        ledger.put(QueuedDecision(page: page, conversation: conversation, decision: .report))

        items.removeAll { $0.key == conversation.key }

        #expect(items.isEmpty)
        #expect(ledger[page.id]?.conversation.clientId == "a")
    }
}
