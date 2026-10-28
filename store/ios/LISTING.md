# Cheto Gallery — App Store listing

Bundle id: `ai.cheto.gallery` · Team: `HAZ2FMYWDT` (Apple ID info@lega.digital, enrolled as an
**individual**, so the store shows the seller as "Sitki Berat Celik", not Lega Digital) · SKU:
`cheto-gallery-ios` · App Store Connect app created 2026-10-28.

## Store listing (Turkish)

**Name** (max 30): `Cheto Gallery`

**Subtitle** (max 30, now 28): `Müşteri videoları tek akışta`

**Promotional text** (max 170, can change without a review):
Instagram, Messenger ve WhatsApp'tan gelen müşteri videolarını tek akışta inceleyin; yüz ve plakayı
bulanıklaştırıp dışa aktarın.

**Keywords** (max 100, comma-separated, no spaces):
`video,galeri,instagram,whatsapp,messenger,dm,reels,hikaye,bulanıklaştırma,plaka,filigran,salon`

**Description** (max 4000):

Cheto Gallery, müşterilerinizin Instagram, Messenger ve WhatsApp mesajlarıyla gönderdiği videoları
tam ekran, tek bir akışta incelemenizi sağlar.

• Tek akış: yukarı kaydırın, sıradaki videoya ya da sıradaki müşteriye geçin.
• Kanal etiketi: Messenger ve WhatsApp'tan gelenler adın yanında işaretlenir.
• Yüz ve plaka bulanıklaştırma, filigran ve küfür filtresi (bip) ile dışa aktarın.
• Telefona kaydedin, Instagram Hikaye'ye gönderin ya da Reels için hazırlayın.
• Yapay zekâ ile video için haber metni (caption) üretin.

Uygulama, Cheto hesabınızla çalışır; giriş için size verilen kullanıcı adı ve şifre gerekir.

**Support URL**: `https://cheto.ai` · **Privacy policy URL**: `https://cheto.ai/privacy`
(cheto.ai is the same server as dmrandevu.com; both pages answer 200, and the privacy page
carries the contact address). The app signs in to `https://cheto.ai` by default since build 3.

**Category**: Photo & Video (primary), Business (secondary). **Price**: Free.

**Copyright**: `2026 Sitki Berat Celik`

## Screenshots

The one required slot is **iPhone with Dynamic Island (medium display)**: 6.1" or 6.3", portrait
1179×2556 or 1206×2622, 3 to 10 of them. App Store Connect uses that set for every other iPhone
size. (The 6.9" slot is optional now.)

Four were uploaded on 2026-10-28: feed, watermark, caption and tour. `AppStoreScreenshots` in the
UI tests took them in the iPhone 17 Pro Max simulator, signed in to the review demo account. They
were shot at 1320×2868 and scaled with `sips -z 2622 1206` (the aspect ratios differ by under 0.1%).
They show copied real `trafik_cezasi` videos under demo names, so **they are not in the
repository**. Before reusing a frame, check that no plate or face is readable in it.

## App Privacy (App Store Connect → App Privacy)

Matches `ios/DMRandevuGaleri/PrivacyInfo.xcprivacy`:

- Data collected: **Identifiers → User ID** (the Cheto account the person signs in with), linked to
  the user, used for **App Functionality** only, not for tracking.
- Nothing else is collected from the user. The videos and names the feed shows are the salon's
  customers' messages, fetched from the salon's own server; the app adds nothing to them.
- No tracking, no ads, no third-party SDK.

## Age rating

Answer the questionnaire truthfully. The feed shows customers' videos unmoderated, including
traffic fights (`trafik_kavgasi`), so expect a high rating (Play was set to 18+).

## Export compliance

`ITSAppUsesNonExemptEncryption = NO` is in `Info.plist` (HTTPS only), so App Store Connect does
not ask on each build.

## App Review: sign-in

The app is sign-in only, so App Review needs a username and password. **Never give them a real
account.** The reviewer will swipe through the feed, and:

- moving to another customer **deletes** the customer left behind from the server, five seconds
  later, so a reviewer on a real account erases real conversations;
- on `trafik_cezasi`, a right swipe **reports the video to real officers**.

The review account must therefore be a gallery-only admin (made on `/admin/admins`) whose only
Instagram account is a demo one holding generated videos and made-up names, never
`trafik_cezasi`. Because each review run deletes what it swipes past, the demo conversations
need to be put back before every submission, or the next reviewer finds an empty feed and rejects
the app for having no content. Say so in the review notes, in English:

> Sign in with the demo account below. The feed shows videos that a business's customers sent it
> over Instagram, Messenger and WhatsApp. Swipe up for the next video. Moving past a customer's
> last video removes that customer from the list (this is the intended workflow: reviewed
> conversations are cleared). The account holds demo data only.

## Checklist

1. **Accept the updated Apple Developer Program License Agreement** (developer.apple.com/account).
   Until then App Store Connect will not take new submissions. The deadline shown was 2026-10-29.
2. Build: `1.1 (3)` (server default `https://cheto.ai`) is the one attached; `1.1 (2)` was
   uploaded first, the same day. For the next one raise `CURRENT_PROJECT_VERSION` in the
   project (App Store Connect refuses a repeat), then Product → Archive → Distribute App → App
   Store Connect, or the `xcodebuild` lines in `ios/README.md`.
3. **TestFlight** (team only, like Play's internal testing): add the team as App Store Connect users
   (Users and Access), then TestFlight → Internal Testing → add them. No review is needed.
4. **App Store**: fill the listing above, add screenshots, App Privacy, age rating, pricing and
   availability. For the EU, either give trader status (Digital Services Act) or leave the EU out
   of availability. Add the demo account and notes under App Review Information, pick the
   build, then submit. Run `scripts/seed-review-demo.js` on the server (DMRandevu repo) first.
