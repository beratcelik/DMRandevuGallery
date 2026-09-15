import SwiftUI

/// İlk açılış tanıtımı: akışın hangi harekete ne yaptığı ve hangi düğmenin ne olduğu.
///
/// NEDEN VAR: bu ekranda tek bir yazı yok. Karar tamamen kaydırmayla veriliyor ve iki ayrı
/// "geri alma" yolu (çip ve geri kaydırma) hiçbir yerde yazmıyor; iki düğmenin de yalnızca UZUN
/// BASINCA ortaya çıkan ikinci bir ayarı var ve tek ipucu köşedeki minik bir rozet. Bunlar kodda
/// bilerek "keşfedilmesi gerekmeyen, bir kez kurulan düğmeler" diye anlatılıyor — ama bir kez
/// bile anlatılmazsa hiç kurulmuyorlar.
///
/// NEDEN TAM EKRAN VE NEDEN AKIŞIN ÜSTÜNDE: altındaki akış dokunmaya ve kaydırmaya karşı çok
/// katmanlı; tanıtım yarı saydam bir katman olsaydı arkadaki karar yüzeyi tanıtımın üstünden
/// ihbar kararı verebilirdi. Dıştaki zemin bu yüzden dokunuşu yakalıyor: hiçbiri aşağı geçmiyor.
///
/// HESABA GÖRE DEĞİŞİYOR: yatay eksen yalnızca ihbar hesabında çalışıyor. Diğer hesaplarda
/// sağa/sola atma satırları hiç gösterilmiyor, çünkü olmayan bir hareketi öğretmek, olanı
/// öğretmemekten daha kötü.
struct OrientationOverlay: View {

    let ihbarEnabled: Bool
    let onDismiss: () -> Void

    var body: some View {
        ZStack {
            Color.black.opacity(0.92)
                .ignoresSafeArea()
                // Altındaki akışa hiçbir dokunuş geçmesin diye.
                .contentShape(.rect)
                .onTapGesture {}

            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text(Strings.tourTitle)
                        .font(.title2.bold())
                        .foregroundStyle(.white)

                    section(Strings.tourGestures)

                    if ihbarEnabled {
                        row("arrow.right", Strings.tourRightTitle, Strings.tourRightDetail)
                        row("arrow.left", Strings.tourLeftTitle, Strings.tourLeftDetail)
                    }
                    row("arrow.up", Strings.tourUpTitle, Strings.tourUpDetail)
                    row(
                        "arrow.down",
                        Strings.tourBackTitle,
                        // İhbar kapalı hesapta geri kaydırmanın iptal edecek bir kararı yok;
                        // silmeyi iptal etmesi ise her hesapta geçerli.
                        ihbarEnabled ? Strings.tourBackDetail : Strings.tourBackDetailPlain
                    )
                    row("play.fill", Strings.tourTapTitle, Strings.tourTapDetail)
                    row("forward.fill", Strings.tourHoldTitle, Strings.tourHoldDetail)

                    section(Strings.tourButtons)

                    // Sıra ekrandaki sırayla AYNI: önce sağ raydakiler yukarıdan aşağıya, sonra
                    // alt sıradakiler soldan sağa. Tanıtımın işi eşleştirme kurmak.
                    row("face.smiling", Strings.faceBlurToggle, Strings.tourFaceDetail)
                    row("car.fill", Strings.plateBlurToggle, Strings.tourPlateDetail)
                    row("signature", Strings.watermarkToggle, Strings.tourWatermarkDetail)
                    row("speaker.wave.2.fill", Strings.censorAudioToggle, Strings.tourCensorDetail)
                    if ihbarEnabled {
                        row("nosign", Strings.bulkDismiss, Strings.tourBulkDetail)
                    }
                    row("arrow.down.circle", Strings.download, Strings.tourDownloadDetail)
                    row("plus.circle", Strings.story, Strings.tourStoryDetail)
                    row("film", Strings.reels, Strings.tourReelsDetail)
                    row("wand.and.stars", Strings.caption, Strings.tourCaptionDetail)

                    Button(action: onDismiss) {
                        Text(Strings.tourDone)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                    }
                    .buttonStyle(.borderedProminent)
                    .padding(.top, 24)
                }
                .padding(24)
            }
        }
        .accessibilityIdentifier("orientationOverlay")
    }

    private func section(_ title: String) -> some View {
        Text(title)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(.white.opacity(0.55))
            .padding(.top, 24)
            .padding(.bottom, 4)
    }

    /// Gerçek simgenin kendisi, benzeri değil: satırın tek işi ekranda görülen şeyle buradaki
    /// cümleyi eşleştirmek.
    private func row(_ icon: String, _ title: String, _ detail: String) -> some View {
        HStack(alignment: .top, spacing: 16) {
            Image(systemName: icon)
                .font(.system(size: 20))
                .foregroundStyle(.white)
                .frame(width: 24, alignment: .center)
                .padding(.top, 2)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white)
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.7))
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 10)
    }
}

/// Tanıtımın gösterildiği yapıyı adlandıran etiket ("1.0+1").
///
/// SÜRÜM NUMARASINA BAĞLI, salt bir "gösterildi" bayrağına değil: hareketler değiştiğinde yapı
/// numarası artırılınca tanıtım bir kez daha çıkıyor. Bugün 1'de duruyor, yani yeniden kurmak
/// tanıtımı geri getirmiyor — uygulama silinmediği sürece bir kez görünüyor.
func currentBuildTag() -> String {
    let info = Bundle.main.infoDictionary
    let short = info?["CFBundleShortVersionString"] as? String ?? "?"
    let build = info?["CFBundleVersion"] as? String ?? "?"
    return "\(short)+\(build)"
}
