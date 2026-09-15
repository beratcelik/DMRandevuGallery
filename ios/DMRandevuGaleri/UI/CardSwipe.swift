import SwiftUI
import UIKit

/// Karar kartının yatay sürükleme hareketi — EKSEN KİLİTLİ bir UIKit pan'i.
///
/// ─── NEDEN SwiftUI DragGesture DEĞİL ───────────────────────────────────────
/// Bu ekranda bir `DragGesture` BİR KEZ denendi ve geri alındı: dikey
/// kaydırmayı tümden çaldı ve akış hiç kaymaz oldu (bkz. VideoPageView'daki
/// jest notu). SwiftUI'nin sürükleme jesti YÖN BİLMİYOR — parmak hangi yöne
/// giderse gitsin tanıyor ve tanıdığı anda alttaki kaydırma görünümü kaybediyor.
/// `.simultaneousGesture` de denendi; o da başlık düğmelerinin dokunuşlarını
/// yuttu.
///
/// ─── NEDEN BU ÇALIŞIYOR ────────────────────────────────────────────────────
/// UIKit'te bir hareketin BAŞLAYIP başlamayacağı sorulabiliyor:
/// `gestureRecognizerShouldBegin`. Parmağın ilk hızına bakıp yatay baskın
/// değilse "başlama" diyoruz — o hâlde olay, dikey sayfalayan kaydırma
/// görünümüne kalıyor ve akış bugünkü gibi kayıyor. Yatay baskınsa biz
/// başlıyoruz; dikey kaydırma görünümü yatayda zaten hiçbir şey yapmıyor
/// (içeriği ekran genişliğinde).
///
/// ─── NEDEN GÖRÜNMEZ BİR KATMAN ─────────────────────────────────────────────
/// Hareket tanıyıcı, dokunuşun düştüğü görünümün ATALARINA da ulaşıyor. Bu
/// yüzden şeffaf katman videoyu kapatsa bile, onu saran SwiftUI görünümüne
/// bağlı dokunma ve basılı tutma jestleri çalışmaya devam ediyor; denetimler
/// ise bu katmanın ÜSTÜNDE ayrı kardeşler olduğu için dokunuşu önce onlar
/// alıyor.
struct CardPanCatcher: UIViewRepresentable {

    /// Kapalıyken hiçbir şey tanınmıyor: ihbar hattına bağlı olmayan hesapta
    /// yatay eksen tamamen atıl olmalı, kartı oynatmak hiçbir şey yapmayacak
    /// bir karar vaat etmek olurdu.
    let enabled: Bool
    /// Parmak nereye dokundu (kartın üst yarısı mı): kartın hangi yöne
    /// devrileceğini bu belirliyor.
    let onBegan: (CGPoint) -> Void
    let onChanged: (CGSize) -> Void
    let onEnded: (CGSize, CGSize) -> Void

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.backgroundColor = .clear
        let pan = UIPanGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handle(_:))
        )
        pan.delegate = context.coordinator
        view.addGestureRecognizer(pan)
        context.coordinator.pan = pan
        return view
    }

    func updateUIView(_ view: UIView, context: Context) {
        context.coordinator.parent = self
        context.coordinator.pan?.isEnabled = enabled
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {

        var parent: CardPanCatcher
        weak var pan: UIPanGestureRecognizer?

        init(_ parent: CardPanCatcher) { self.parent = parent }

        @objc func handle(_ recognizer: UIPanGestureRecognizer) {
            guard let view = recognizer.view else { return }
            let translation = recognizer.translation(in: view)
            switch recognizer.state {
            case .began:
                parent.onBegan(recognizer.location(in: view))
                parent.onChanged(CGSize(width: translation.x, height: translation.y))
            case .changed:
                parent.onChanged(CGSize(width: translation.x, height: translation.y))
            case .ended, .cancelled, .failed:
                let velocity = recognizer.velocity(in: view)
                parent.onEnded(
                    CGSize(width: translation.x, height: translation.y),
                    CGSize(width: velocity.x, height: velocity.y)
                )
            default:
                break
            }
        }

        /// EKSEN KİLİDİ. Bu dosyanın var oluş sebebi tek satır burada: parmağın
        /// ilk hızı yatay baskın değilse hareket HİÇ BAŞLAMIYOR ve olay dikey
        /// sayfalayıcıya kalıyor.
        ///
        /// Çarpan 1'den büyük (1,2): tam çaprazda kararsız kalmaktansa dikeyden
        /// yana düşmek doğru yön — dikeyi yanlışlıkla çalmak akışı durdurur,
        /// yatayı kaçırmak yalnızca kartın o seferlik oynamaması demek.
        func gestureRecognizerShouldBegin(_ recognizer: UIGestureRecognizer) -> Bool {
            guard let pan = recognizer as? UIPanGestureRecognizer, let view = pan.view else {
                return true
            }
            let velocity = pan.velocity(in: view)
            return abs(velocity.x) > abs(velocity.y) * 1.2
        }

        /// Kaydırma görünümünün kendi hareketiyle AYNI ANDA tanınmıyor: eksen
        /// kilidi zaten ayrımı yapıyor ve eşzamanlı tanıma, çapraz bir
        /// sürüklemede hem kartın oynaması hem akışın kayması demekti.
        func gestureRecognizer(
            _ recognizer: UIGestureRecognizer,
            shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer
        ) -> Bool {
            false
        }
    }
}
